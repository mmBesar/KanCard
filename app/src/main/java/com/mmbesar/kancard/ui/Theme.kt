package com.mmbesar.kancard.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ---- What the user can choose ---------------------------------------------------------------

/** The overall look. */
enum class ThemeChoice {
    /** Catppuccin Latte in light mode, Macchiato in dark mode (follows the phone). */
    AUTO,
    LATTE,
    FRAPPE,
    MACCHIATO,
    MOCHA,

    /** The colours of the phone's wallpaper (Material You, Android 12 and newer). */
    SYSTEM,
}

/** The 14 Catppuccin accent colours, in the order used by [CatFlavor.accents]. */
enum class CatAccent {
    ROSEWATER, FLAMINGO, PINK, MAUVE, RED, MAROON, PEACH, YELLOW, GREEN, TEAL, SKY, SAPPHIRE, BLUE, LAVENDER
}

/**
 * Everything the user can change about the looks.
 *
 * [accent]: "" = the theme's own accent (Mauve for Catppuccin, the system's own
 * colour for System colours), the name of a [CatAccent], or "CUSTOM" (see [customAccent]).
 * The accent works on EVERY theme, including System colours.
 */
data class ThemeSettings(
    val theme: ThemeChoice = ThemeChoice.AUTO,
    val accent: String = "",
    val customAccent: Int = 0xFF8839EF.toInt(),
    /** Pure black backgrounds in dark themes (saves battery on OLED screens). */
    val amoled: Boolean = false,
    /** Show the description text on task cards. */
    val showDescriptions: Boolean = true,
)

// ---- The Catppuccin palettes (https://catppuccin.com/palette) ----------------------------------------

private fun c(rgb: Long) = Color(0xFF000000L or rgb)

/** One Catppuccin flavour. [accents] follows the order of [CatAccent]. */
class CatFlavor(
    val dark: Boolean,
    val accents: List<Color>,
    val text: Color,
    val subtext0: Color,
    val overlay0: Color,
    val surface1: Color,
    val surface0: Color,
    val base: Color,
    val mantle: Color,
    val crust: Color,
)

object Catppuccin {
    val latte = CatFlavor(
        dark = false,
        accents = listOf(
            c(0xdc8a78), c(0xdd7878), c(0xea76cb), c(0x8839ef), c(0xd20f39), c(0xe64553), c(0xfe640b),
            c(0xdf8e1d), c(0x40a02b), c(0x179299), c(0x04a5e5), c(0x209fb5), c(0x1e66f5), c(0x7287fd),
        ),
        text = c(0x4c4f69), subtext0 = c(0x6c6f85), overlay0 = c(0x9ca0b0),
        surface1 = c(0xbcc0cc), surface0 = c(0xccd0da),
        base = c(0xeff1f5), mantle = c(0xe6e9ef), crust = c(0xdce0e8),
    )

    val frappe = CatFlavor(
        dark = true,
        accents = listOf(
            c(0xf2d5cf), c(0xeebebe), c(0xf4b8e4), c(0xca9ee6), c(0xe78284), c(0xea999c), c(0xef9f76),
            c(0xe5c890), c(0xa6d189), c(0x81c8be), c(0x99d1db), c(0x85c1dc), c(0x8caaee), c(0xbabbf1),
        ),
        text = c(0xc6d0f5), subtext0 = c(0xa5adce), overlay0 = c(0x737994),
        surface1 = c(0x51576d), surface0 = c(0x414559),
        base = c(0x303446), mantle = c(0x292c3c), crust = c(0x232634),
    )

    val macchiato = CatFlavor(
        dark = true,
        accents = listOf(
            c(0xf4dbd6), c(0xf0c6c6), c(0xf5bde6), c(0xc6a0f6), c(0xed8796), c(0xee99a0), c(0xf5a97f),
            c(0xeed49f), c(0xa6da95), c(0x8bd5ca), c(0x91d7e3), c(0x7dc4e4), c(0x8aadf4), c(0xb7bdf8),
        ),
        text = c(0xcad3f5), subtext0 = c(0xa5adcb), overlay0 = c(0x6e738d),
        surface1 = c(0x494d64), surface0 = c(0x363a4f),
        base = c(0x24273a), mantle = c(0x1e2030), crust = c(0x181926),
    )

    val mocha = CatFlavor(
        dark = true,
        accents = listOf(
            c(0xf5e0dc), c(0xf2cdcd), c(0xf5c2e7), c(0xcba6f7), c(0xf38ba8), c(0xeba0ac), c(0xfab387),
            c(0xf9e2af), c(0xa6e3a1), c(0x94e2d5), c(0x89dceb), c(0x74c7ec), c(0x89b4fa), c(0xb4befe),
        ),
        text = c(0xcdd6f4), subtext0 = c(0xa6adc8), overlay0 = c(0x6c7086),
        surface1 = c(0x45475a), surface0 = c(0x313244),
        base = c(0x1e1e2e), mantle = c(0x181825), crust = c(0x11111b),
    )
}

