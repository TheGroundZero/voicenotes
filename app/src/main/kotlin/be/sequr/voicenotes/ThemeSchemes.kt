// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

/** The colour themes the user can choose (docs/THEMES.md). SYSTEM, LIGHT and DARK use the wallpaper colours; the others are fixed. */
enum class AppTheme(val id: String, val label: String) {
    SYSTEM("system", "Follow the system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    PAPER("paper", "Paper"),
    HIGH_CONTRAST("high-contrast", "High contrast"),
    MATRIX("matrix", "Matrix"),
    DRACULA("dracula", "Dracula");

    /** The fixed colours of this theme, or null when it uses the wallpaper colours. [systemDark] only matters for High contrast. */
    fun seeds(systemDark: Boolean): Seeds? = when (this) {
        PAPER -> ThemeSeeds.paper
        HIGH_CONTRAST -> if (systemDark) ThemeSeeds.highContrastDark else ThemeSeeds.highContrastLight
        MATRIX -> ThemeSeeds.matrix
        DRACULA -> ThemeSeeds.dracula
        SYSTEM, LIGHT, DARK -> null
    }

    companion object {
        /** An unknown or missing id (an older or newer version's preference) means the default. */
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** WCAG 2.x contrast on colours written as 0xAARRGGBB (the alpha is ignored; every theme colour is opaque). */
object Contrast {
    private fun channel(argb: Long, shift: Int) = ((argb shr shift) and 0xFF).toInt()

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Long): Double =
        0.2126 * linear(channel(argb, 16)) + 0.7152 * linear(channel(argb, 8)) + 0.0722 * linear(channel(argb, 0))

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [t] = 0 gives [from], 1 gives [to]. */
    fun blend(from: Long, to: Long, t: Double): Long {
        fun mix(shift: Int) = (channel(from, shift) * (1 - t) + channel(to, shift) * t).roundToInt().coerceIn(0, 255).toLong() shl shift
        return 0xFF000000L or mix(16) or mix(8) or mix(0)
    }

    /** [fg] moved toward [toward] in 2 % steps until it reaches [min] on every one of [backgrounds]; unchanged if it already does. */
    fun ensure(fg: Long, backgrounds: List<Long>, min: Double, toward: Long): Long {
        var t = 0.0
        while (t <= 1.0) {
            val candidate = blend(fg, toward, t)
            if (backgrounds.all { ratio(candidate, it) >= min }) return candidate
            t += 0.02
        }
        return toward
    }

    /** Whichever of [candidates] reads best on [background]. */
    fun best(background: Long, vararg candidates: Long): Long = candidates.maxByOrNull { ratio(it, background) }!!
}

/**
 * The few colours that define a fixed theme. [minContrast] is the contrast every text colour must reach (4.5 normally, 7 for
 * High contrast). A palette's own colours are only a starting point: [derive] lightens or darkens an accent until it is readable
 * on the background and on the lightest card, so the palette's hex values and the final colours can differ a little.
 */
class Seeds(
    val dark: Boolean,
    val minContrast: Double,
    val background: Long,
    val card: Long,
    val text: Long,
    val primary: Long,
    val secondary: Long,
    val tertiary: Long,
    val error: Long,
    val outline: Long,
)

object ThemeSeeds {
    val paper = Seeds(
        dark = false, minContrast = 4.5, background = 0xFFF6F1E4, card = 0xFFEBE3D0, text = 0xFF2B2620,
        primary = 0xFF2F4B7C, secondary = 0xFF7A4B1E, tertiary = 0xFF4B6B3A, error = 0xFF9B1C1C, outline = 0xFF7D7360,
    )
    val highContrastLight = Seeds(
        dark = false, minContrast = 7.0, background = 0xFFFFFFFF, card = 0xFFEDEDED, text = 0xFF000000,
        primary = 0xFF0033CC, secondary = 0xFF7A3E00, tertiary = 0xFF006B2E, error = 0xFFB00000, outline = 0xFF595959,
    )
    val highContrastDark = Seeds(
        dark = true, minContrast = 7.0, background = 0xFF000000, card = 0xFF1F1F1F, text = 0xFFFFFFFF,
        primary = 0xFFFFD60A, secondary = 0xFF4DD0E1, tertiary = 0xFFB2FF59, error = 0xFFFF6B6B, outline = 0xFFB3B3B3,
    )
    val matrix = Seeds(
        dark = true, minContrast = 4.5, background = 0xFF000000, card = 0xFF0F1F12, text = 0xFF7CFF9A,
        primary = 0xFF00FF41, secondary = 0xFF00B82E, tertiary = 0xFFB8FF5A, error = 0xFFFF4D4D, outline = 0xFF009926,
    )
    // The official Dracula palette (MIT, "Color Palette (OSS)"); see third_party/README.md.
    val dracula = Seeds(
        dark = true, minContrast = 4.5, background = 0xFF282A36, card = 0xFF44475A, text = 0xFFF8F8F2,
        primary = 0xFFBD93F9, secondary = 0xFFFF79C6, tertiary = 0xFF8BE9FD, error = 0xFFFF5555, outline = 0xFF6272A4,
    )
    val all = listOf(paper, highContrastLight, highContrastDark, matrix, dracula)
}

/** Every colour role the app's Material 3 scheme needs, as 0xAARRGGBB. */
class Roles(
    val dark: Boolean,
    val minContrast: Double,
    val background: Long, val onBackground: Long,
    val surface: Long, val onSurface: Long, val surfaceVariant: Long, val onSurfaceVariant: Long,
    val surfaceDim: Long, val surfaceBright: Long,
    val surfaceContainerLowest: Long, val surfaceContainerLow: Long, val surfaceContainer: Long,
    val surfaceContainerHigh: Long, val surfaceContainerHighest: Long,
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
    val outline: Long, val outlineVariant: Long,
    val inverseSurface: Long, val inverseOnSurface: Long, val inversePrimary: Long,
) {
    /** The surfaces text can sit on. */
    val surfaces get() = listOf(background, surface, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceVariant)

    fun toColorScheme(): ColorScheme {
        fun c(argb: Long) = Color(argb)
        val scrim = Color.Black
        return if (dark) {
            darkColorScheme(
                primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
                inversePrimary = c(inversePrimary),
                secondary = c(secondary), onSecondary = c(onSecondary), secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
                tertiary = c(tertiary), onTertiary = c(onTertiary), tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
                background = c(background), onBackground = c(onBackground), surface = c(surface), onSurface = c(onSurface),
                surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant), surfaceTint = c(primary),
                inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
                error = c(error), onError = c(onError), errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
                outline = c(outline), outlineVariant = c(outlineVariant), scrim = scrim,
                surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
                surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
                surfaceContainerHighest = c(surfaceContainerHighest), surfaceContainerLow = c(surfaceContainerLow),
                surfaceContainerLowest = c(surfaceContainerLowest),
            )
        } else {
            lightColorScheme(
                primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
                inversePrimary = c(inversePrimary),
                secondary = c(secondary), onSecondary = c(onSecondary), secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
                tertiary = c(tertiary), onTertiary = c(onTertiary), tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
                background = c(background), onBackground = c(onBackground), surface = c(surface), onSurface = c(onSurface),
                surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant), surfaceTint = c(primary),
                inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
                error = c(error), onError = c(onError), errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
                outline = c(outline), outlineVariant = c(outlineVariant), scrim = scrim,
                surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
                surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
                surfaceContainerHighest = c(surfaceContainerHighest), surfaceContainerLow = c(surfaceContainerLow),
                surfaceContainerLowest = c(surfaceContainerLowest),
            )
        }
    }
}

