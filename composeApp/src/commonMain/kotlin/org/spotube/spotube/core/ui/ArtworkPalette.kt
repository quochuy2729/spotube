package org.spotube.spotube.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Two accent colours taken from a cover, used for the now-playing backdrop. */
data class ArtworkPalette(val primary: Color, val secondary: Color)

/**
 * Picks the two most representative colours from ARGB [pixels] (a small thumbnail) with a
 * 4-bit-per-channel histogram. Transparent, near-black, near-white and grey pixels are ignored
 * because they make for a dull backdrop. Returns null when nothing colourful is left.
 */
fun extractArtworkPalette(pixels: IntArray): ArtworkPalette? {
    if (pixels.isEmpty()) return null
    val counts = IntArray(HISTOGRAM_SIZE)
    val rSum = LongArray(HISTOGRAM_SIZE)
    val gSum = LongArray(HISTOGRAM_SIZE)
    val bSum = LongArray(HISTOGRAM_SIZE)
    for (argb in pixels) {
        val a = argb ushr 24 and 0xFF
        if (a < 128) continue
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < NEAR_BLACK || min > NEAR_WHITE) continue
        if ((max - min) < MIN_CHROMA) continue
        val bin = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
        counts[bin]++
        rSum[bin] += r.toLong()
        gSum[bin] += g.toLong()
        bSum[bin] += b.toLong()
    }
    val ranked = counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }
    if (ranked.isEmpty()) return null

    fun colorOf(bin: Int): Color {
        val n = counts[bin]
        return Color(
            red = (rSum[bin] / n).toInt() / 255f,
            green = (gSum[bin] / n).toInt() / 255f,
            blue = (bSum[bin] / n).toInt() / 255f)
    }

    val primary = colorOf(ranked.first())
    val secondary = ranked.drop(1).asSequence().map(::colorOf)
        .firstOrNull { colorDistance(it, primary) >= MIN_SECONDARY_DISTANCE }
        ?: lerp(primary, Color.Black, 0.4f)
    return ArtworkPalette(primary, secondary)
}

/**
 * Blends [tint] over [background] as strongly as possible (up to [maxAlpha]) while [text] keeps
 * at least [minContrast] against the result, so a vivid cover never makes the labels unreadable.
 */
fun blendForContrast(
    tint: Color,
    background: Color,
    text: Color,
    maxAlpha: Float = 0.55f,
    minContrast: Float = 4.5f
): Color {
    var alpha = maxAlpha
    while (alpha > 0f) {
        val blended = lerp(background, tint, alpha)
        if (contrastRatio(text, blended) >= minContrast) return blended
        alpha -= 0.05f
    }
    return background
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
}

private const val HISTOGRAM_SIZE = 4096
private const val NEAR_BLACK = 40
private const val NEAR_WHITE = 225
private const val MIN_CHROMA = 30
private const val MIN_SECONDARY_DISTANCE = 0.25f
