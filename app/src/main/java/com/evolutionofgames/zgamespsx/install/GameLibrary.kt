package com.evolutionofgames.zgamespsx.install

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.evolutionofgames.zgamespsx.emulator.MemstickStorage
import com.evolutionofgames.zgamespsx.emulator.PathResolver
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One installed game file, as listed in the Downloaded tab and the launcher. */
data class InstalledGame(
    val key: String,
    val uri: String,
    val fileName: String,
    val title: String,
    val gameId: String?,
    val sizeBytes: Long,
    val addedAtMs: Long,
    val coverPath: String?
)

/** Stable key for a game URI (content URIs keep their id; file URIs keep their path). */
fun libraryKey(uriString: String): String {
    return try {
        val u = Uri.parse(uriString)
        when {
            uriString.startsWith("content://") -> "content:${u.lastPathSegment ?: uriString}"
            else -> "file:$uriString"
        }
    } catch (_: Exception) {
        uriString
    }
}

/**
 * The installed-games index: game files in Download/ZGames PSX plus
 * the memstick GAME folder. Titles come from filenames (cleaned); PSP
 * game IDs are probed from the file itself (PARAM.SFO) on refresh — a
 * bounded read of the PVD + SFO area, never a full scan.
 *
 * Refresh is explicit ([refresh]) so the UI controls when the (slightly
 * slow) MediaStore + probe pass runs: on launch, after an install, and
 * on pull-to-refresh — never on every recomposition.
 */
