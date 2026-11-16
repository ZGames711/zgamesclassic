package com.evolutionofgames.zgamespsx.emulator

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File

/**
 * Every SwanStation core option, wired to the in-emulator settings dialog.
 * Keys/values are the real libretro option keys from github.com/libretro/swanstation
 * (src/duckstation-libretro/libretro_host_interface.cpp) and the Recalbox core-options table.
 * [applyAll] pushes them into the native frontend before the core loads.
 * No BIOS is required: SwanStation falls back to its built-in OpenBIOS.
 */
object EmuSettings {
    private const val FILE = "zg_emu_settings"

    data class Opt(
        val key: String, val title: String, val sub: String,
        val values: List<String>, val default: String, val section: String,
        val labels: List<String> = values
    )
    private fun cy(key: String, title: String, sub: String, values: List<String>, default: String, section: String, labels: List<String> = values) =
        Opt(key, title, sub, values, default, section, labels)
    private fun sw(key: String, title: String, sub: String, default: Boolean, section: String) =
        Opt(key, title, sub, listOf("false", "true"), default.toString(), section, listOf("Off", "On"))

    val OPTIONS: List<Opt> = listOf(
        // Console
        cy("duckstation_Console.Region", "Console region", "Auto matches the disc.", listOf("Auto", "NTSC-J", "NTSC-U", "PAL"), "Auto", "Console"),
        sw("duckstation_Console.Enable8MBRAM", "8MB RAM (dev console)", "Only for homebrew that needs extra RAM.", false, "Console"),
        // BIOS
        sw("duckstation_BIOS.PatchFastBoot", "Fast boot", "Skip the PlayStation logo when a game starts.", false, "BIOS"),
        // CD-ROM
        sw("duckstation_CDROM.RegionCheck", "CD-ROM region check", "Refuse to boot discs from another region.", false, "CD-ROM"),
        sw("duckstation_CDROM.ReadThread", "CD-ROM read thread", "Reads the disc on a separate thread. Faster loading.", true, "CD-ROM"),
        sw("duckstation_CDROM.LoadImagePatches", "Apply image patches", "Applies PPF patches found next to the disc image.", false, "CD-ROM"),
        sw("duckstation_CDROM.LoadImageToRAM", "Preload disc to RAM", "Loads the whole disc into memory. Faster, uses more RAM.", false, "CD-ROM"),
        sw("duckstation_CDROM.PreCacheCHD", "Pre-cache CHD to RAM", "Loads CHD images into memory before starting.", false, "CD-ROM"),
        sw("duckstation_CDROM.MuteCDAudio", "Mute CD audio", "Silences CD music.", false, "CD-ROM"),
        cy("duckstation_CDROM.ReadSpeedup", "CD-ROM read speed", "Faster disc loading.", (1..10).map { it.toString() }, "1", "CD-ROM", (1..10).map { "${it}x" }),
        cy("duckstation_CDROM.SeekSpeedup", "CD-ROM seek speed", "0 = instant seeking.", (0..10).map { it.toString() }, "1", "CD-ROM", listOf("Instant") + (1..10).map { "${it}x" }),
        cy("duckstation_CDROM.ReadaheadSectors", "CD-ROM readahead", "Sectors read ahead of time. 8 is the default.", listOf("0", "1", "2", "4", "8", "16", "32"), "8", "CD-ROM"),
        // CPU
        cy("duckstation_CPU.ExecutionMode", "CPU mode", "Recompiler is fastest. Interpreter is the most accurate but slow.", listOf("Recompiler", "CachedInterpreter", "Interpreter"), "Recompiler", "CPU", listOf("Recompiler", "Cached", "Interpreter")),
        cy("duckstation_CPU.Overclock", "CPU overclock", "Speeds up the console CPU. Helps slow games.", listOf("50", "75", "100", "125", "150", "200", "300"), "100", "CPU", listOf("50%", "75%", "100%", "125%", "150%", "200%", "300%")),
        sw("duckstation_CPU.RecompilerICache", "Recompiler I-cache", "Emulates the CPU instruction cache. Slower, more accurate.", false, "CPU"),
        sw("duckstation_CPU.RecompilerBlockLinking", "Recompiler block linking", "Links translated blocks together. Faster.", true, "CPU"),
        cy("duckstation_CPU.FastmemMode", "Fast memory access", "LUT is faster, MMAP is fastest (64-bit only).", listOf("Disabled", "LUT", "MMAP"), "LUT", "CPU"),
        sw("duckstation_CPU.FastmemRewrite", "Fast memory rewrite", "Rewrites memory accesses for speed.", false, "CPU"),
        // Graphics
        cy("duckstation_GPU.Renderer", "Renderer", "Software — the built-in player is software-only.", listOf("Software"), "Software", "Graphics"),
        cy("duckstation_GPU.ResolutionScale", "Internal resolution", "Sharper picture at 2x or 4x. 1x is the original PS1 look.", listOf("1", "2", "4"), "1", "Graphics", listOf("1x", "2x", "4x")),
        sw("duckstation_GPU.UseSoftwareRendererForReadbacks", "Software renderer for readbacks", "Uses software rendering when the game reads the screen.", false, "Graphics"),
        cy("duckstation_GPU.MSAA", "Antialiasing (MSAA)", "Hardware renderer only — no effect in software mode.", listOf("1", "2", "4", "8"), "1", "Graphics", listOf("Off", "2x", "4x", "8x")),
        sw("duckstation_GPU.TrueColor", "True colour (24-bit)", "Removes colour banding.", false, "Graphics"),
        sw("duckstation_GPU.ScaledDithering", "Scaled dithering", "Smooths dithering at higher resolutions.", true, "Graphics"),
        sw("duckstation_GPU.DisableInterlacing", "Disable interlacing", "Forces a progressive picture.", false, "Graphics"),
        sw("duckstation_GPU.ForceNTSCTimings", "Force NTSC timings", "Runs PAL games at NTSC speed.", false, "Graphics"),
        sw("duckstation_GPU.ChromaSmoothingFor24Bit", "Chroma smoothing (24-bit)", "Smooths colour in 24-bit display mode.", false, "Graphics"),
        cy("duckstation_GPU.TextureFilter", "Texture filter", "Bilinear smooths textures; Nearest keeps the original pixels.", listOf("Nearest", "Bilinear", "BilinearBinAlpha", "JINC2", "JINC2BinAlpha", "xBR", "xBRBinAlpha"), "Nearest", "Graphics", listOf("Nearest", "Bilinear", "Bilinear (sharp)", "JINC2", "JINC2 (sharp)", "xBR", "xBR (sharp)")),
        sw("duckstation_GPU.WidescreenHack", "Widescreen hack", "Stretches 3D games to widescreen. Can break menus.", false, "Graphics"),
        sw("duckstation_GPU.PGXPEnable", "PGXP geometry correction", "Steadies wobbly 3D models.", false, "Graphics"),
        sw("duckstation_GPU.PGXPCulling", "PGXP culling correction", "Reduces holes in geometry. Needs PGXP.", true, "Graphics"),
        sw("duckstation_GPU.PGXPTextureCorrection", "PGXP texture correction", "Straightens warped textures. Needs PGXP.", true, "Graphics"),
        sw("duckstation_GPU.PGXPVertexCache", "PGXP vertex cache", "Caches vertices for speed. Needs PGXP.", false, "Graphics"),
        sw("duckstation_GPU.PGXPCPU", "PGXP on CPU", "Runs PGXP on the CPU instead of memory.", false, "Graphics"),
        sw("duckstation_GPU.PGXPDepthBuffer", "PGXP depth buffer", "More accurate depth. Needs PGXP.", false, "Graphics"),
        cy("duckstation_GPU.DownsampleMode", "Downsampling", "Smooths the picture when upscaling.", listOf("Disabled", "Box", "Adaptive"), "Disabled", "Graphics"),
        // Display
        sw("duckstation_Display.Force4_3For24Bit", "Force 4:3 in 24-bit mode", "Keeps 4:3 when the game uses 24-bit colour.", false, "Display"),
        cy("duckstation_Display.AspectRatio", "Aspect ratio", "Shape of the picture.", listOf("4:3", "16:9", "Auto"), "4:3", "Display"),
        cy("duckstation_Display.CropMode", "Crop mode", "Removes the black borders around the picture.", listOf("None", "Overscan", "Borders"), "Overscan", "Display"),
        sw("duckstation_Display.ShowOSDMessages", "Show on-screen messages", "Shows brief messages (e.g. when a save state is made).", true, "Display"),
        cy("duckstation_Display.ActiveStartOffset", "Active display start", "Shifts the picture. 0 is normal.", listOf("-10", "-5", "0", "5", "10"), "0", "Display"),
        cy("duckstation_Display.ActiveEndOffset", "Active display end", "Shifts the picture. 0 is normal.", listOf("-10", "-5", "0", "5", "10"), "0", "Display"),
        cy("duckstation_Display.LineStartOffset", "Line start offset", "Shifts scanlines. 0 is normal.", listOf("-10", "-5", "0", "5", "10"), "0", "Display"),
        cy("duckstation_Display.LineEndOffset", "Line end offset", "Shifts scanlines. 0 is normal.", listOf("-10", "-5", "0", "5", "10"), "0", "Display"),
        // Memory card
        cy("duckstation_MemoryCards.Card1Type", "Memory card 1", "How saves are stored for slot 1.", listOf("None", "Libretro", "Shared", "PerGame", "PerGameTitle"), "Libretro", "Memory Card", listOf("No card", "App managed", "Shared", "Per game", "Per game title")),
        cy("duckstation_MemoryCards.Card2Type", "Memory card 2", "How saves are stored for slot 2.", listOf("None", "Shared", "PerGame", "PerGameTitle"), "None", "Memory Card", listOf("No card", "Shared", "Per game", "Per game title")),
        sw("duckstation_MemoryCards.UsePlaylistTitle", "One card per playlist", "Shares one card across games in a playlist.", true, "Memory Card"),
        // Controller
        cy("duckstation_ControllerPorts.MultitapMode", "Multitap", "Adds extra controller ports.", listOf("Disabled", "Port1Only", "Port2Only", "BothPorts"), "Disabled", "Controller", listOf("Off", "Port 1 only", "Port 2 only", "Both ports")),
        cy("duckstation_Controller.AnalogCombo", "Analog mode combo", "Buttons that switch the pad between digital and analog.", listOf("1", "3", "4"), "3", "Controller", listOf("L1+R1+L3+R3", "L1+R1+Select", "L1+R1+Start")),
        sw("duckstation_Controller.EnableRumble", "Rumble", "Vibration on supported games.", true, "Controller"),
        // Main / hacks / audio / logging / textures
        sw("duckstation_Main.ApplyGameSettings", "Apply game fixes", "Applies known fixes for specific games.", true, "Main"),
        cy("duckstation_Main.RunaheadFrameCount", "Run-ahead", "Reduces input delay. Costs performance.", listOf("0", "1", "2"), "0", "Main", listOf("Off", "1 frame", "2 frames")),
        sw("duckstation_Hacks.UseOldMDECRoutines", "Old MDEC routines", "Old video decoding. Only for broken videos.", false, "Hacks"),
        sw("duckstation_Audio.FastHook", "Fast audio hook", "Faster audio. Turn off if sound is broken.", true, "Audio"),
        cy("duckstation_Logging.LogLevel", "Log level", "How much the emulator writes to the log.", listOf("None", "Error", "Warning", "Information", "Debug"), "Information", "Logging"),
        sw("duckstation_TextureReplacements.EnableVRAMWriteReplacements", "VRAM texture replacements", "Lets texture packs replace in-game textures.", false, "Textures")
    )

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun value(ctx: Context, opt: Opt): String = sp(ctx).getString(opt.key, opt.default) ?: opt.default
    fun set(ctx: Context, opt: Opt, v: String) { sp(ctx).edit().putString(opt.key, v).apply() }
    fun label(opt: Opt, v: String): String {
        val i = opt.values.indexOf(v)
        return if (i >= 0) opt.labels[i] else v
    }

