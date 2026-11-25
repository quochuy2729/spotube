package org.spotube.spotube.core.ui.theming

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun systemAccentColor(): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return remember(context.resources.configuration) {
        Color(context.resources.getColor(android.R.color.system_accent1_500, context.theme))
    }
}

actual fun isBlurSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
