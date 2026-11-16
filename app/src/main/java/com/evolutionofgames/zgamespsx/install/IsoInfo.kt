package com.evolutionofgames.zgamespsx.install

import java.io.RandomAccessFile
import java.util.zip.Inflater

/** What the library shows for one PSP game file. */
class GameInfo(val title: String?, val gameId: String?, val icon: ByteArray?)

/**
 * Reads game metadata (title, DISC_ID, ICON0.PNG) from PSP game files:
 * plain ISO, compressed CSO (CISO) and EBOOT.PBP. Pure Kotlin — no Android.
 */
object IsoInfo {
    private const val SECTOR = 2048L
    private const val MAX_ICON = 1024 * 1024L

    /** Random-access source so the Android side can wrap a FileChannel too. */
    interface Source {
        fun read(pos: Long, buf: ByteArray, len: Int): Int
    }

    class FileSource(private val f: RandomAccessFile) : Source {
        override fun read(pos: Long, buf: ByteArray, len: Int): Int {
            f.seek(pos)
            var total = 0
            while (total < len) {
                val n = f.read(buf, total, len - total)
                if (n <= 0) break
                total += n
            }
            return total
        }
    }

    /** Decompresses a CSO (CISO v1) file on the fly so it can be read like an ISO. */
    class CisoSource(private val src: Source) : Source {
        private val blockSize: Int
        private val align: Int
        private val index: IntArray
        val totalBytes: Long
        private var cachedBlock = -1
        private val cache: ByteArray

        init {
            val h = ByteArray(24)
            if (src.read(0, h, 24) < 24 || String(h, 0, 4, Charsets.ISO_8859_1) != "CISO") throw IllegalArgumentException("not CISO")
            totalBytes = le32(h, 8) or (le32(h, 12) shl 32)
            blockSize = le32(h, 16).toInt()
            align = h[21].toInt() and 0xFF
            if (blockSize < 512 || blockSize > 1 shl 20 || totalBytes <= 0 || align > 8) throw IllegalArgumentException("bad CISO")
            val blocks = (totalBytes / blockSize).toInt()
            if (blocks < 1 || blocks > 16_000_000) throw IllegalArgumentException("bad CISO size")
            val raw = ByteArray((blocks + 1) * 4)
            if (src.read(24, raw, raw.size) < raw.size) throw IllegalArgumentException("short CISO index")
            index = IntArray(blocks + 1) { i -> le32(raw, i * 4).toInt() }
            cache = ByteArray(blockSize)
        }

        private fun load(block: Int): Int {
            if (block == cachedBlock) return blockSize
            val e = index[block]
            val plain = e < 0
            val start = (e.toLong() and 0x7FFFFFFFL) shl align
            val end = (index[block + 1].toLong() and 0x7FFFFFFFL) shl align
            val size = (end - start).toInt()
            if (size <= 0 || size > blockSize * 2) { java.util.Arrays.fill(cache, 0); cachedBlock = block; return blockSize }
            val comp = ByteArray(size)
            val got = src.read(start, comp, size)
            if (got <= 0) return 0
            if (plain) {
                java.util.Arrays.fill(cache, 0)
                System.arraycopy(comp, 0, cache, 0, minOf(got, blockSize))
            } else {
                val inf = Inflater(true)
                try {
                    inf.setInput(comp, 0, got)
                    var off = 0
                    while (off < blockSize && !inf.finished()) {
                        val n = inf.inflate(cache, off, blockSize - off)
                        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        off += n
                    }
                    if (off < blockSize) java.util.Arrays.fill(cache, off, blockSize, 0)
                } finally { inf.end() }
            }
            cachedBlock = block
            return blockSize
        }

        @Synchronized
        override fun read(pos: Long, buf: ByteArray, len: Int): Int {
            var done = 0
            var p = pos
            while (done < len && p < totalBytes) {
                val block = (p / blockSize).toInt()
                if (block >= index.size - 1) break
                if (load(block) == 0) break
                val off = (p % blockSize).toInt()
                val n = minOf(len - done, blockSize - off, (totalBytes - p).toInt())
                System.arraycopy(cache, off, buf, done, n)
                done += n; p += n
            }
            return done
        }
    }

    /** Metadata for an ISO, CSO or PBP, or null when nothing readable was found. */
    fun readInfo(src: Source): GameInfo? = try {
        val magic = ByteArray(4)
        if (src.read(0, magic, 4) < 4) null
        else if (String(magic, Charsets.ISO_8859_1) == "CISO") readIso(CisoSource(src))
        else if (magic[0].toInt() == 0 && magic[1] == 'P'.code.toByte() && magic[2] == 'B'.code.toByte() && magic[3] == 'P'.code.toByte()) readPbp(src)
        else readIso(src)
    } catch (_: Throwable) { null }

    fun readDiscId(src: Source): String? = readInfo(src)?.gameId