/** Fills in all Material 3 roles from a handful of seeds; the contrast test (ThemeSchemesTest) checks the result. */
fun derive(s: Seeds): Roles {
    val min = s.minContrast
    val iconMin = if (min >= 7.0) 4.5 else 3.0
    val bg = s.background
    val text = s.text
    fun mix(t: Double) = Contrast.blend(bg, s.card, t)
    val low = mix(0.35)
    val container = mix(0.65)
    val high = mix(0.85)
    val highest = s.card // the palette's own card colour is the lightest surface text can sit on
    val dim = if (s.dark) bg else Contrast.blend(bg, text, 0.06)
    val bright = if (s.dark) Contrast.blend(bg, text, 0.12) else bg
    // Accents are used as text and icons on the background and on cards, so they must be readable on the lightest surface too.
    val widest = listOf(bg, dim, bright, highest)
    fun accent(seed: Long) = Contrast.ensure(seed, widest, min, text)
    fun onAccent(accent: Long) = Contrast.best(accent, bg, text, 0xFF000000, 0xFFFFFFFF)
    fun container(accent: Long) = Contrast.blend(bg, accent, 0.30)
    fun onContainer(accent: Long, container: Long) = Contrast.ensure(accent, listOf(container), min, text)

    val primary = accent(s.primary)
    val secondary = accent(s.secondary)
    val tertiary = accent(s.tertiary)
    val error = accent(s.error)
    val primaryContainer = container(primary)
    val secondaryContainer = container(secondary)
    val tertiaryContainer = container(tertiary)
    val errorContainer = container(error)
    val onSurfaceVariant = Contrast.ensure(Contrast.blend(text, bg, 0.30), widest, min, text)
    val outline = Contrast.ensure(s.outline, widest, iconMin, text)
    return Roles(
        dark = s.dark, minContrast = min,
        background = bg, onBackground = text, surface = bg, onSurface = text, surfaceVariant = high, onSurfaceVariant = onSurfaceVariant,
        surfaceDim = dim, surfaceBright = bright,
        surfaceContainerLowest = bg, surfaceContainerLow = low, surfaceContainer = container, surfaceContainerHigh = high, surfaceContainerHighest = highest,
        primary = primary, onPrimary = onAccent(primary), primaryContainer = primaryContainer, onPrimaryContainer = onContainer(primary, primaryContainer),
        secondary = secondary, onSecondary = onAccent(secondary), secondaryContainer = secondaryContainer, onSecondaryContainer = onContainer(secondary, secondaryContainer),
        tertiary = tertiary, onTertiary = onAccent(tertiary), tertiaryContainer = tertiaryContainer, onTertiaryContainer = onContainer(tertiary, tertiaryContainer),
        error = error, onError = onAccent(error), errorContainer = errorContainer, onErrorContainer = onContainer(error, errorContainer),
        outline = outline, outlineVariant = Contrast.blend(bg, outline, 0.5),
        inverseSurface = text, inverseOnSurface = bg, inversePrimary = Contrast.ensure(primary, listOf(text), min, bg),
    )
}
