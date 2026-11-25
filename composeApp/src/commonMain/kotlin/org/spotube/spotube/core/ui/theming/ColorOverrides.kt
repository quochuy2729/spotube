package org.spotube.spotube.core.ui.theming

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.spotube.millennium.theme.MillenniumColors

/**
 * Applies the user's optional AMOLED / system-accent overrides on top of a resolved Millennium
 * colour set. Returns [base] untouched when no override applies.
 */
fun applyColorOverrides(
    base: MillenniumColors,
    dark: Boolean,
    pureBlack: Boolean,
    accent: Color?
): MillenniumColors {
    var colors = base
    if (accent != null) {
        colors = colors.copy(
            accent = accent,
            accentTop = lerp(accent, Color.White, 0.12f),
            accentBottom = lerp(accent, Color.Black, 0.12f),
            onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        )
    }
    if (pureBlack && dark) {
        colors = colors.copy(
            background = Color.Black,
            surface = lerp(colors.surface, Color.Black, 0.7f),
            raised = lerp(colors.raised, Color.Black, 0.5f),
            inset = Color.Black,
            // Panels are separated from the black background by their border, so keep it visible.
            border = visibleBorderOnBlack(colors.border)
        )
    }
    return colors
}

private fun visibleBorderOnBlack(border: Color): Color {
    val lifted = lerp(border, Color.White, 0.08f)
    return if (lifted.luminance() < MIN_BORDER_LUMINANCE) Color(0xFF2E2E2E) else lifted
}

private const val MIN_BORDER_LUMINANCE = 0.02f
