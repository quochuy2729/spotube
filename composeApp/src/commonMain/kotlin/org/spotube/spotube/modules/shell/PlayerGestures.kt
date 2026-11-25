package org.spotube.spotube.modules.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class PlayerSwipe { Next, Previous }

/** Fraction of the width a horizontal drag must cover before it counts as a skip. */
const val PLAYER_SWIPE_THRESHOLD = 0.3f

/** Swiping left brings the next track, swiping right the previous one. */
fun resolvePlayerSwipe(
    offsetPx: Float,
    widthPx: Float,
    threshold: Float = PLAYER_SWIPE_THRESHOLD
): PlayerSwipe? {
    if (widthPx <= 0f) return null
    val fraction = offsetPx / widthPx
    return when {
        fraction <= -threshold -> PlayerSwipe.Next
        fraction >= threshold -> PlayerSwipe.Previous
        else -> null
    }
}

/** Holds the horizontal offset the artwork follows while the user drags it. */
class PlayerSwipeState(internal val scope: CoroutineScope) {
    internal val offset = Animatable(0f)
    internal var widthPx = 0f
    internal val draggableState = DraggableState { delta -> dragBy(delta) }

    internal suspend fun finish(enabled: Boolean, onSwipe: (PlayerSwipe) -> Unit) {
        val swipe = if (enabled) resolvePlayerSwipe(offset.value, widthPx) else null
        if (swipe == null) {
            offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            return
        }
        val exit = if (swipe == PlayerSwipe.Next) -widthPx else widthPx
        offset.animateTo(exit, tween(120))
        onSwipe(swipe)
        // The new track slides in from the opposite side and settles with a small bounce.
        offset.snapTo(-exit)
        offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }

    internal fun dragBy(delta: Float) {
        scope.launch { offset.snapTo(offset.value + delta) }
    }
}

@Composable
fun rememberPlayerSwipeState(): PlayerSwipeState {
    val scope = rememberCoroutineScope()
    return remember { PlayerSwipeState(scope) }
}

/**
 * Horizontal drag that only claims horizontal movement, so the vertical drag of the player sheet
 * keeps working. [onSwipe] fires once the drag passes [PLAYER_SWIPE_THRESHOLD].
 */
fun Modifier.playerHorizontalSwipe(
    state: PlayerSwipeState,
    enabled: Boolean,
    onSwipe: (PlayerSwipe) -> Unit
): Modifier = this
    .onSizeChanged { state.widthPx = it.width.toFloat() }
    .draggable(
        state = state.draggableState,
        orientation = Orientation.Horizontal,
        enabled = enabled,
        onDragStopped = { state.scope.launch { state.finish(enabled, onSwipe) } }
    )

/** Applies the drag offset to the content (not the gesture area). */
fun Modifier.playerSwipeOffset(state: PlayerSwipeState): Modifier =
    graphicsLayer { translationX = state.offset.value }

/** Double tap: x is the tap position as a fraction (0..1) of the width. */
fun Modifier.playerDoubleTapSeek(enabled: Boolean, onDoubleTap: (Float) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap(it.x / size.width.toFloat().coerceAtLeast(1f)) })
    }
