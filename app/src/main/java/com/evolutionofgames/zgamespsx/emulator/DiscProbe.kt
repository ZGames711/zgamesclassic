package com.evolutionofgames.zgamespsx.emulator

import java.io.File
import java.io.RandomAccessFile

/**
 * ISO9660 disc probing for the fresh engine (PS2 vs PSP detection,
 * SYSTEM.CNF / BOOT2 pre-validation, truncation check, BIN/CUE handling).
 *
 * ISO9660 facts used here (do not "fix" them):
 * - The Primary Volume Descriptor lives at sector 16 and starts with
 *   type byte 1 + "CD001".
 * - Volume space size is a little-endian u32 at PVD offset 80.
 * - The root directory record starts at PVD offset 156: [156] = record
 *   length, [157] = extended attribute length, [158..161] = extent
 *   location (LE), [166..169] = data length (LE). Reading the extent at
 *   156 instead of 158 yields garbage for every real disc (a shipped
 *   bug in an earlier build that blocked every game).
 * - Directory records: [0] = length, [2..5] = extent LE, [10..13] = data
 *   length LE, [25] = flags (bit 2 = directory), [32] = name length,
 *   [33..] = name (may carry a ";1" version suffix; names compare
 *   case-insensitively, version suffix stripped).
 *
 * BIN files have 2352-byte sectors with a 24-byte sync/header before
 * each 2048-byte user-data area, so the PVD sits at 16*2352+24 and the
 * root extent math uses sectorSize 2352 with headerSkip 24. A raw
 * 2048-sector "BIN" is just an ISO and is probed as one.
 */
object DiscProbe {

    /** File extensions the library treats as games (case-insensitive). */
    val GAME_EXTENSIONS = setOf("iso", "bin", "cue", "chd", "cso", "gz", "img", "mdf")

    /** Which console a game file belongs to. PSP = embedded PSP core, PS2 = embedded PS2 core. */
    enum class Console { PSP, PS2 }

    sealed class DiscCheck {
        /** A disc the core can be expected to boot (PS2: SYSTEM.CNF present). */
        data class Valid(val sectorSize: Int) : DiscCheck()
        /** Provably NOT a bootable disc of the expected kind. */
        data class NotDisc(val reason: String) : DiscCheck()
        /** Cannot be verified (CHD/GZ/CSO): proceed, never block. */
        object Unknown : DiscCheck()
    }

    /**
     * PS2 disc check for one game file. Never copies the file, never
     * guesses: an unresolvable or provably-bad game blocks with a plain
     * reason instead of a black screen.
     */
    fun checkPs2Disc(file: File, ext: String): DiscCheck {
        return try {
            when (ext.lowercase()) {
                "iso", "img" -> {
                    when (val r = checkIsoFile(file, 2048, 0)) {
                        is IsoCheckResult.Ok -> DiscCheck.Valid(2048)
                        is IsoCheckResult.NotIso -> DiscCheck.NotDisc(r.reason)
                        is IsoCheckResult.NoSystemCnf -> DiscCheck.NotDisc("isn't a valid game disc image")
                    }
                }
                "bin" -> checkBinFile(file)
                // CHD / GZ / CSO cannot be probed without a decompressor here.
                else -> DiscCheck.Unknown
            }
        } catch (_: Exception) {
            DiscCheck.Unknown
        }
    }

    private sealed class IsoCheckResult {
        object Ok : IsoCheckResult()
        data class NotIso(val reason: String) : IsoCheckResult()
        object NoSystemCnf : IsoCheckResult()
    }

    private fun checkIsoFile(file: File, sectorSize: Int, headerSkip: Int): IsoCheckResult {
        if (!file.isFile) return IsoCheckResult.NotIso("couldn't be found")
        var sawPvd = false
        var sawSystemCnf = false
        walkRootDir(file, sectorSize, headerSkip) { name, _, _, isDir ->
            sawPvd = true
            if (!isDir && name.equals("SYSTEM.CNF", ignoreCase = true)) sawSystemCnf = true
            false
        }
        if (!sawPvd) return IsoCheckResult.NotIso("isn't a disc image")
        if (!sawSystemCnf) return IsoCheckResult.NoSystemCnf
        return IsoCheckResult.Ok
    }