    private fun readPbp(src: Source): GameInfo? {
        val h = ByteArray(40)
        if (src.read(0, h, 40) < 40) return null
        val sfoOff = le32(h, 8); val iconOff = le32(h, 12); val icon1Off = le32(h, 16)
        var title: String? = null; var id: String? = null; var icon: ByteArray? = null
        val sfoLen = iconOff - sfoOff
        if (sfoOff in 40..(1L shl 31) && sfoLen in 20..(256 * 1024)) {
            val d = ByteArray(sfoLen.toInt())
            if (src.read(sfoOff, d, d.size) == d.size) { val m = parseSfo(d); title = m["TITLE"]; id = m["DISC_ID"]?.uppercase()?.takeIf { ArchivePlanner.isGameId(it) } }
        }
        val iconLen = icon1Off - iconOff
        if (iconOff > 0 && iconLen in 8..MAX_ICON) {
            val d = ByteArray(iconLen.toInt())
            if (src.read(iconOff, d, d.size) == d.size && isPng(d)) icon = d
        }
        return if (title == null && id == null && icon == null) null else GameInfo(title, id, icon)
    }

    private fun readIso(src: Source): GameInfo? {
        val pvd = ByteArray(SECTOR.toInt())
        if (src.read(16 * SECTOR, pvd, pvd.size) < 190) return null
        if (pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.ISO_8859_1) != "CD001") return null
        val psp = findEntry(src, le32(pvd, 158), le32(pvd, 166), "PSP_GAME", dir = true) ?: return null
        var title: String? = null; var id: String? = null; var icon: ByteArray? = null
        findEntry(src, psp.first, psp.second, "PARAM.SFO", dir = false)?.let { sfo ->
            val size = minOf(sfo.second, 256 * 1024L).toInt()
            if (size >= 20) {
                val data = ByteArray(size)
                if (src.read(sfo.first * SECTOR, data, size) == size) {
                    val m = parseSfo(data)
                    title = m["TITLE"]
                    id = m["DISC_ID"]?.uppercase()?.takeIf { ArchivePlanner.isGameId(it) }
                }
            }
        }
        findEntry(src, psp.first, psp.second, "ICON0.PNG", dir = false)?.let { ic ->
            if (ic.second in 8..MAX_ICON) {
                val d = ByteArray(ic.second.toInt())
                if (src.read(ic.first * SECTOR, d, d.size) == d.size && isPng(d)) icon = d
            }
        }
        return if (title == null && id == null && icon == null) null else GameInfo(title, id, icon)
    }

    private fun isPng(d: ByteArray) = d.size > 8 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() && d[3] == 'G'.code.toByte()

    private fun findEntry(src: Source, lba: Long, len: Long, name: String, dir: Boolean): Pair<Long, Long>? {
        if (len <= 0 || len > 4 * 1024 * 1024) return null
        val buf = ByteArray(len.toInt())
        val got = src.read(lba * SECTOR, buf, buf.size)
        var off = 0
        while (off < got) {
            val recLen = buf[off].toInt() and 0xFF
            if (recLen == 0) { // padding to next sector
                off = ((off / SECTOR.toInt()) + 1) * SECTOR.toInt()
                continue
            }
            if (off + recLen > got || recLen < 34) break
            val nameLen = buf[off + 32].toInt() and 0xFF
            if (off + 33 + nameLen > got) break
            val isDir = (buf[off + 25].toInt() and 2) != 0
            val n = String(buf, off + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';')
            if (isDir == dir && n.equals(name, true)) return Pair(le32(buf, off + 2), le32(buf, off + 10))
            off += recLen
        }
        return null
    }

    /** All string entries of a PARAM.SFO (TITLE, DISC_ID, …). */
    fun parseSfo(d: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        if (d.size < 20 || d[0].toInt() != 0 || d[1] != 'P'.code.toByte() || d[2] != 'S'.code.toByte() || d[3] != 'F'.code.toByte()) return out
        val keyTable = le32(d, 8).toInt()
        val dataTable = le32(d, 12).toInt()
        val count = le32(d, 16).toInt()
        if (count < 0 || count > 512) return out
        for (i in 0 until count) {
            val e = 20 + i * 16
            if (e + 16 > d.size) break
            val keyOff = (d[e].toInt() and 0xFF) or ((d[e + 1].toInt() and 0xFF) shl 8)
            val fmt = (d[e + 2].toInt() and 0xFF) or ((d[e + 3].toInt() and 0xFF) shl 8)
            val dataLen = le32(d, e + 4).toInt()
            val dataOff = le32(d, e + 12).toInt()
            if (fmt != 0x0004 && fmt != 0x0204) continue // strings only
            val ks = keyTable + keyOff
            if (ks < 0 || ks >= d.size) continue
            var ke = ks
            while (ke < d.size && d[ke].toInt() != 0) ke++
            val key = String(d, ks, ke - ks, Charsets.US_ASCII)
            val ds = dataTable + dataOff
            if (ds < 0 || ds >= d.size) continue
            val max = minOf(d.size, ds + maxOf(dataLen, 0))
            var de = ds
            while (de < max && d[de].toInt() != 0) de++
            out[key] = String(d, ds, de - ds, Charsets.UTF_8).trim()
        }
        return out
    }

    fun parseSfoDiscId(d: ByteArray): String? =
        parseSfo(d)["DISC_ID"]?.uppercase()?.takeIf { ArchivePlanner.isGameId(it) }

    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))
}