// ---- Building the colour scheme ------------------------------------------------------------------------

/** Black or near-black text if the colour is light, white if it is dark. */
fun onColorFor(color: Color): Color = if (color.luminance() > 0.5f) Color(0xFF111111) else Color.White

/** True if the phone can show the system (wallpaper) colours. */
@androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun systemColorsAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The flavour that matches the choice (for Auto and unsupported System colours: by phone dark mode). */
fun flavorFor(theme: ThemeChoice, systemDark: Boolean): CatFlavor = when (theme) {
    ThemeChoice.LATTE -> Catppuccin.latte
    ThemeChoice.FRAPPE -> Catppuccin.frappe
    ThemeChoice.MACCHIATO -> Catppuccin.macchiato
    ThemeChoice.MOCHA -> Catppuccin.mocha
    ThemeChoice.AUTO, ThemeChoice.SYSTEM -> if (systemDark) Catppuccin.macchiato else Catppuccin.latte
}

/** The accent the user picked, or null for "the theme's own accent". */
fun chosenAccent(settings: ThemeSettings, flavor: CatFlavor): Color? = when (settings.accent) {
    "" -> null
    "CUSTOM" -> Color(settings.customAccent)
    else -> runCatching { CatAccent.valueOf(settings.accent) }.getOrNull()?.let { flavor.accents[it.ordinal] }
}

private fun catppuccinScheme(f: CatFlavor, accent: Color, amoled: Boolean): ColorScheme {
    val black = amoled && f.dark
    val bg = if (black) Color.Black else f.base
    val low = if (black) Color.Black else f.mantle
    val lowest = if (black) Color.Black else f.crust
    // Cards stay slightly lighter than the black background so they remain visible.
    val high = if (black) lerp(f.surface0, Color.Black, 0.55f) else f.surface0
    val highest = if (black) lerp(f.surface1, Color.Black, 0.45f) else f.surface1
    val lavender = f.accents[CatAccent.LAVENDER.ordinal]
    val blue = f.accents[CatAccent.BLUE.ordinal]
    val secondary = if (accent == lavender) blue else lavender
    val red = f.accents[CatAccent.RED.ordinal]

    val start = if (f.dark) darkColorScheme() else lightColorScheme()
    return start.copy(
        primary = accent,
        onPrimary = onColorFor(accent),
        primaryContainer = lerp(bg, accent, 0.28f),
        onPrimaryContainer = f.text,
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = highest,
        onSecondaryContainer = f.text,
        background = bg,
        onBackground = f.text,
        surface = bg,
        onSurface = f.text,
        surfaceVariant = high,
        onSurfaceVariant = f.subtext0,
        surfaceTint = accent,
        outline = f.overlay0,
        outlineVariant = highest,
        error = red,
        onError = onColorFor(red),
        surfaceContainerLowest = lowest,
        surfaceContainerLow = low,
        surfaceContainer = low,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
    )
}

/** Puts the user's accent (and AMOLED black) on top of the phone's own colours. */
private fun tuneSystemScheme(base: ColorScheme, accent: Color?, amoled: Boolean, dark: Boolean): ColorScheme {
    var scheme = base
    if (accent != null) {
        scheme = scheme.copy(
            primary = accent,
            onPrimary = onColorFor(accent),
            primaryContainer = lerp(scheme.surface, accent, 0.28f),
            onPrimaryContainer = scheme.onSurface,
            surfaceTint = accent,
        )
    }
    if (amoled && dark) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = lerp(scheme.surfaceContainerHigh, Color.Black, 0.55f),
            surfaceContainerHighest = lerp(scheme.surfaceContainerHighest, Color.Black, 0.45f),
        )
    }
    return scheme
}

/** The colours for the current settings. [systemDark] = whether the phone is in dark mode. */
fun schemeFor(settings: ThemeSettings, systemDark: Boolean, context: Context): ColorScheme {
    if (settings.theme == ThemeChoice.SYSTEM && systemColorsAvailable()) {
        val base = if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val accent = chosenAccent(settings, flavorFor(ThemeChoice.AUTO, systemDark))
        return tuneSystemScheme(base, accent, settings.amoled, systemDark)
    }
    val flavor = flavorFor(settings.theme, systemDark)
    val accent = chosenAccent(settings, flavor) ?: flavor.accents[CatAccent.MAUVE.ordinal]
    return catppuccinScheme(flavor, accent, settings.amoled)
}

/** Wrap every screen in this to get the KanCard colours. */
@Composable
fun KanCardTheme(settings: ThemeSettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = remember(settings, systemDark) { schemeFor(settings, systemDark, context) }

    // Status and navigation bar icons must be readable on the chosen background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val light = scheme.background.luminance() > 0.5f
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = light
                controller.isAppearanceLightNavigationBars = light
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
