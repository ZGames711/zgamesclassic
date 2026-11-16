package com.evolutionofgames.zgamespsx.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PSX theme: deep midnight base, PlayStation-blue accent with a cyan
 * highlight, generous rounded shapes. No emulator names in UI text.
 */
object PsxColors {
    val Orange = Color(0xFF3D7BFF)
    val OrangeDark = Color(0xFF2557D6)
    val Yellow = Color(0xFF6CC4FF)
    val Background = Color(0xFF07090F)
    val Surface = Color(0xFF10141F)
    val SurfaceHigh = Color(0xFF192033)
    val Outline = Color(0xFF2A3350)
    val TextPrimary = Color(0xFFEAF0FF)
    val TextSecondary = Color(0xFF9AA6C4)
    val Gold = Color(0xFFFFC107)
    val Success = Color(0xFF34D399)

    /** Primary accent gradient (buttons, logo wordmark, badges). */
    val AccentBrush: Brush get() = Brush.horizontalGradient(listOf(Yellow, Orange))
    val ScrimBrush: Brush
        get() = Brush.verticalGradient(
            listOf(Color.Transparent, Color(0xCC07090F), Color(0xFF07090F))
        )
}

private val PsxColorScheme = darkColorScheme(
    primary = PsxColors.Orange,
    onPrimary = Color.White,
    primaryContainer = PsxColors.OrangeDark,
    onPrimaryContainer = Color.White,
    secondary = PsxColors.Yellow,
    onSecondary = Color(0xFF021B2B),
    tertiary = PsxColors.Yellow,
    background = PsxColors.Background,
    onBackground = PsxColors.TextPrimary,
    surface = PsxColors.Surface,
    onSurface = PsxColors.TextPrimary,
    surfaceVariant = PsxColors.SurfaceHigh,
    onSurfaceVariant = PsxColors.TextSecondary,
    surfaceContainer = PsxColors.Surface,
    surfaceContainerHigh = PsxColors.SurfaceHigh,
    outline = PsxColors.Outline,
    outlineVariant = PsxColors.Outline,
    error = Color(0xFFFF6B6B)
)

private val PsxShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PsxTheme(content: @Composable () -> Unit) {
    // Accent colour chosen in the WordPress plugin (empty = built-in blue).
    val remote by com.evolutionofgames.zgamespsx.remote.WpConfig.content.collectAsState()
    val choice by com.evolutionofgames.zgamespsx.remote.WpConfig.selectedTheme.collectAsState()
    val light by com.evolutionofgames.zgamespsx.remote.WpConfig.light.collectAsState()
    val scheme = remember(remote.accent, remote.themes, remote.defaultTheme, remote.allowUserTheme, choice, light, remote.features) {
        val userId = if (remote.allowUserTheme && choice.isNotEmpty()) choice else ""
        val th = remote.themes.firstOrNull { it.id == userId }
            ?: remote.themes.firstOrNull { it.id == remote.defaultTheme && remote.defaultTheme != "zgames" }
        val a = if (th != null) th.accent else remote.accent
        val useLight = light && remote.flag("light_mode", false)
        var cs = if (useLight) lightColorScheme(primary = Color(0xFF1F4FD1), onPrimary = Color.White, secondary = Color(0xFF0F6C99), background = Color(0xFFF4F7FF), onBackground = Color(0xFF111827), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111827), surfaceVariant = Color(0xFFE3E9F8), onSurfaceVariant = Color(0xFF3F4B66), outline = Color(0xFF7A86A5)) else PsxColorScheme
        if (th != null && !useLight) {
            val bg = Color(th.background)
            val sf = Color(th.surface)
            val hi = androidx.compose.ui.graphics.lerp(sf, Color.White, 0.08f)
            cs = cs.copy(
                background = bg, surface = sf, surfaceVariant = hi,
                surfaceContainer = sf, surfaceContainerHigh = hi,
                surfaceContainerLow = sf, surfaceContainerLowest = bg, surfaceContainerHighest = hi,
                onBackground = readableText(Color(th.text), bg), onSurface = readableText(Color(th.text), sf),
                onSurfaceVariant = readableText(Color(th.text), hi)
            )
        }
        if (a == null) cs else {
            val c = Color(a)
            cs.copy(
                primary = c,
                primaryContainer = c,
                onPrimaryContainer = readableText(Color.White, c),
                onPrimary = readableText(Color.White, c)
            )
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = PsxShapes,
        content = content
    )
}

/** Keep plugin-defined text readable against the selected background. */
private fun readableText(text: Color, background: Color): Color {
    val ratio = (maxOf(text.luminance(), background.luminance()) + 0.05f) /
        (minOf(text.luminance(), background.luminance()) + 0.05f)
    return if (ratio >= 4.5f) text else if (background.luminance() > 0.179f) Color.Black else Color.White
}
