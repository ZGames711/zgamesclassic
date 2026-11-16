package com.evolutionofgames.zgamespsx.emulator

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Resolves a game reference to a real readable file path — never copies
 * a multi-GB game. Resolution order (first readable win):
 *  1. readable file:// path
 *  2. MediaStore DISPLAY_NAME + RELATIVE_PATH path
 *  3. ZGames PSX/<fileName>
 *  4. readable plain path
 *  5. the original URI unchanged (caller treats this as unresolvable)
 *
 * Why this exists: the PS2 core opens the disc with fopen(), and a
 * MediaStore content:// URI is not openable that way. The core's own
 * resolver cannot map a MediaStore URI to a file (it only knows
 * DocumentsContract / persisted SAF trees), so it would hand native
 * the content:// string unchanged and boot would fail with a generic
 * error. The path is resolved + validated here, where the real file
 * is known.
 */
object PathResolver {

    /** Public Downloads "ZGames PSX" folder (game images live here). */
    const val MODS_DIR_NAME = "ZGames PSX"
    const val MODS_RELATIVE_PATH = "Download/ZGames PSX/"

    fun isReadableFile(path: String): Boolean {
        return try {
            val f = File(path)
            f.isFile && f.canRead()
        } catch (_: Exception) {
            false
        }
    }

    fun resolveLaunchPath(
        gameUri: String,
        fileName: String,
        modsDir: File,
        mediaStorePath: String?
    ): String {
        // 1. file:// URI to a readable file.
        try {
            if (gameUri.startsWith("file://")) {
                val p = Uri.parse(gameUri).path
                if (!p.isNullOrBlank() && isReadableFile(p)) return p
            }
        } catch (_: Exception) {
        }
        // 2. MediaStore row path (DISPLAY_NAME + RELATIVE_PATH).
        if (!mediaStorePath.isNullOrBlank() && isReadableFile(mediaStorePath)) {
            return mediaStorePath
        }
        // 3. ZGames PSX/<fileName>.
        if (fileName.isNotBlank()) {
            val inMods = File(modsDir, fileName)
            if (inMods.isFile && inMods.canRead()) return inMods.absolutePath
        }
        // 4. Plain path (no scheme).
        if (!gameUri.startsWith("content://") && isReadableFile(gameUri)) {
            return gameUri
        }
        // 5. Give up honestly — the caller blocks with a re-download message.
        return gameUri
    }

    /**
     * The real on-disk path for a MediaStore URI, from its own row:
     * /storage/emulated/0/<RELATIVE_PATH><DISPLAY_NAME>. This is the only
     * reliable mapping on modern Android (the deprecated DATA column is
     * empty for most rows) and it covers subfolders and renames that the
     * ZGames PSX/<fileName> lookup misses. Null when unavailable.
     */
    @Suppress("DEPRECATION")
    fun mediaStorePathFor(appCtx: Context, gameUri: String): String? {
        if (!gameUri.startsWith("content://") || Build.VERSION.SDK_INT < 29) return null
        return try {
            appCtx.contentResolver.query(
                Uri.parse(gameUri),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val name = cursor.getString(0) ?: return null
                val rel = cursor.getString(1) ?: return null
                if (name.isBlank() || rel.isBlank()) return null
                File(Environment.getExternalStorageDirectory(), rel + name).absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Public Downloads/ZGames PSX dir (created on demand). */
    @Suppress("DEPRECATION")
    fun modsDownloadsDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            MODS_DIR_NAME
        ).also { if (!it.exists()) it.mkdirs() }
}
