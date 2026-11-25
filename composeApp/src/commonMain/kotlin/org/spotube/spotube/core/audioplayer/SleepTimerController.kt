package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SleepTimerState(
    /** Time left for a countdown timer, null when no countdown is running. */
    val remainingMs: Long? = null,
    /** True while the timer waits for the current track to finish. */
    val untilTrackEnd: Boolean = false
) {
    val isActive: Boolean get() = remainingMs != null || untilTrackEnd
}

/**
 * App-wide sleep timer. Lives in Koin as a singleton (not in a ViewModel) so it keeps counting
 * when the Activity or its ViewModelStore is destroyed. The volume is faded out during the last
 * [FADE_DURATION_MS] and is always restored to its original value afterwards, including when the
 * timer is cancelled.
 */
class SleepTimerController(
    private val audioPlayer: AudioPlayerInterface,
    private val audioPlayerQueue: AudioPlayerQueue,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null
    private var originalVolume: Float? = null

    fun startCountdown(minutes: Long) {
        if (minutes !in 1..MAX_MINUTES) return
        launchTimer {
            var remainingMs = minutes * MILLIS_PER_MINUTE
            while (remainingMs > 0L) {
                _state.value = SleepTimerState(remainingMs = remainingMs)
                applyFade(remainingMs)
                val tick = minOf(remainingMs, TICK_MS)
                delay(tick)
                remainingMs -= tick
            }
            finish()
        }
    }

    fun startUntilTrackEnd() {
        launchTimer {
            _state.value = SleepTimerState(untilTrackEnd = true)
            val startEntry = audioPlayerQueue.currentQueueEntryFlow.value
            var lastPosition = -1L
            // Pause once the position reaches the end of the track, or as soon as the queue moves on.
            while (true) {
                val entryChanged = audioPlayerQueue.currentQueueEntryFlow.value != startEntry
                val duration = audioPlayer.durationFlow.value.inWholeMilliseconds
                val position = audioPlayer.positionFlow.value.inWholeMilliseconds
                // Position is sampled sparsely (and faster than real time above 1x), so the end
                // window can be skipped: with repeat-one (same entry) the track would then restart
                // and the timer never fire. A jump back from close to the end counts as the end.
                val speed = audioPlayer.playbackSpeedFlow.value.coerceAtLeast(1f)
                val margin = (END_MARGIN_MS * speed).toLong()
                val nearEnd = duration > 0L && duration - position <= margin
                val wrapped = duration > 0L && lastPosition >= 0L && position < lastPosition &&
                    duration - lastPosition <= (WRAP_WINDOW_MS * speed).toLong()
                if (entryChanged || nearEnd || wrapped) break
                lastPosition = position
                delay(END_POLL_MS)
            }
            finish()
        }
    }

    fun cancel() {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            _state.value = SleepTimerState()
            restoreVolume()
        }
    }

    private fun launchTimer(block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            // Wait for the previous run (and its volume restore) before touching the player again.
            previous?.cancelAndJoin()
            restoreVolume()
            block()
        }
    }

    private suspend fun applyFade(remainingMs: Long) {
        if (remainingMs > FADE_DURATION_MS) return
        val base = originalVolume ?: audioPlayer.volumeFlow.value.also { originalVolume = it }
        val factor = (remainingMs.toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
        runCatching { audioPlayer.setVolume(base * factor) }
    }

    private suspend fun finish() {
        runCatching { audioPlayer.pause() }
        restoreVolume()
        _state.value = SleepTimerState()
    }

    private suspend fun restoreVolume() {
        val volume = originalVolume ?: return
        originalVolume = null
        runCatching { audioPlayer.setVolume(volume) }
    }

    companion object {
        const val MAX_MINUTES = 600L
        const val FADE_DURATION_MS = 30_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val TICK_MS = 1_000L
        private const val END_POLL_MS = 250L
        private const val END_MARGIN_MS = 400L
        private const val WRAP_WINDOW_MS = 1_500L
    }
}