    /** BIN: try Mode2/2352 (headerSkip 24) first, then raw 2048. */
    private fun checkBinFile(file: File): DiscCheck {
        return try {
            // Mode2/2352: PVD at 16*2352+24+1 ("CD001" starts at +1 past type byte).
            if (hasPvdAt(file, 16L * 2352L + 24L)) {
                when (val r = checkIsoFile(file, 2352, 24)) {
                    is IsoCheckResult.Ok -> return DiscCheck.Valid(2352)
                    is IsoCheckResult.NotIso -> return DiscCheck.NotDisc(r.reason)
                    is IsoCheckResult.NoSystemCnf -> return DiscCheck.NotDisc("isn't a valid game disc image")
                }
            }
            // Raw 2048: a "BIN" that is really an ISO dump.
            if (hasPvdAt(file, 16L * 2048L)) {
                when (val r = checkIsoFile(file, 2048, 0)) {
                    is IsoCheckResult.Ok -> return DiscCheck.Valid(2048)
                    is IsoCheckResult.NotIso -> return DiscCheck.NotDisc(r.reason)
                    is IsoCheckResult.NoSystemCnf -> return DiscCheck.NotDisc("isn't a valid game disc image")
                }
            }
            DiscCheck.NotDisc("isn't a readable game disc image")
        } catch (_: Exception) {
            DiscCheck.Unknown
        }
    }

