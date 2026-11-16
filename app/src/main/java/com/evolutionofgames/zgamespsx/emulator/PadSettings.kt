package com.evolutionofgames.zgamespsx.emulator

import android.content.Context

/**
 * On-screen controller look + layout, like DuckStation's touchscreen settings:
 * button theme, button size, button opacity, and a draggable layout (each control
 * cluster can be moved in "Move buttons" mode). Stored in the same prefs file as
 * the core settings. Layout offsets are in base-u units (u = min(w,h) * 0.10 at
 * scale 1.0) so they stay put when the button size changes.
 */
object PadSettings {
    private const val FILE = "zg_emu_settings"
    private const val KEY_THEME = "pad_theme"
    private const val KEY_SCALE = "pad_scale"
    private const val KEY_OPACITY = "pad_opacity"
    private const val KEY_LAYOUT = "pad_layout"
    private const val KEY_ANALOG = "pad_analog"

    // Clusters that can be dragged in edit mode.
    const val C_DPAD = "dpad"
    const val C_FACE = "face"
    const val C_STICK_L = "stickL"
    const val C_STICK_R = "stickR"
    const val C_SH_L = "shL"
    const val C_SH_R = "shR"
    const val C_SEL = "sel"
    const val C_MENU = "menu"
    val CLUSTER_LABELS = mapOf(
        C_DPAD to "D-PAD", C_FACE to "BUTTONS", C_STICK_L to "LEFT STICK",
        C_STICK_R to "RIGHT STICK", C_SH_L to "L1  L2", C_SH_R to "R1  R2",
        C_SEL to "SELECT  START", C_MENU to "MENU"
    )

    enum class Theme(val id: String, val label: String) {
        OUTLINE("outline", "Classic white"),
        PS("ps", "PlayStation"),
        NEON("neon", "Neon");

        companion object {
            fun of(id: String) = values().firstOrNull { it.id == id } ?: OUTLINE
        }
    }

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(ctx: Context): Theme =
        Theme.of(sp(ctx).getString(KEY_THEME, Theme.OUTLINE.id) ?: Theme.OUTLINE.id)

    fun setTheme(ctx: Context, t: Theme) {
        sp(ctx).edit().putString(KEY_THEME, t.id).apply()
    }

    val SCALES = listOf(0.8f to "Small", 1.0f to "Normal", 1.2f to "Large", 1.4f to "Extra large")
    fun scale(ctx: Context): Float = sp(ctx).getFloat(KEY_SCALE, 0.8f)
    fun scaleLabel(ctx: Context): String =
        SCALES.firstOrNull { it.first == scale(ctx) }?.second ?: "Normal"

    fun setScale(ctx: Context, s: Float) {
        sp(ctx).edit().putFloat(KEY_SCALE, s).apply()
    }

    val OPACITIES = listOf(0.4f to "Faint", 0.6f to "Light", 0.8f to "Normal", 1.0f to "Solid")
    fun opacity(ctx: Context): Float = sp(ctx).getFloat(KEY_OPACITY, 0.8f)
    fun opacityLabel(ctx: Context): String =
        OPACITIES.firstOrNull { it.first == opacity(ctx) }?.second ?: "Normal"

    fun setOpacity(ctx: Context, o: Float) {
        sp(ctx).edit().putFloat(KEY_OPACITY, o).apply()
    }

    /** Analog sticks on/off. Off hides the sticks and the core runs as a digital pad. Off by default. */
    fun analogEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ANALOG, false)
    fun setAnalogEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ANALOG, on).apply()
    }

    /** Offsets in base-u units. Stored as "key:dx,dy;key:dx,dy". */
    fun offsets(ctx: Context): Map<String, Pair<Float, Float>> {
        val raw = sp(ctx).getString(KEY_LAYOUT, "") ?: ""
        val map = HashMap<String, Pair<Float, Float>>()
        for (part in raw.split(";")) {
            val kv = part.split(":")
            if (kv.size != 2) continue
            val xy = kv[1].split(",")
            if (xy.size != 2) continue
            val dx = xy[0].toFloatOrNull() ?: continue
            val dy = xy[1].toFloatOrNull() ?: continue
            map[kv[0]] = dx to dy
        }
        return map
    }

    fun setOffset(ctx: Context, key: String, dx: Float, dy: Float) {
        val map = offsets(ctx).toMutableMap()
        map[key] = dx to dy
        sp(ctx).edit().putString(KEY_LAYOUT, map.entries.joinToString(";") {
            "${it.key}:${it.value.first},${it.value.second}"
        }).apply()
    }

    fun resetLayout(ctx: Context) {
        sp(ctx).edit().remove(KEY_LAYOUT).apply()
    }
}
