package org.spotube.spotube.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkPaletteTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun ignoresBlackWhiteGreyAndTransparent() {
        val pixels = IntArray(100) { argb(0, 0, 0) } +
            IntArray(100) { argb(255, 255, 255) } +
            IntArray(100) { argb(128, 128, 128) } +
            IntArray(100) { argb(200, 20, 20, a = 10) }
        assertNull(extractArtworkPalette(pixels))
    }

    @Test
    fun picksTheDominantColour() {
        val pixels = IntArray(300) { argb(200, 30, 30) } + IntArray(50) { argb(30, 30, 200) } +
            IntArray(500) { argb(0, 0, 0) }
        val palette = assertNotNull(extractArtworkPalette(pixels))
        assertTrue(palette.primary.red > 0.7f && palette.primary.blue < 0.3f)
        assertTrue(palette.secondary.blue > 0.6f)
    }

    @Test
    fun singleColourGetsDarkerSecondary() {
        val palette = assertNotNull(extractArtworkPalette(IntArray(64) { argb(30, 160, 60) }))
        assertTrue(palette.secondary.green < palette.primary.green)
    }

    @Test
    fun blendKeepsTextReadable() {
        val background = Color(0xFF121212)
        val text = Color.White
        val blended = blendForContrast(Color(0xFFFFEB3B), background, text)
        assertTrue(contrastRatio(text, blended) >= 4.5f)
    }

    @Test
    fun blendFallsBackToBackgroundWhenImpossible() {
        assertEquals(Color.White, blendForContrast(Color.Yellow, Color.White, Color.White))
    }
}