class GameLibrary(context: Context) {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _games = MutableStateFlow<List<InstalledGame>>(emptyList())
    val games: StateFlow<List<InstalledGame>> = _games.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Refreshes the index. Returns the job so callers can await the new list. */
    fun refresh(): kotlinx.coroutines.Job {
        return scope.launch {
            _scanning.value = true
            try {
                val rows = withContext(Dispatchers.IO) { listRows() }
                val old = _games.value.associateBy { it.key }
                val out = ArrayList<InstalledGame>()
                for (r in rows) {
                    val ext = r.name.substringAfterLast('.', "").lowercase()
                    if (ext !in GAME_EXT) continue
                    // A real disc image / PBP is megabytes. Tiny files (e.g. a 560-byte
                    // PARAM.SFO saved by a browser) are not games and used to appear
                    // in My Games as a broken "PS2" entry.
                    if (r.size in 0 until MIN_GAME_BYTES) continue
                    if (r.name.contains("param.sfo", ignoreCase = true) ||
                        r.name.contains("param sfo", ignoreCase = true)) continue
                    val prev = old[r.key]
                    var gameId = prev?.gameId
                    var title = prev?.title
                    if (gameId == null && ext in PROBE_EXT) {
                        gameId = probeGameId(r.uri)
                    }
                    if (title == null) {
                        title = cleanTitle(r.name)
                    }
                    out += InstalledGame(
                        key = r.key,
                        uri = r.uri,
                        fileName = r.name,
                        title = title,
                        gameId = gameId,
                        sizeBytes = r.size,
                        addedAtMs = r.addedMs,
                        coverPath = prev?.coverPath
                    )
                }
                out.sortByDescending { it.addedAtMs }
                // NOTE: no "same disc id + same size" de-duplication any
                // more. A patched TTT ISO has the base game's ID and size,
                // so that rule hid every mod behind the original game.
                // Real duplicates (same file through two paths) are already
                // collapsed by name + size in listRows().
                _games.value = out
            } catch (_: Exception) {
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * Deletes the game file plus any same-named copy of it in the other
     * folders the library reads (ZGames PSX, memstick GAME).
     */
    fun delete(game: InstalledGame): Boolean {
        var removed = false
        try {
            val uri = Uri.parse(game.uri)
            removed = if (game.uri.startsWith("content://")) {
                appCtx.contentResolver.delete(uri, null, null) > 0
            } else {
                uri.path?.let { File(it).delete() } ?: false
            }
        } catch (_: Exception) {
        }
        try {
            val sibling = File(PathResolver.modsDownloadsDir(), game.fileName)
            if (sibling.isFile && sibling.delete()) removed = true
        } catch (_: Exception) {
        }
        try {
            val g = File(File(MemstickStorage.currentMemstickDir(appCtx), "GAME"), game.fileName)
            if (g.isFile && g.delete()) removed = true
        } catch (_: Exception) {
        }
        return removed
    }

    private class Row(val key: String, val uri: String, val name: String, val size: Long, val addedMs: Long)

    private fun listRows(): List<Row> {
        val rows = ArrayList<Row>()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                appCtx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_ADDED
                    ),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(PathResolver.MODS_RELATIVE_PATH),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        val size = cursor.getLong(2)
                        val added = cursor.getLong(3) * 1000L
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id).toString()
                        rows += Row(libraryKey(uri), uri, name, size, added)
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Legacy dir on disk (same folder, direct files).
        try {
            val dir = PathResolver.modsDownloadsDir()
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val uri = Uri.fromFile(f).toString()
                    if (rows.none { it.uri == uri }) {
                        rows += Row(libraryKey(uri), uri, f.name, f.length(), f.lastModified())
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Memstick GAME folder (installed game images unpacked there).
        try {
            val gameDir = File(MemstickStorage.currentMemstickDir(appCtx), "GAME")
            gameDir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val uri = Uri.fromFile(f).toString()
                    if (rows.none { it.uri == uri }) {
                        rows += Row(libraryKey(uri), uri, f.name, f.length(), f.lastModified())
                    }
                } else if (f.isDirectory) {
                    // Homebrew / mod folders: GAME/<folder>/EBOOT.PBP. These
                    // used to install fine and then never appear anywhere.
                    val eboot = f.listFiles()?.firstOrNull { it.isFile && it.name.equals("EBOOT.PBP", true) }
                    if (eboot != null) {
                        val uri = Uri.fromFile(eboot).toString()
                        if (rows.none { it.uri == uri }) {
                            rows += Row(libraryKey(uri), uri, f.name + ".pbp", eboot.length(), eboot.lastModified())
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // The SAME file shows up through several paths: MediaStore
        // (content://) and the direct file path (file://) of the same
        // Download/ZGames PSX folder, and again after the installer
        // copies it into the memstick GAME folder. URIs differ, so the old
        // "uri already seen" check never matched and every finished
        // download appeared twice. Collapse by file name + size (first
        // row wins: MediaStore, then legacy dir, then memstick).
        val seen = HashSet<String>()
        val unique = ArrayList<Row>()
        for (r in rows) {
            val id = r.name.trim().lowercase() + "|" + r.size
            if (seen.add(id)) unique += r
        }
        return unique
    }

    private fun probeGameId(uriString: String): String? {
        return try {
            val pfd = appCtx.contentResolver.openFileDescriptor(Uri.parse(uriString), "r") ?: return null
            try {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                IsoInfo.readInfo(object : IsoInfo.Source {
                    override fun read(pos: Long, buf: ByteArray, len: Int): Int {
                        ch.position(pos)
                        val bb = java.nio.ByteBuffer.wrap(buf, 0, len)
                        var total = 0
                        while (bb.hasRemaining()) { val n = ch.read(bb); if (n <= 0) break; total += n }
                        return total
                    }
                })?.gameId
            } finally {
                try { pfd.close() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Filename -> display title: strip extension, underscores/dots -> spaces, collapse whitespace. */
    private fun cleanTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        return base.replace('_', ' ').replace('.', ' ')
            .replace(Regex("\\s+"), " ").trim()
            .ifEmpty { fileName }
    }

    companion object {
        const val MIN_GAME_BYTES = 1_048_576L // 1 MB
        val GAME_EXT = setOf("iso", "cso", "pbp", "chd", "zso", "bin", "gz", "img", "mdf")
        private val PROBE_EXT = setOf("iso", "cso", "pbp")
        val ARCHIVE_EXT = setOf("zip", "rar", "7z")
        /**
         * ZGamez's GameLibrary AUTO_EXT gate, verbatim:
         * GAME_EXT + ARCHIVE_EXT + setOf("bin", "") — "bin"/blank
         * covers a download the server gave no real name. Only files
         * whose extension is in this set are auto-installed on
         * completion; anything else (APK, PDF, …) stays a plain
         * completed download and is never fed to the installer.
         */
        val AUTO_EXT = GAME_EXT + ARCHIVE_EXT + setOf("bin", "")

        /** ZGamez's onCompleted gate: extension of the finished file name, lowercased, in AUTO_EXT. */
        fun isAutoInstallable(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in AUTO_EXT
    }
}