    /** Pushes every setting into the native frontend. Call before nativeLoadCore. */
    fun applyAll(ctx: Context) {
        for (opt in OPTIONS) NativeCore.nativeSetVariable(opt.key, value(ctx, opt))
        // An installed BIOS (e.g. scph1001.bin) must be named in the per-region paths,
        // or the core won't find it — it only looks for scph5500/5501/5502.bin by default.
        installedBiosName(ctx)?.let { bios ->
            NativeCore.nativeSetVariable("duckstation_BIOS.PathNTSCJ", bios)
            NativeCore.nativeSetVariable("duckstation_BIOS.PathNTSCU", bios)
            NativeCore.nativeSetVariable("duckstation_BIOS.PathPAL", bios)
        }
    }

    /** Aspect ratio the picture should be drawn at ("4:3", "16:9" or "Auto" -> 4:3). */
    fun aspectRatio(ctx: Context): String {
        val opt = OPTIONS.firstOrNull { it.key == "duckstation_Display.AspectRatio" } ?: return "4:3"
        return value(ctx, opt)
    }

    /** The folder SwanStation reads BIOS files from (scph1001.bin etc.). */
    fun systemDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "system").apply { mkdirs() }

    /** Name of the installed BIOS file, or null when the built-in OpenBIOS is in use. */
    fun installedBiosName(ctx: Context): String? =
        systemDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.lowercase().startsWith("scph") && it.name.lowercase().endsWith(".bin") }
            ?.sortedBy { it.name }?.firstOrNull()?.name

    /** Copies a user-picked BIOS file into the system folder. Returns the stored name, or null. Call from a background thread. */
    fun installBios(ctx: Context, uri: Uri, displayName: String?): String? {
        return try {
            val dir = systemDir(ctx)
            val clean = displayName?.trim()?.takeIf { it.isNotBlank() } ?: "scph1001.bin"
            val name = if (clean.lowercase().startsWith("scph") && clean.lowercase().endsWith(".bin")) clean else "scph1001.bin"
            val out = File(dir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (out.isFile && out.length() > 0) name else null
        } catch (_: Exception) {
            null
        }
    }
}
