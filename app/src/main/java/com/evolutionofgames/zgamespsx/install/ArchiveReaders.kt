package com.evolutionofgames.zgamespsx.install

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

open class InstallException(
    message: String,
    cause: Throwable? = null,
    /** User-facing extra detail for the error dialog (shown only when it is plain text, never a stack trace). */
    val details: String? = null
) : Exception(message, cause)

/** The file to install no longer exists (already installed + removed, or moved/deleted by the user). */
class SourceGoneException : InstallException("The downloaded file couldn't be found — it may have been moved or deleted. Download it again.")

/**
 * An archive needs a password (or the given one was wrong). Caught by the
 * install retry loop, which prompts the user — this is NEVER shown to
 * the user as a plain install error.
 */
class PasswordNeededException(val wrongPassword: Boolean) : Exception(
    if (wrongPassword) "Wrong password." else "This archive is password-protected."
)

/** One file inside an archive. [handle] is reader-specific. */
class ArchiveEntry(val path: String, val size: Long, val isDir: Boolean, internal val handle: Any)

interface ArchiveReader : Closeable {
    val entries: List<ArchiveEntry>
    /** True when entries must be extracted in order for speed/correctness (solid RAR). */
    val isSolid: Boolean
    /** Writes the entry's bytes to [out]. */
    fun extract(entry: ArchiveEntry, out: OutputStream)
}

class ZipArchiveReader private constructor(private val zip: ZipFile) : ArchiveReader {
    override val isSolid = false
    override val entries: List<ArchiveEntry> = zip.entries().asSequence()
        .map { ArchiveEntry(it.name, it.size, it.isDirectory, it) }.toList()

    override fun extract(entry: ArchiveEntry, out: OutputStream) {
        val ze = entry.handle as ZipEntry
        try {
            zip.getInputStream(ze).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        } catch (e: java.util.zip.ZipException) {
            val m = e.message.orEmpty()
            if (m.contains("encrypt", true) || m.contains("password", true)) {
                // java.util.zip cannot decrypt at all: hand off to the password retry loop.
                throw PasswordNeededException(false)
            }
            throw InstallException("The ZIP file is damaged or incomplete (${m.ifBlank { "read error" }}). Please download it again.", e)
        }
    }

    override fun close() { try { zip.close() } catch (_: IOException) { } }

    companion object {
        /**
         * Opens with java.util.zip. Encrypted entries surface as PasswordNeededException on extract —
         * AND at open: java.util.zip throws ZipException "invalid CEN header (encrypted entry)" while
         * reading the central directory of an encrypted ZIP, so an encrypted archive used to fail here
         * as "damaged" and never reach the password prompt. An encryption-flavored failure is a
         * password prompt, not a damaged file.
         */
        fun open(file: File): ZipArchiveReader {
            var last: Throwable? = null
            // UTF-8 first; legacy archives use a DOS code page, which ISO-8859-1 reads without throwing.
            for (cs in listOf<Charset>(Charsets.UTF_8, Charsets.ISO_8859_1)) {
                try {
                    return ZipArchiveReader(ZipFile(file, cs))
                } catch (e: IllegalArgumentException) { last = e }
                catch (e: java.util.zip.ZipException) {
                    if (e.message?.contains("encrypt", true) == true) throw PasswordNeededException(false)
                    last = e; break
                }
            }
            throw InstallException("This ZIP file can't be opened — it may be damaged or incomplete. Please download it again.", last)
        }
    }
}

/**
 * ZIP reader via zip4j — the only path that can actually DECRYPT entries
 * (java.util.zip cannot). Used when a password is supplied: ZipCrypto and
 * AES-encrypted entries both work. Wrong password surfaces as
 * PasswordNeededException(true).
 */
class Zip4jArchiveReader private constructor(private val zip: net.lingala.zip4j.ZipFile) : ArchiveReader {
    override val isSolid = false
    override val entries: List<ArchiveEntry> = try {
        zip.fileHeaders.map { ArchiveEntry(it.fileName, it.uncompressedSize, it.isDirectory, it) }
    } catch (e: net.lingala.zip4j.exception.ZipException) {
        throw InstallException("This ZIP file can't be opened — it may be damaged or incomplete. Please download it again.", e)
    }

    override fun extract(entry: ArchiveEntry, out: OutputStream) {
        val fh = entry.handle as net.lingala.zip4j.model.FileHeader
        try {
            zip.getInputStream(fh).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            if (m.contains("password", true)) throw PasswordNeededException(true)
            if (m.contains("encrypt", true)) throw PasswordNeededException(false)
            throw InstallException("The ZIP file is damaged or incomplete (${m.ifBlank { "read error" }}). Please download it again.", e)
        }
    }

