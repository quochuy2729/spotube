package org.spotube.spotube.modules.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.jam.JamRole
import org.spotube.spotube.core.jam.JamRoomService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class SeekOverlayDirection { Back, Forward }

/** [sequence] changes on every double tap so repeated taps restart the overlay animation. */
data class SeekOverlay(val direction: SeekOverlayDirection, val sequence: Int)

data class PlayerGestureUiState(
    /** False for Jam guests: the shared queue and playback position belong to the host. */
    val gesturesEnabled: Boolean = true,
    val seekOverlay: SeekOverlay? = null)

class PlayerGestureViewModel(
    private val audioPlayer: AudioPlayerInterface,
    jamRoomService: JamRoomService) : ViewModel() {
    private val overlay = MutableStateFlow<SeekOverlay?>(null)
    private val isGuest: StateFlow<Boolean> = jamRoomService.role.map { it == JamRole.Guest }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var overlayJob: Job? = null
    private var sequence = 0

    val uiState: StateFlow<PlayerGestureUiState> = combine(isGuest, overlay) { guest, shown ->
        PlayerGestureUiState(gesturesEnabled = !guest, seekOverlay = shown)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerGestureUiState())

    fun onSwipe(swipe: PlayerSwipe) {
        if (isGuest.value) return
        viewModelScope.launch {
            when (swipe) {
                PlayerSwipe.Next -> audioPlayer.skipToNext()
                PlayerSwipe.Previous -> audioPlayer.skipToPrevious()
            }
        }
    }

    /** [xFraction] is the tap position across the artwork: left half rewinds, right half forwards. */
    fun onDoubleTap(xFraction: Float) {
        if (isGuest.value) return
        val direction = if (xFraction < 0.5f) SeekOverlayDirection.Back else SeekOverlayDirection.Forward
        viewModelScope.launch {
            val target = seekTarget(
                position = audioPlayer.positionFlow.value,
                duration = audioPlayer.durationFlow.value,
                forward = direction == SeekOverlayDirection.Forward)
            audioPlayer.seekTo(target)
        }
        overlay.value = SeekOverlay(direction, ++sequence)
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(OVERLAY_VISIBLE_MS.milliseconds)
            overlay.value = null
        }
    }

    companion object {
        const val OVERLAY_VISIBLE_MS = 700L
        val SEEK_STEP: Duration = 10.seconds
    }
}

/** Position after a 10 s jump, kept inside the track when its duration is known. */
internal fun seekTarget(position: Duration, duration: Duration, forward: Boolean): Duration {
    val moved = if (forward) position + PlayerGestureViewModel.SEEK_STEP else position - PlayerGestureViewModel.SEEK_STEP
    val upper = if (duration > Duration.ZERO) duration else moved
    return moved.coerceIn(Duration.ZERO, maxOf(upper, Duration.ZERO))
}
