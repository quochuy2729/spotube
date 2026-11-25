/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer.trim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.LoopState
import org.spotube.spotube.core.audioplayer.PlayerState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Enforces saved trims during playback: jumps over the cut intro and moves on when the cut tail is
 * reached. App-wide singleton so it also works while the app is in the background.
 *
 * Player position and the current queue entry are published independently, so right after a track
 * change the position may still belong to the previous track. Two guards cover that: nothing happens
 * on the first tick of a new entry, and the end cut only fires after a position before it was seen.
 */
class TrackTrimController(
    private val audioPlayer: AudioPlayerInterface,
    private val audioPlayerQueue: AudioPlayerQueue,
    private val trims: StateFlow<Map<String, TrackTrim>>,
    /** True while somebody else owns the playback position (Jam guest). */
    private val isBlocked: () -> Boolean = { false },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {

    private var key: String? = null
    private var trim: TrackTrim? = null
    private var endArmed = false
    private var startSeekCooldown = 0

    init {
        // currentQueueEntryFlow only updates while collected.
        scope.launch { audioPlayerQueue.currentQueueEntryFlow.collect {} }
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(POLL_MS)
            }
        }
    }

    private suspend fun tick() {
        val currentKey = audioPlayerQueue.currentQueueEntryFlow.value?.trimKey
        val currentTrim = currentKey?.let { trims.value[it] }
        if (currentKey != key) {
            key = currentKey
            trim = currentTrim
            endArmed = false
            startSeekCooldown = 0
            return
        }
        // Edited while this track plays: apply right away, even if already inside the cut part.
        if (currentTrim != trim) {
            trim = currentTrim
            endArmed = currentTrim?.endMs != null
            startSeekCooldown = 0
        }
        if (currentTrim == null || isBlocked()) return
        if (audioPlayer.playerStateFlow.value != PlayerState.PLAYING) return

        val duration = audioPlayer.durationFlow.value.inWholeMilliseconds
        val position = audioPlayer.positionFlow.value.inWholeMilliseconds
        if (duration <= 0L) return
        // A trim outside the audio (e.g. a different source was picked) is ignored.
        val start = currentTrim.startMs.takeIf { it < duration } ?: 0L
        val end = currentTrim.endMs?.takeIf { it < duration && it > start }

        if (startSeekCooldown > 0) startSeekCooldown--
        if (position < start - START_TOLERANCE_MS) {
            // The player may drop a seek while still buffering, so retry after a pause.
            if (startSeekCooldown == 0) {
                startSeekCooldown = SEEK_RETRY_TICKS
                audioPlayer.seekTo(start.milliseconds)
            }
            return
        }

        if (end == null) return
        if (position < end) {
            endArmed = true
        } else if (endArmed) {
            endArmed = false
            finishTrack(start.milliseconds)
        }
    }

    private suspend fun finishTrack(start: Duration) {
        val loop = audioPlayer.loopStateFlow.value
        if (loop == LoopState.ONE) {
            audioPlayer.seekTo(start)
            return
        }
        val playlist = audioPlayer.playlistFlow.value
        val currentUrl = audioPlayer.currentMediaItemFlow.value?.url
        val index = playlist.indexOfFirst { it.url == currentUrl }
        when {
            index >= 0 && index < playlist.lastIndex -> audioPlayer.skipToNext()
            loop == LoopState.ALL && playlist.size > 1 -> audioPlayer.jumpTo(0)
            loop == LoopState.ALL -> audioPlayer.seekTo(start)
            else -> {
                audioPlayer.pause()
                audioPlayer.seekTo(start)
            }
        }
    }

    private companion object {
        const val POLL_MS = 200L
        const val START_TOLERANCE_MS = 500L
        const val SEEK_RETRY_TICKS = 10
    }
}