    override fun close() { try { zip.close() } catch (_: IOException) { } }

    companion object {
        fun open(file: File, password: String): Zip4jArchiveReader {
            return try {
                Zip4jArchiveReader(net.lingala.zip4j.ZipFile(file, password.toCharArray()))
            } catch (e: net.lingala.zip4j.exception.ZipException) {
                val m = e.message.orEmpty()
                if (m.contains("password", true)) throw PasswordNeededException(true)
                throw InstallException("This ZIP file can't be opened — it may be damaged or incomplete. Please download it again.", e)
            }
        }
    }
}

class RarArchiveReader private constructor(
    private val file: File,
    private val password: String?,
    private var rar: Archive,
    files: List<FileHeader>
) : ArchiveReader {
    override val isSolid: Boolean = files.any { it.isSolid }
    private var headers: List<FileHeader> = files
    private val indexOf = java.util.IdentityHashMap<FileHeader, Int>().also { m -> files.forEachIndexed { i, h -> m[h] = i } }
    private var pos = -1
    override val entries: List<ArchiveEntry> = files.map {
        val raw = try { it.fileName } catch (_: Throwable) { "" }
        ArchiveEntry(raw.orEmpty(), it.fullUnpackSize, it.isDirectory, it)
    }

    private object NullOut : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    override fun extract(entry: ArchiveEntry, out: OutputStream) {
        val target = entry.handle as FileHeader
        try {
            if (!isSolid) {
                rar.extractFile(target, out)
                return
            }
            // Solid RAR: every file is compressed against the ones before it,
            // so entries MUST be decoded in archive order. The installer
            // reads game images first and the rest after, which is out of
            // order — decoding a later file alone gave a checksum error or
            // garbage. Re-open when going backwards and decode (and discard)
            // everything in between.
            val idx = indexOf[target] ?: throw InstallException("The RAR file is damaged. Please download it again.")
            if (idx <= pos) {
                try { rar.close() } catch (_: Throwable) { }
                rar = if (password != null) Archive(file, password) else Archive(file)
                headers = rar.fileHeaders
                pos = -1
            }
            for (i in (pos + 1) until idx) {
                val h = headers[i]
                if (!h.isDirectory) rar.extractFile(h, NullOut)
            }
            rar.extractFile(headers[idx], out)
            pos = idx
        } catch (e: com.github.junrar.exception.RarException) {
            throw mapRar(e, passwordGiven = true)
        }
    }

    override fun close() { try { rar.close() } catch (_: Throwable) { } }

    companion object {
        private fun mapRar(e: com.github.junrar.exception.RarException, passwordGiven: Boolean): Throwable {
            val n = e.javaClass.simpleName
            val m = e.message.orEmpty()
            // Wrong password is explicit in junrar; a missing-password error
            // without a password attempt means "ask the user", not "damaged".
            // Same for header-encryption errors (junrar reports those without
            // the word "password"): an encrypted archive prompts, never errors as damaged.
            if (n.contains("WrongPassword", true)) return PasswordNeededException(true)
            if (m.contains("password", true)) return PasswordNeededException(passwordGiven)
            if (n.contains("encrypt", true) || m.contains("encrypt", true)) return PasswordNeededException(passwordGiven)
            return when {
                n.contains("Volume", true) ->
                    InstallException("This is a multi-part RAR. Download every part and extract the first one.", e)
                n.contains("Crc", true) ->
                    InstallException("The RAR file is damaged (checksum mismatch). Please download it again.", e)
                n.contains("V5", true) || m.contains("RAR5", true) || m.contains("v5", true) ->
                    InstallException("This RAR was made with the newer RAR5 format, which this app can't unpack yet. Download the 7z or ZIP version of this mod, or re-pack it as ZIP.", e)
                n.contains("Unsupported", true) ->
                    InstallException("This RAR uses a compression method that can't be unpacked here. Re-pack it as ZIP, or extract it with another app.", e)
                else -> InstallException("The RAR file is damaged or unsupported. Please download it again.", e)
            }
        }

        fun open(file: File, password: String? = null): RarArchiveReader {
            val archive = try {
                if (password != null) Archive(file, password) else Archive(file)
            } catch (e: com.github.junrar.exception.RarException) {
                throw mapRar(e, password != null)
            } catch (e: IOException) {
                throw InstallException("Couldn't read the RAR file.", e)
            }
            try {
                if (archive.isPasswordProtected && password == null) {
                    archive.close()
                    throw PasswordNeededException(false)
                }
                val files = archive.fileHeaders
                return RarArchiveReader(file, password, archive, files)
            } catch (e: PasswordNeededException) {
                throw e
            } catch (e: com.github.junrar.exception.RarException) {
                try { archive.close() } catch (_: Throwable) { }
                throw mapRar(e, password != null)
            } catch (e: Throwable) {
                try { archive.close() } catch (_: Throwable) { }
                throw InstallException("Couldn't read the RAR file.", e)
            }
        }
    }
}

