package org.spotube.spotube.modules.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import org.koin.compose.viewmodel.koinViewModel
import org.spotube.spotube.core.ui.ArtworkPixelLoader
import org.spotube.spotube.core.ui.blendForContrast
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.core.ui.theming.isBlurSupported

/**
 * Cover-coloured backdrop for the expanded player: a vertical gradient from the cover palette to
 * the theme background, plus the blurred cover at low alpha where blur is supported. Colours are
 * blended so the theme's text colour always keeps its contrast.
 */
@Composable
fun NowPlayingBackdrop(
    modifier: Modifier = Modifier,
    viewModel: NowPlayingVisualsViewModel = koinViewModel()
) {
    val context = LocalPlatformContext.current
    remember(viewModel, context) {
        viewModel.attachPixelLoader(ArtworkPixelLoader(SingletonImageLoader.get(context), context))
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val background = AppTheme.colors.background
    val text = AppTheme.colors.onBackground
    val palette = state.palette
    val topTarget = palette?.let { blendForContrast(it.primary, background, text) } ?: background
    val bottomTarget = palette?.let { blendForContrast(it.secondary, background, text, maxAlpha = 0.35f) } ?: background
    val top by animateColorAsState(topTarget, tween(600), label = "backdropTop")
    val bottom by animateColorAsState(bottomTarget, tween(600), label = "backdropBottom")

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Brush.verticalGradient(listOf(top, bottom, background))) }
        )
        val backdropModel = state.backdropModel
        if (backdropModel != null && isBlurSupported()) {
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.18f)
                    .blur(48.dp),
                contentScale = ContentScale.Crop
            )
        }
    }
}