    private fun hasPvdAt(file: File, pvdOffset: Long): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < pvdOffset + 6) return false
                raf.seek(pvdOffset)
                val h = ByteArray(6)
                if (raf.read(h) < 6) return false
                h[0] == 1.toByte() && String(h, 1, 5, Charsets.ISO_8859_1) == "CD001"
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Walks the root directory, calling [onRecord] for each entry.
     * Returning true from [onRecord] stops the walk. Returns false when
     * no PVD was found (not a disc image at all).
     *
     * Record validation (from the proven walker): a zero-length record
     * skips to the next sector (padding); a record that overruns the
     * buffer, is shorter than 34 bytes, or whose name overruns the
     * buffer ends the walk. Names are version-suffix-stripped.
     */
    fun walkRootDir(
        file: File,
        sectorSize: Int,
        headerSkip: Int,
        onRecord: (name: String, extent: Long, size: Long, isDir: Boolean) -> Boolean
    ): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val pvdPos = 16L * sectorSize + headerSkip
                if (raf.length() < pvdPos + 2048) return false
                raf.seek(pvdPos)
                val pvd = ByteArray(2048)
                if (raf.read(pvd) < 190) return false
                if (pvd[0] != 1.toByte() || String(pvd, 1, 5, Charsets.ISO_8859_1) != "CD001") return false
                val rootLba = le32(pvd, 158)
                val rootLen = le32(pvd, 166)
                if (rootLen <= 0 || rootLen > 4 * 1024 * 1024) return false
                val buf = ByteArray(rootLen.toInt())
                // NOTE: for 2048-sector images headerSkip is 0, so the root
                // is read as one block at rootLba*2048. For 2352-sector
                // images each sector carries its own 24-byte header, so the
                // walk below reads sector-by-sector instead of one block.
                if (headerSkip == 0) {
                    raf.seek(rootLba * sectorSize)
                    val got = readFully(raf, buf)
                    var off = 0
                    while (off < got) {
                        val recLen = buf[off].toInt() and 0xFF
                        if (recLen == 0) {
                            off = ((off / sectorSize) + 1) * sectorSize
                            continue
                        }
                        if (off + recLen > got || recLen < 34) break
                        val nameLen = buf[off + 32].toInt() and 0xFF
                        if (off + 33 + nameLen > got) break
                        val isDir = (buf[off + 25].toInt() and 2) != 0
                        val n = String(buf, off + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';')
                        if (onRecord(n, le32(buf, off + 2), le32(buf, off + 10), isDir)) return true
                        off += recLen
                    }
                } else {
                    // 2352-sector image: walk sector by sector, skipping each
                    // sector's 24-byte header to reach its 2048 user bytes.
                    var remaining = rootLen
                    var lba = rootLba
                    val sector = ByteArray(2048)
                    while (remaining > 0) {
                        raf.seek(lba * sectorSize + headerSkip)
                        val got = readFully(raf, sector)
                        if (got <= 0) break
                        var off = 0
                        while (off < got) {
                            val recLen = sector[off].toInt() and 0xFF
                            if (recLen == 0) break
                            if (off + recLen > got || recLen < 34) break
                            val nameLen = sector[off + 32].toInt() and 0xFF
                            if (off + 33 + nameLen > got) break
                            val isDir = (sector[off + 25].toInt() and 2) != 0
                            val n = String(sector, off + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';')
                            if (onRecord(n, le32(sector, off + 2), le32(sector, off + 10), isDir)) return true
                            off += recLen
                        }
                        remaining -= 2048
                        lba++
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pre-launch boot-target check (PS2 ISO/IMG/BIN only). Returns null
     * when the game may launch, or a plain block reason when it must NOT
     * launch. Blocks ONLY on a provably truncated disc or a provably
     * missing root-level BOOT2 target. Unparseable SYSTEM.CNF and subdir
     * BOOT2 targets proceed — blocking on those would false-block real
     * games.
     */
    fun checkPs2BootTarget(file: File, sectorSize: Int): String? {
        return try {
            if (!file.isFile) return null
            val pvd = readPvd(file, sectorSize) ?: return null
            val volBlocks = le32(pvd, 80)
            // Truncation: only when the volume size is sane. A zero or
            // absurd block count means an unreadable PVD, not a short file.
            if (volBlocks in 1 until 8L * 1024 * 1024) {
                val expected = volBlocks * sectorSize
                if (file.length() < expected) return "looks incomplete (the download may have been cut short)"
            }
            var systemCnf: String? = null
            walkRootDir(file, sectorSize, if (sectorSize == 2352) 24 else 0) { name, extent, size, isDir ->
                if (!isDir && name.equals("SYSTEM.CNF", ignoreCase = true)) {
                    if (size in 1..2048) {
                        systemCnf = readUserData(file, sectorSize, extent, size)
                    }
                    true
                } else false
            }
            val cnf = systemCnf ?: return null
            val boot = parseBoot2(cnf) ?: return null
            // Strip device prefix (cdrom0:) and version suffix (;1).
            val target = boot.substringBefore(';').trim()
                .substringAfter(':').trim()
                .trimStart('\\', '/')
            if (target.isEmpty()) return null
            // Subdir targets can't be checked against the root listing.
            if (target.contains('\\') || target.contains('/')) return null
            var found = false
            walkRootDir(file, sectorSize, if (sectorSize == 2352) 24 else 0) { name, _, _, isDir ->
                if (!isDir && name.equals(target, ignoreCase = true)) {
                    found = true
                    true
                } else false
            }
            if (!found) "its startup file ($target) is missing from the disc" else null
        } catch (_: Exception) {
            null
        }
    }

    /** Parse "BOOT2 = cdrom0:\path\file" from SYSTEM.CNF text. */
    fun parseBoot2(systemCnf: String): String? {
        for (rawLine in systemCnf.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("BOOT2", ignoreCase = true)) {
                val value = line.substringAfter('=', "").trim().trim('"')
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    /** The .cue sidecar for a .bin file (never overwrites an existing one). */
    fun cueFileFor(binFile: File): File =
        File(binFile.parentFile, binFile.nameWithoutExtension + ".cue")

    /**
     * Ensure a .cue sidecar exists next to a .bin file. An existing cue
     * is never touched. A synthesized cue assumes a single-track disc:
     * MODE2/2352 for 2352-byte sectors, MODE1/2048 for 2048-byte ones.
     * Returns false when the cue could not be written or the sector
     * size is not a known disc layout.
     */
    fun ensureCue(binFile: File, sectorSize: Int): Boolean {
        return try {
            if (!binFile.isFile) return false
            val cueFile = cueFileFor(binFile)
            if (cueFile.isFile) return true
            val mode = when (sectorSize) {
                2352 -> "MODE2/2352"
                2048 -> "MODE1/2048"
                else -> return false
            }
            cueFile.writeText(
                "FILE \"${binFile.name}\" BINARY\r\n" +
                    "  TRACK 1 $mode\r\n" +
                    "    INDEX 1 00:00:00\r\n"
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Console routing when no readable file is available (extension +
     * title only). Used when a game URI cannot be resolved to a real
     * file: launch still blocks with a re-download message, but tab
     * badges and Play routing can still pick a side.
     */
    fun consoleForTitle(title: String, ext: String): Console {
        return when (ext.lowercase()) {
            "pbp", "zso" -> Console.PSP
            "gz", "mdf" -> Console.PS2
            "bin", "cue" -> binFallback(title)
            "img" -> Console.PS2
            else -> titleFallback(title)
        }
    }

    /**
     * Tells PSP games apart from PS2 games by READING the file, never by
     * filename alone. PSP ISO: root contains a PSP_GAME directory.
     * PS2 ISO: root contains a SYSTEM.CNF file. PBP/ZSO are PSP-only,
     * GZ/MDF are PS2-era formats the PSP core cannot play, BIN/CUE is
     * PS2 unless a PSP title marker says otherwise, IMG probes like ISO
     * and is PS2 when inconclusive (PSP never uses IMG). CHD and
     * unreadable files fall through to the title fallback (PSP default,
     * matching long-standing behavior).
     */
    fun probeConsole(file: File, title: String): Console {
        val ext = file.extension.lowercase().ifBlank { "" }
        when (ext) {
            "pbp", "zso" -> return Console.PSP
            "gz", "mdf" -> return Console.PS2
            "bin", "cue" -> return binFallback(title)
            "img" -> {
                probeIsoRoot(file, 2048, 0)?.let { return it }
                return Console.PS2
            }
            "iso", "cso" -> {
                if (ext == "cso") {
                    probeCsoRoot(file)?.let { return it }
                    return titleFallback(title) // compressed PSP format; PSP unless the title clearly says PS2
                }
                probeIsoRoot(file, 2048, 0)?.let { return it }
                return titleFallback(title)
            }
            else -> return titleFallback(title)
        }
    }

    /** Returns PSP/PS2 when the ISO structure proves it, null when inconclusive. */
    private fun probeIsoRoot(file: File, sectorSize: Int, headerSkip: Int): Console? {
        var hasSystemCnf = false
        var foundPspGame = false
        val sawPvd = walkRootDir(file, sectorSize, headerSkip) { name, _, _, isDir ->
            if (isDir && name.equals("PSP_GAME", ignoreCase = true)) foundPspGame = true
            // UMD_DATA.BIN sits in the root of every PSP UMD image.
            if (!isDir && name.equals("UMD_DATA.BIN", ignoreCase = true)) foundPspGame = true
            if (!isDir && name.equals("SYSTEM.CNF", ignoreCase = true)) hasSystemCnf = true
            false
        }
        if (!sawPvd) return null
        if (foundPspGame) return Console.PSP
        return if (hasSystemCnf) Console.PS2 else null
    }

    /**
     * Title markers are only the LAST resort (file structure and the
     * catalogue platform win). "Budokai Tenkaichi" alone is NOT a PS2 marker:
     * Tenkaichi Tag Team (TTT) is a PSP game whose mod titles often say
     * "Budokai Tenkaichi TTT", and that used to open in the PS2 emulator.
     */
    private val pspTitleRegex = Regex(
        "\\b(ttt|tag team|tenkaichi tag|shin budokai|psp|ppsspp|ultimate mod psp|ultimate ppsspp)\\b"
    )
    private val ps2TitleRegex = Regex(
        "\\b(bt2|bt3|budokai tenkaichi [23]|sparking( neo| meteor)?|ps2|playstation 2|pcsx2|aethersx2)\\b"
    )

    private fun titleFallback(title: String): Console {
        val t = title.lowercase()
        if (pspTitleRegex.containsMatchIn(t)) return Console.PSP
        if (ps2TitleRegex.containsMatchIn(t)) return Console.PS2
        return Console.PSP
    }

    /**
     * Console PROVEN by disc structure (PSP_GAME dir => PSP, SYSTEM.CNF =>
     * PS2) for ISO/IMG; null when the file can't prove it (CSO, unreadable).
     * [extOverride] lets a /proc/self/fd path (no extension) be probed.
     */
    fun probeProven(file: File, extOverride: String? = null): Console? {
        val ext = (extOverride ?: file.extension).lowercase()
        return when (ext) {
            "pbp", "zso" -> Console.PSP
            "iso", "img" -> probeIsoRoot(file, 2048, 0) ?: scanForConsole(file)
            "bin" -> probeIsoRoot(file, 2352, 24) ?: probeIsoRoot(file, 2048, 0) ?: scanForConsole(file)
            "cso" -> probeCsoRoot(file)
            else -> null
        }
    }

    /**
     * Last structural resort when the directory walk proves nothing (odd
     * mastering, modded ISOs): look for the PSP markers (PSP_GAME folder name,
     * UMD_DATA.BIN) or PS2's SYSTEM.CNF in the first few MB, where the root
     * directory always lives. A PSP image never contains SYSTEM.CNF and a PS2
     * image never contains PSP_GAME, so this does not guess from the name.
     */
    fun scanForConsole(file: File): Console? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = minOf(raf.length(), 3L * 1024 * 1024).toInt()
                if (len < 65536) return null
                val buf = ByteArray(len)
                val got = readFully(raf, buf)
                if (got < 65536) return null
                val s = String(buf, 0, got, Charsets.ISO_8859_1)
                when {
                    s.contains("PSP_GAME") || s.contains("UMD_DATA.BIN") -> Console.PSP
                    s.contains("SYSTEM.CNF") -> Console.PS2
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Strong PSP words in a title (Tag Team / TTT / Shin Budokai...). Used before the catalogue's platform. */
    fun titleSaysPsp(title: String): Boolean = pspTitleRegex.containsMatchIn(title.lowercase())

    /**
     * Proves the console of a CSO (zlib-compressed ISO) by decompressing
     * just the sectors it needs: the PVD (sector 16) and the root
     * directory. PSP_GAME dir => PSP, SYSTEM.CNF => PS2. Returns null when
     * the file isn't a readable CSO or the root proves nothing, so the
     * caller falls through to the catalogue platform / title hint.
     *
     * CSO layout: "CISO", u32 header size, u64 total bytes, u32 block
     * size, u8 version, u8 index shift (align), 2 reserved bytes, then
     * (blocks + 1) u32 index entries. Bit 31 of an entry = block stored
     * uncompressed; the file offset is (entry & 0x7FFFFFFF) << align. A
     * block runs from its offset to the next entry's offset and, when
     * compressed, is a RAW deflate stream (no zlib header).
     */
    private fun probeCsoRoot(file: File): Console? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(24)
                if (raf.length() < 24) return null
                raf.seek(0)
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.ISO_8859_1) != "CISO") return null
                val total = le32(head, 8) or (le32(head, 12) shl 32)
                val blockSize = le32(head, 16).toInt()
                val align = head[21].toInt() and 0xFF
                if (blockSize < 2048 || blockSize > 1 shl 20 || total <= 0) return null
                val blocks = ((total + blockSize - 1) / blockSize).toInt()
                val indexAt = 24L

                fun entry(i: Int): Long {
                    raf.seek(indexAt + 4L * i)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    return le32(b, 0)
                }

                // Returns the 2048 user bytes of disc sector [lba], or null.
                fun sector(lba: Long): ByteArray? {
                    val byteOff = lba * 2048L
                    if (byteOff >= total) return null
                    val blk = (byteOff / blockSize).toInt()
                    if (blk >= blocks) return null
                    val e0 = entry(blk)
                    val e1 = entry(blk + 1)
                    val plain = (e0 and 0x80000000L) != 0L
                    val start = (e0 and 0x7FFFFFFFL) shl align
                    val end = (e1 and 0x7FFFFFFFL) shl align
                    val len = (end - start).toInt()
                    if (len <= 0 || len > blockSize * 2 || start + len > raf.length()) return null
                    val raw = ByteArray(len)
                    raf.seek(start)
                    raf.readFully(raw)
                    val data: ByteArray = if (plain) raw else {
                        val inf = java.util.zip.Inflater(true)
                        try {
                            inf.setInput(raw)
                            val out = ByteArray(blockSize)
                            var n = 0
                            while (n < blockSize && !inf.finished()) {
                                val r = inf.inflate(out, n, blockSize - n)
                                if (r <= 0 && (inf.needsInput() || inf.needsDictionary())) break
                                n += r
                            }
                            if (n < blockSize) return null
                            out
                        } finally {
                            inf.end()
                        }
                    }
                    val within = (byteOff % blockSize).toInt()
                    if (within + 2048 > data.size) return null
                    return data.copyOfRange(within, within + 2048)
                }

                val pvd = sector(16) ?: return null
                if (pvd[0] != 1.toByte() || String(pvd, 1, 5, Charsets.ISO_8859_1) != "CD001") return null
                val rootLba = le32(pvd, 158)
                val rootLen = le32(pvd, 166)
                if (rootLen <= 0 || rootLen > 256 * 1024) return null
                var hasSystemCnf = false
                var hasPspGame = false
                var remaining = rootLen
                var lba = rootLba
                while (remaining > 0) {
                    val sec = sector(lba) ?: break
                    var off = 0
                    while (off < sec.size) {
                        val recLen = sec[off].toInt() and 0xFF
                        if (recLen == 0) break
                        if (off + recLen > sec.size || recLen < 34) break
                        val nameLen = sec[off + 32].toInt() and 0xFF
                        if (off + 33 + nameLen > sec.size) break
                        val isDir = (sec[off + 25].toInt() and 2) != 0
                        val n = String(sec, off + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';')
                        if (isDir && n.equals("PSP_GAME", ignoreCase = true)) hasPspGame = true
                        if (!isDir && n.equals("SYSTEM.CNF", ignoreCase = true)) hasSystemCnf = true
                        off += recLen
                    }
                    remaining -= 2048
                    lba++
                }
                when {
                    hasPspGame -> Console.PSP
                    hasSystemCnf -> Console.PS2
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun binFallback(title: String): Console {
        return if (pspTitleRegex.containsMatchIn(title.lowercase())) Console.PSP else Console.PS2
    }

    private fun readPvd(file: File, sectorSize: Int): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val pvdPos = 16L * sectorSize + if (sectorSize == 2352) 24L else 0L
                if (raf.length() < pvdPos + 2048) return null
                raf.seek(pvdPos)
                val pvd = ByteArray(2048)
                if (raf.read(pvd) < 190) return null
                if (pvd[0] != 1.toByte() || String(pvd, 1, 5, Charsets.ISO_8859_1) != "CD001") return null
                pvd
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Reads [size] user-data bytes at disc extent [lba] (sectorSize-aware). */
    private fun readUserData(file: File, sectorSize: Int, lba: Long, size: Long): String? {
        return try {
            if (size <= 0 || size > 2048) return null
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(size.toInt())
                if (sectorSize == 2048) {
                    raf.seek(lba * 2048)
                    if (readFully(raf, buf) < size) return null
                } else {
                    // 2352-sector image: SYSTEM.CNF fits in one user area.
                    raf.seek(lba * 2352 + 24)
                    if (readFully(raf, buf) < size) return null
                }
                buf.toString(Charsets.ISO_8859_1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Int {
        var done = 0
        while (done < buf.size) {
            val n = raf.read(buf, done, buf.size - done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
}
