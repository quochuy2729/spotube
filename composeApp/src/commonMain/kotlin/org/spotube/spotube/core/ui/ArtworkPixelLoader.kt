package org.spotube.spotube.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult

/** Android must not return hardware bitmaps here: they cannot be drawn into a software canvas. */
internal expect fun ImageRequest.Builder.softwareBitmapsOnly(): ImageRequest.Builder

/**
 * Loads any artwork model Coil understands (URL, local cover bytes) at thumbnail size and returns
 * its ARGB pixels. It reuses the app's Coil loader, so network, cache and decoding match the UI.
 */
class ArtworkPixelLoader(
    private val imageLoader: ImageLoader,
    private val context: PlatformContext
) {
    suspend fun load(model: Any, side: Int = THUMBNAIL_SIDE): IntArray? {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(side, side)
            .softwareBitmapsOnly()
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        val painter = result.image.asPainter(context)
        val bitmap = ImageBitmap(side, side)
        draw(painter, bitmap, side)
        val map = bitmap.toPixelMap()
        return IntArray(side * side) { map.buffer[it] }
    }

    private fun draw(painter: Painter, bitmap: ImageBitmap, side: Int) {
        val size = Size(side.toFloat(), side.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
            with(painter) { draw(size) }
        }
    }

    companion object {
        const val THUMBNAIL_SIDE = 32
    }
}
