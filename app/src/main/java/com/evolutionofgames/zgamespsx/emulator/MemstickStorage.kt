package com.evolutionofgames.zgamespsx.emulator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The PSP memory-stick ("PSP") folder, shared by the embedded PSP core
 * and the mod installer.
 *
 * The folder lives in MAIN storage (/sdcard/PSP) — the folder users can
 * see, browse, and keep — not the app-private files dir (invisible,
 * deleted on uninstall). On Android 11+ that needs All Files Access
 * (MANAGE_EXTERNAL_STORAGE): one system toggle, no SAF folder picker.
 *
 * When All Files Access is not granted (denied, or Android < 11 where
 * the toggle doesn't exist), everything falls back to the app-private
 * folder so Play and installs keep working.
 *
 * The PSP core reads <filesDir>/memstick_dir.txt on startup: if it
 * points at a real directory, the core uses it and never shows its
 * folder-setup screen. The core activity runs in this same app/process,
 * so its getFilesDir() is ours.
 */
object MemstickStorage {

    /** Main-storage PSP folder: /sdcard/PSP — visible to the user. */
    fun mainPspDir(): File = File(Environment.getExternalStorageDirectory(), "PSP")

    /** App-private fallback: this app's external files dir / PSP. */
    fun appPrivatePspDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "PSP")

    /** True when the app may freely read/write main storage (Android 11+ toggle). */
    fun isMainStorageGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
    }

    private fun memstickFile(context: Context) = File(context.filesDir, "memstick_dir.txt")

    /** The memstick dir the core will actually use, from memstick_dir.txt when set. */
    fun currentMemstickDir(context: Context): File {
        val txt = memstickFile(context)
        if (txt.isFile) {
            try {
                val saved = txt.readText().trim()
                if (saved.isNotEmpty()) {
                    val dir = File(saved)
                    // Stale main-storage pointer after the grant was
                    // revoked: main storage is unusable without it, so
                    // fall through to the app-private fallback instead
                    // of pointing the core at an unwritable folder.
                    if (!isMainStorageGranted(context) &&
                        dir.absolutePath == mainPspDir().absolutePath
                    ) {
                        return appPrivatePspDir(context)
                    }
                    if (dir.isDirectory || dir.mkdirs()) return dir
                }
            } catch (_: Exception) {
            }
        }
        return if (isMainStorageGranted(context)) mainPspDir() else appPrivatePspDir(context)
    }

    private fun ensureSubdirs(pspDir: File) {
        try {
            pspDir.mkdirs()
            File(pspDir, "SAVEDATA").mkdirs()
            File(pspDir, "SYSTEM").mkdirs()
            File(pspDir, "GAME").mkdirs()
            File(pspDir, "TEXTURES").mkdirs()
        } catch (_: Exception) {
        }
    }

    /** Best-effort copy of a folder's contents (recursion, no overwrite of existing files). */
    private fun copyTree(src: File, dst: File) {
        try {
            if (!src.isDirectory) return
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                val target = File(dst, child.name)
                try {
                    if (child.isDirectory) {
                        copyTree(child, target)
                    } else if (!target.exists()) {
                        child.copyTo(target)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Makes sure a memstick dir exists and memstick_dir.txt points at it.
     * Returns the dir the core will use.
     *
     * Migration: if All Files Access is now granted but memstick_dir.txt
     * still points at the old app-private folder, saves are copied to
     * /sdcard/PSP/SAVEDATA (old folder left untouched, never deleted) and
     * the pointer is rewritten. A user's own folder choice is only replaced
     * by this migration when the old pointer is exactly our app-private dir;
     * any other custom folder is left alone.
     */
    fun ensureConfigured(context: Context): File {
        val granted = isMainStorageGranted(context)
        val txt = memstickFile(context)
        val oldTarget: File? = try {
            if (txt.isFile) txt.readText().trim().takeIf { it.isNotEmpty() }?.let { File(it) } else null
        } catch (_: Exception) {
            null
        }
        val appPrivate = appPrivatePspDir(context)
        val target = when {
            granted -> mainPspDir()
            // A genuinely custom folder (not ours) is preserved; a stale
            // main-storage pointer after the grant was revoked falls back
            // to app-private instead of an unwritable folder.
            oldTarget != null && oldTarget.absolutePath != mainPspDir().absolutePath -> oldTarget
            else -> appPrivate
        }
        ensureSubdirs(target)
        if (granted && oldTarget != null && oldTarget.absolutePath == appPrivate.absolutePath &&
            target.absolutePath != oldTarget.absolutePath
        ) {
            // Migrate saves from the old app-private folder to main storage.
            copyTree(File(oldTarget, "SAVEDATA"), File(target, "SAVEDATA"))
        }
        try {
            // No trailing newline: the core reads the whole file as the path.
            txt.writeText(target.absolutePath)
        } catch (_: Exception) {
        }
        return target
    }

    /**
     * Pure in-place patch of a ppsspp.ini's text: sets
     * "ReplaceTextures = True" under [Graphics], preserving every other
     * key/section. Returns the new text (a minimal ini when [existing] is
     * null/blank, i.e. the core has never run).
     */
    fun patchIniForTextureReplacement(existing: String?): String {
        val lines = if (existing.isNullOrBlank()) {
            mutableListOf()
        } else {
            existing.lines().toMutableList()
        }
        var inGraphics = false
        var replaced = false
        var graphicsIdx = -1
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inGraphics = trimmed.equals("[Graphics]", ignoreCase = true)
                if (inGraphics) graphicsIdx = i
                continue
            }
            if (inGraphics && trimmed.startsWith("ReplaceTextures", ignoreCase = true) &&
                trimmed.substringAfter("ReplaceTextures", "").trimStart().startsWith("=")
            ) {
                lines[i] = "ReplaceTextures = True"
                replaced = true
            }
        }
        if (!replaced) {
            if (graphicsIdx >= 0) {
                lines.add(graphicsIdx + 1, "ReplaceTextures = True")
            } else {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[Graphics]")
                lines.add("ReplaceTextures = True")
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    // Two bitmask settings exist in this core (found in its config table):
    //  - ForceEnableHLEFlags: "run OUR HLE for these libraries, even where the real
    //    firmware module is the default". sceMpeg/sceMp4/sceAtrac default to the real
    //    firmware module in this build, which is what shows "sceMpeg needs a firmware
    //    installed to run without HLE" and leaves games with unresolved imports (black
    //    screen at the first video).
    //  - DisableHLEFlags: "run the REAL firmware module instead of our HLE".
    // The exact bit of each module is not guessed: every bit is forced, so whichever
    // bit sceMpeg/sceMp4/sceAtrac use is covered (bits with no module are ignored).
    // Only when no firmware is installed; with firmware the forcing is cleared.
    private const val FORCE_ALL_HLE = 2147483647 // lower 31 bits

    /**
     * Sets an integer key in ini lines. The core writes every setting into its own ppsspp.ini
     * after its first run, in whatever section it really belongs to, so the key is updated
     * WHEREVER it already exists (all occurrences). Only when it is absent (the core never
     * saved a config yet) is it inserted under [General]. Everything else is preserved.
     */
    private fun setGeneralIniInt(lines: MutableList<String>, key: String, value: Int, createIfMissing: Boolean) {
        var found = false
        var generalIdx = -1
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (generalIdx < 0 && trimmed.equals("[General]", ignoreCase = true)) generalIdx = i
                continue
            }
            if (trimmed.startsWith(key, ignoreCase = true) &&
                trimmed.substring(key.length).trimStart().startsWith("=")
            ) {
                lines[i] = "$key = $value"
                found = true
            }
        }
        if (found || !createIfMissing) return
        val line = "$key = $value"
        if (generalIdx >= 0) {
            lines.add(generalIdx + 1, line)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[General]")
            lines.add(line)
        }
    }

    /**
     * Pure in-place patch of a ppsspp.ini's text for the video HLE fallback. When
     * [forceHle] is true (no firmware installed): ForceEnableHLEFlags = all bits and
     * DisableHLEFlags = 0 under [General]. When false (firmware installed): both are
     * reset to 0 only if present, so the real firmware modules are used. Other
     * keys/sections are preserved. Returns null when there is nothing to write.
     */
    fun patchIniForVideoHle(existing: String?, forceHle: Boolean): String? {
        if (existing.isNullOrBlank() && !forceHle) return null
        val lines = if (existing.isNullOrBlank()) mutableListOf() else existing.lines().toMutableList()
        setGeneralIniInt(lines, "ForceEnableHLEFlags", if (forceHle) FORCE_ALL_HLE else 0, createIfMissing = forceHle)
        setGeneralIniInt(lines, "DisableHLEFlags", 0, createIfMissing = forceHle)
        // Idempotent: drop trailing blank lines so repeated launches don't grow the file.
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n") + "\n"
    }

    /**
     * Video HLE fallback (black-screen fix for 7z/RAR mods and any game that
     * plays video through sceMpeg).
     *
     * Why: this core version disables sceMpeg HLE by default. When a game
     * loads sceMpeg through sceUtility, the core looks ONLY for
     * flash0:/kd/mpeg.prx in the firmware — a disc copy of mpeg.prx does
     * not satisfy that path. Without firmware the warning appears and the
     * game gets unresolved imports, black-screening when video plays.
     * Forcing every bit in ForceEnableHLEFlags removes sceMpeg from
     * the effective disable flags, so sceUtility never attempts the
     * firmware load and the HLE video path plays.
     *
     * Firmware present = <pspDir>/NAND/flash0/kd/mpeg.prx exists. When
     * present, exactly the video bits are cleared so the real firmware
     * modules are used. Idempotent and safe to call on every launch; the
     * core reads the ini at startup, so this must run BEFORE the core
     * activity starts.
     */
    fun ensureVideoHleFallback(memstickDir: File) {
        try {
            val firmwareMpeg = File(memstickDir, "NAND/flash0/kd/mpeg.prx")
            val forceHle = !firmwareMpeg.isFile
            val systemDir = File(memstickDir, "SYSTEM").apply { mkdirs() }
            val ini = File(systemDir, "ppsspp.ini")
            val existing = if (ini.isFile) {
                try {
                    ini.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val patched = patchIniForVideoHle(existing, forceHle)
            if (patched != null && patched != existing) {
                ini.writeText(patched, Charsets.UTF_8)
            }
            // A per-game config (<GAMEID>_ppsspp.ini) overrides the global one. Patch only the
            // ones that already carry the HLE keys, so nothing new is created per game.
            systemDir.listFiles { f -> f.isFile && f.name.endsWith("_ppsspp.ini", ignoreCase = true) }?.forEach { g ->
                try {
                    val text = g.readText(Charsets.UTF_8)
                    if (text.contains("HLEFlags", ignoreCase = true)) {
                        val out = patchIniForVideoHle(text, forceHle)
                        if (out != null && out != text) g.writeText(out, Charsets.UTF_8)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // Best effort: if this fails, the core falls back to its own
            // defaults instead of crashing the launch.
        }
    }

    /**
     * Turns texture replacement ON in the global config, so a game
     * with installed textures shows them with no manual settings step.
     * Safe to call when the core isn't running; if the core later saves
     * settings it keeps the value (it loads the ini first).
     */
    fun enableTextureReplacement(memstickDir: File) {
        try {
            val systemDir = File(memstickDir, "SYSTEM").apply { mkdirs() }
            val ini = File(systemDir, "ppsspp.ini")
            val existing = if (ini.isFile) {
                try {
                    ini.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            ini.writeText(patchIniForTextureReplacement(existing), Charsets.UTF_8)
        } catch (_: Exception) {
            // Best effort: textures still work if the user (or the core's
            // default, which is True) already has replacement on.
        }
    }

    /**
     * Opens the system All Files Access screen for this app (one toggle,
     * NOT the SAF folder picker). Returns false when the screen couldn't
     * be opened at all.
     */
    fun openAllFilesAccessSettings(activity: Activity): Boolean {
        val pkg = activity.packageName
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$pkg")
            },
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}