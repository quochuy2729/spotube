package org.spotube.spotube.core.ui.theming

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.spotube.millennium.theme.MillenniumColorSchemes
import org.spotube.millennium.theme.MillenniumPalette
import org.spotube.millennium.theme.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColorOverridesTest {
    private val dark = MillenniumColorSchemes().resolve(MillenniumPalette.Blue, dark = true)
    private val light = MillenniumColorSchemes().resolve(MillenniumPalette.Blue, dark = false)

    @Test
    fun noOverridesLeavesColorsAlone() {
        assertEquals(dark, applyColorOverrides(dark, dark = true, pureBlack = false, accent = null))
    }

    @Test
    fun pureBlackOnlyAffectsDarkTheme() {
        assertEquals(light, applyColorOverrides(light, dark = false, pureBlack = true, accent = null))
        val black = applyColorOverrides(dark, dark = true, pureBlack = true, accent = null)
        assertEquals(Color.Black, black.background)
    }

    @Test
    fun borderStaysVisibleOnBlack() {
        val black = applyColorOverrides(dark, dark = true, pureBlack = true, accent = null)
        assertNotEquals(black.background, black.border)
        assertTrue(black.border.luminance() >= 0.02f)
    }

    @Test
    fun systemAccentPicksReadableOnAccent() {
        val yellow = applyColorOverrides(dark, dark = true, pureBlack = false, accent = Color(0xFFFFEB3B))
        assertEquals(Color.Black, yellow.onAccent)
        val navy = applyColorOverrides(dark, dark = true, pureBlack = false, accent = Color(0xFF0D2A6B))
        assertEquals(Color.White, navy.onAccent)
    }
}
