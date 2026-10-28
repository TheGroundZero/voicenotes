// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSchemesTest {
    private val named = mapOf(
        "paper" to ThemeSeeds.paper, "high contrast light" to ThemeSeeds.highContrastLight,
        "high contrast dark" to ThemeSeeds.highContrastDark, "matrix" to ThemeSeeds.matrix, "dracula" to ThemeSeeds.dracula,
    )

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    private fun check(failures: MutableList<String>, scheme: String, what: String, fg: Long, bg: Long, min: Double) {
        val ratio = Contrast.ratio(fg, bg)
        if (ratio < min) failures += "$scheme: $what ${hex(fg)} on ${hex(bg)} is %.2f:1, needs %.1f:1".format(ratio, min)
    }

    @Test fun contrastMatchesTheKnownWcagValues() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.001)
        assertEquals(1.0, Contrast.ratio(0xFF336699, 0xFF336699), 0.001)
        assertEquals(13.4, Contrast.ratio(0xFFF8F8F2, 0xFF282A36), 0.1)   // Dracula's text on its background
        assertEquals(3.0, Contrast.ratio(0xFF6272A4, 0xFF282A36), 0.1)    // Dracula's own "comment" colour: too weak for text
    }

    @Test fun blendingGoesFromOneColourToTheOther() {
        assertEquals(0xFF000000, Contrast.blend(0xFF000000, 0xFFFFFFFF, 0.0))
        assertEquals(0xFFFFFFFF, Contrast.blend(0xFF000000, 0xFFFFFFFF, 1.0))
        assertEquals(0xFF808080, Contrast.blend(0xFF000000, 0xFFFFFFFF, 0.5))
    }

    @Test fun ensureLeavesAReadableColourAloneAndFixesAWeakOne() {
        val bg = 0xFF282A36
        assertEquals(0xFFF8F8F2, Contrast.ensure(0xFFF8F8F2, listOf(bg), 4.5, 0xFFFFFFFF))
        val fixed = Contrast.ensure(0xFF6272A4, listOf(bg), 4.5, 0xFFF8F8F2)
        assertTrue(Contrast.ratio(fixed, bg) >= 4.5)
    }

    // Every colour that carries text is readable on every surface it can sit on; icons, outlines and accents have their own limits.
    @Test fun everyThemeIsReadable() {
        val failures = mutableListOf<String>()
        named.forEach { (name, seeds) ->
            val r = derive(seeds)
            val min = r.minContrast
            val iconMin = if (min >= 7.0) 4.5 else 3.0
            r.surfaces.forEach { surface ->
                check(failures, name, "onSurface", r.onSurface, surface, min)
                check(failures, name, "onSurfaceVariant", r.onSurfaceVariant, surface, min)
                check(failures, name, "primary as text", r.primary, surface, min)
                check(failures, name, "secondary as text", r.secondary, surface, min)
                check(failures, name, "tertiary as text", r.tertiary, surface, min)
                check(failures, name, "error as text", r.error, surface, min)
                check(failures, name, "outline", r.outline, surface, iconMin)
            }
            check(failures, name, "onBackground", r.onBackground, r.background, min)
            check(failures, name, "onPrimary", r.onPrimary, r.primary, min)
            check(failures, name, "onSecondary", r.onSecondary, r.secondary, min)
            check(failures, name, "onTertiary", r.onTertiary, r.tertiary, min)
            check(failures, name, "onError", r.onError, r.error, min)
            check(failures, name, "onPrimaryContainer", r.onPrimaryContainer, r.primaryContainer, min)
            check(failures, name, "onSecondaryContainer", r.onSecondaryContainer, r.secondaryContainer, min)
            check(failures, name, "onTertiaryContainer", r.onTertiaryContainer, r.tertiaryContainer, min)
            check(failures, name, "onErrorContainer", r.onErrorContainer, r.errorContainer, min)
            check(failures, name, "inverseOnSurface", r.inverseOnSurface, r.inverseSurface, min)
            check(failures, name, "inversePrimary", r.inversePrimary, r.inverseSurface, min)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // Deriving may lighten or darken an accent to make it readable, but a palette that is readable already must come out unchanged.
    @Test fun readableAccentsAreNotChanged() {
        val paper = derive(ThemeSeeds.paper)
        assertEquals(ThemeSeeds.paper.primary, paper.primary)
        assertEquals(ThemeSeeds.paper.secondary, paper.secondary)
        assertEquals(ThemeSeeds.paper.error, paper.error)
        val matrix = derive(ThemeSeeds.matrix)
        assertEquals(ThemeSeeds.matrix.primary, matrix.primary)
        assertEquals(ThemeSeeds.matrix.error, matrix.error)
        assertEquals(ThemeSeeds.highContrastDark.primary, derive(ThemeSeeds.highContrastDark).primary)
        assertEquals(ThemeSeeds.highContrastLight.primary, derive(ThemeSeeds.highContrastLight).primary)
        assertEquals(ThemeSeeds.dracula.tertiary, derive(ThemeSeeds.dracula).tertiary)
    }

    @Test fun anAdjustedAccentKeepsItsHue() {
        // Dracula's purple, pink and red are lightened, but each stays recognisably the same colour: the dominant channel is unchanged.
        fun dominant(argb: Long) = listOf(16, 8, 0).maxByOrNull { (argb shr it) and 0xFF }
        val d = derive(ThemeSeeds.dracula)
        assertEquals(dominant(ThemeSeeds.dracula.error), dominant(d.error))
        assertEquals(dominant(ThemeSeeds.dracula.secondary), dominant(d.secondary))
        assertEquals(dominant(ThemeSeeds.dracula.primary), dominant(d.primary))
    }

    @Test fun lightThemesHaveDarkTextAndDarkThemesLightText() {
        named.forEach { (name, seeds) ->
            val r = derive(seeds)
            assertEquals(name, seeds.dark, Contrast.luminance(r.background) < 0.5)
            assertEquals(name, seeds.dark, Contrast.luminance(r.onSurface) > Contrast.luminance(r.surface))
        }
    }

    @Test fun everyThemeIdRoundTripsAndUnknownOnesMeanSystem() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromId(it.id)) }
        assertEquals(AppTheme.SYSTEM, AppTheme.fromId(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromId("solarized"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromId(""))
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.id }.toSet().size)
    }

    @Test fun onlyTheFixedThemesHaveSeeds() {
        assertTrue(AppTheme.SYSTEM.seeds(false) == null && AppTheme.LIGHT.seeds(true) == null && AppTheme.DARK.seeds(false) == null)
        assertTrue(AppTheme.entries.filter { it !in listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK) }.all { it.seeds(false) != null })
        assertEquals(false, AppTheme.HIGH_CONTRAST.seeds(systemDark = false)!!.dark)
        assertEquals(true, AppTheme.HIGH_CONTRAST.seeds(systemDark = true)!!.dark)
    }
}
