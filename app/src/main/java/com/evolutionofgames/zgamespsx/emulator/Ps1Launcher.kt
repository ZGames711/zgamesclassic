package com.evolutionofgames.zgamespsx.emulator

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.evolutionofgames.zgamespsx.install.InstalledGame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts a downloaded PlayStation 1 game in a real PS1 emulator.
 *
 * Engine: the BUILT-IN emulator (libretro frontend + SwanStation core in jniLibs, see CORE_README.md)
 * when the core ships with the build; otherwise RetroArch running the libretro core chosen in the plugin.
 * A real PS1 BIOS is optional: install one from Settings -> Emulator, or drop it in the app's
 * system folder (Android/data/<package>/files/system). Without one the core uses OpenBIOS.
 *
 * Flow: tap -> checks -> interstitial (ad plugin switch) -> emulator opens. When the player
 * returns, [EmulatorSession] lets MainActivity show the exit interstitial once.
 */
object Ps1Launcher {

    const val RETROARCH_PLAY_ID = "com.retroarch.aarch64"
    private val RETROARCH_PACKAGES = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    private const val RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    /** Core file for the plugin-selected libretro core (default SwanStation, a DuckStation fork). */
    private fun coreFile(): String = when (com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.emulatorCore) {
        "pcsx_rearmed" -> "pcsx_rearmed_libretro_android.so"
        else -> "swanstation_libretro_android.so"
    }
    private val DISC_EXTENSIONS = setOf("cue", "chd", "pbp", "iso", "bin", "img", "m3u", "mdf", "ccd")

    @Volatile private var launching = false

    fun installedRetroArch(context: Context): String? =
        RETROARCH_PACKAGES.firstOrNull { isInstalled(context, it) }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Throwable) { false }

    /** Opens the store page of the recommended emulator. */
    fun openEmulatorStore(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$RETROARCH_PLAY_ID")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$RETROARCH_PLAY_ID")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) { }
        }
    }

    private data class Prep(val path: String?, val block: String?)

    private suspend fun prepare(context: Context, game: InstalledGame): Prep = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val modsDir = PathResolver.modsDownloadsDir()
        val mediaStorePath = PathResolver.mediaStorePathFor(appCtx, game.uri)
        val path = PathResolver.resolveLaunchPath(game.uri, game.fileName, modsDir, mediaStorePath)
        val file = File(path)
        if (path == game.uri || !file.isFile) {
            return@withContext Prep(null, "Couldn't find \"${game.title}\" on your phone. Download it again.")
        }
        var ext = file.extension.lowercase().ifBlank { game.fileName.substringAfterLast('.', "").lowercase() }
        if (ext !in DISC_EXTENSIONS) {
            return@withContext Prep(null, "\"${game.title}\" isn't a PlayStation disc image (.cue/.bin, .iso, .chd, .pbp).")
        }
        // A lone .bin plays best through its .cue sheet when one sits beside it.
        if (ext == "bin") {
            val cue = File(file.parentFile, file.nameWithoutExtension + ".cue")
            if (cue.isFile) return@withContext Prep(cue.absolutePath, null)
        }
        Prep(path, null)
    }

    suspend fun play(
        activity: Activity,
        adGate: LaunchAdGate,
        game: InstalledGame,
        onNeedEmulator: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        if (launching) return
        launching = true
        try {
            val prep = prepare(activity, game)
            if (prep.path == null) {
                launching = false
                onBlocked(prep.block ?: "This game can't be started.")
                return
            }
            val path = prep.path
            adGate.showLaunchInterstitial(activity) {
                launching = false
                if (activity.isFinishing || activity.isDestroyed) return@showLaunchInterstitial
                if (!start(activity, game, path)) onNeedEmulator()
            }
        } catch (_: Exception) {
            launching = false
        }
    }

    private fun preferredCore(): String = com.evolutionofgames.zgamespsx.remote.WpConfig.content.value.emulatorCore

    /** True when the built-in libretro core (jniLibs) ships with this build. */
    fun hasBuiltInCore(context: Context): Boolean = EmulatorActivity.corePath(context, preferredCore()) != null

    /** Returns true when an emulator was opened. */
    private fun start(activity: Activity, game: InstalledGame, path: String): Boolean {
        // 1) The built-in emulator (runs inside the app, own process).
        if (hasBuiltInCore(activity)) {
            try {
                activity.startActivity(
                    Intent(activity, EmulatorActivity::class.java)
                        .putExtra(EmulatorActivity.EXTRA_PATH, path)
                        .putExtra(EmulatorActivity.EXTRA_TITLE, game.title)
                        .putExtra("core", preferredCore())
                )
                markStarted(game)
                return true
            } catch (_: Throwable) { }
        }
        // 2) Fallback: an external PS1 emulator (RetroArch) when no core is bundled.
        val ra = installedRetroArch(activity)
        if (ra != null) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(ra, RETROARCH_ACTIVITY)
                    putExtra("ROM", path)
                    putExtra("LIBRETRO", "/data/data/$ra/cores/${coreFile()}")
                    putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$ra/files/retroarch.cfg")
                    putExtra("QUITFOCUS", "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                activity.startActivity(intent)
                markStarted(game)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Throwable) { }
        }
        return false
    }

    private fun markStarted(game: InstalledGame) {
        PlaytimeStats.begin(game.key)
        EmulatorSession.markGameStarted()
    }
}