/**
 * 7z reader (LZMA / LZMA2 / PPMd / BZip2 / Deflate / BCJ…) via commons-compress + xz.
 * 7z is usually "solid", so entries must be read in archive order; [extract] walks forward
 * and re-opens the file only if asked for an earlier entry.
 *
 * Password notes (verified against commons-compress 1.28):
 *  - No password + encrypted content → PasswordRequiredException on read.
 *  - Wrong password → NOT a password exception: the AES layer decrypts to
 *    garbage and the decompressor reports corruption (CorruptedInputException).
 *    So when a password WAS supplied, any read-side error is treated as a
 *    wrong password, not a damaged file.
 */
class SevenZArchiveReader private constructor(
    private val file: File,
    private var z: SevenZFile,
    private val passwordGiven: Boolean,
    private val password: String?,
) : ArchiveReader {
    override val isSolid = true
    private val list: List<SevenZArchiveEntry> = z.entries.toList()
    private val indexOf: Map<SevenZArchiveEntry, Int> = HashMap<SevenZArchiveEntry, Int>().also { m -> list.forEachIndexed { i, e -> m[e] = i } }
    override val entries: List<ArchiveEntry> = list.map { ArchiveEntry(it.name.orEmpty(), it.size, it.isDirectory, it) }
    private var pos = -1

    override fun extract(entry: ArchiveEntry, out: OutputStream) {
        val target = entry.handle as SevenZArchiveEntry
        val idx = indexOf[target] ?: throw InstallException("The 7z file is damaged. Please download it again.")
        try {
            if (idx <= pos) { // need an earlier entry: start over
                try { z.close() } catch (_: Throwable) { }
                z = reopenWithPassword()
                pos = -1
            }
            while (pos < idx) {
                z.nextEntry ?: throw InstallException("The 7z file ended unexpectedly. Please download it again.")
                pos++
            }
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = z.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (e: InstallException) {
            throw e
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw PasswordNeededException(passwordGiven)
        } catch (e: IOException) {
            // Output failures must stay IOException so the caller reports "out of space".
            if (passwordGiven && isReadSideError(e)) throw PasswordNeededException(true)
            if (isReadSideError(e)) throw InstallException("The 7z file is damaged or uses an unsupported method (${e.message ?: "read error"}). Re-pack it as ZIP or download it again.", e)
            throw e
        }
    }

    override fun close() { try { z.close() } catch (_: Throwable) { } }

    companion object {
        /** commons-compress signals corrupt/unsupported data with IOException subclasses/messages; writes fail with plain IOException from our stream. */
        private fun isReadSideError(e: IOException): Boolean {
            val n = e.javaClass.name
            if (n.startsWith("org.apache.commons.compress") || n.startsWith("org.tukaani")) return true
            val st = e.stackTrace.firstOrNull()?.className.orEmpty()
            return st.startsWith("org.apache.commons.compress") || st.startsWith("org.tukaani")
        }

        fun open(file: File, password: String? = null): SevenZArchiveReader {
            val builder = SevenZFile.builder().setFile(file)
            if (password != null) builder.setPassword(password.toCharArray())
            val z = try {
                builder.get()
            } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                throw PasswordNeededException(password != null)
            } catch (e: IOException) {
                throw InstallException("This 7z file can't be opened — it may be damaged, incomplete, or use a method that isn't supported (${e.message ?: "read error"}).", e)
            }
            return try {
                SevenZArchiveReader(file, z, password != null, password)
            } catch (e: Throwable) {
                try { z.close() } catch (_: Throwable) { }
                throw InstallException("This 7z file can't be read.", e)
            }
        }
    }

    /** Reopens the file with the same password this reader was opened with (in-memory only, never persisted). */
    private fun reopenWithPassword(): SevenZFile {
        val builder = SevenZFile.builder().setFile(file)
        if (password != null) builder.setPassword(password.toCharArray())
        return builder.get()
    }
}
