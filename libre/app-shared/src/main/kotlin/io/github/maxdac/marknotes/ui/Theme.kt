package io.github.maxdac.marknotes.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import io.github.maxdac.marknotes.data.ThemeColor
import io.github.maxdac.marknotes.data.ThemeMode

/** Primary colours of a fixed palette: tone 40 for light mode and tone 80 for dark mode. */
private data class Palette(val light: Long, val dark: Long)

private val palettes = mapOf(
    ThemeColor.BLUE to Palette(0xFF1E5EB4, 0xFFA8C8FF),
    ThemeColor.TEAL to Palette(0xFF006A65, 0xFF4FDBD3),
    ThemeColor.GREEN to Palette(0xFF2E6B2F, 0xFF94D88E),
    ThemeColor.LIME to Palette(0xFF566500, 0xFFBDD161),
    ThemeColor.AMBER to Palette(0xFF7A5900, 0xFFF6BE3C),
    ThemeColor.ORANGE to Palette(0xFF9A4600, 0xFFFFB68A),
    ThemeColor.RED to Palette(0xFFB3261E, 0xFFFFB4AB),
    ThemeColor.PURPLE to Palette(0xFF6750A4, 0xFFD0BCFF)
)

/** Swatch shown in the colour picker. */
fun ThemeColor.swatch(): Color = Color(palettes[this]?.light ?: 0xFF6750A4)

private fun lightScheme(palette: Palette): ColorScheme {
    val primary = Color(palette.light)
    val container = lerp(primary, Color.White, 0.82f)
    val secondary = lerp(primary, Color(0xFF5F6368), 0.55f)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = lerp(primary, Color.Black, 0.6f),
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = lerp(secondary, Color.White, 0.82f),
        onSecondaryContainer = lerp(secondary, Color.Black, 0.6f),
        tertiary = lerp(primary, Color(0xFF7D5260), 0.5f),
        tertiaryContainer = lerp(primary, Color(0xFFFFD8E4), 0.75f),
        surfaceTint = primary
    )
}

private fun darkScheme(palette: Palette): ColorScheme {
    val primary = Color(palette.dark)
    val secondary = lerp(primary, Color(0xFFC4C7C5), 0.55f)
    return darkColorScheme(
        primary = primary,
        onPrimary = lerp(Color(palette.light), Color.Black, 0.55f),
        primaryContainer = lerp(Color(palette.light), Color.Black, 0.25f),
        onPrimaryContainer = lerp(primary, Color.White, 0.6f),
        secondary = secondary,
        onSecondary = lerp(secondary, Color.Black, 0.7f),
        secondaryContainer = lerp(secondary, Color.Black, 0.6f),
        onSecondaryContainer = lerp(secondary, Color.White, 0.6f),
        tertiary = lerp(primary, Color(0xFFEFB8C8), 0.5f),
        tertiaryContainer = lerp(primary, Color(0xFF633B48), 0.75f),
        surfaceTint = primary
    )
}

@Composable
fun MarkNotesTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    color: ThemeColor = ThemeColor.DYNAMIC,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val palette = palettes[color]
    val fallback = palettes.getValue(ThemeColor.BLUE)
    val colors = when {
        palette != null -> if (dark) darkScheme(palette) else lightScheme(palette)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> if (dark) darkScheme(fallback) else lightScheme(fallback)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
