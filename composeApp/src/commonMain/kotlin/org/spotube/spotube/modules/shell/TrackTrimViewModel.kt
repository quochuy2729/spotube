/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.core.audioplayer.trim.TrackTrim
import org.spotube.spotube.core.audioplayer.trim.TrackTrimRepository
import org.spotube.spotube.core.audioplayer.trim.trimKey

data class TrackTrimUiState(
    val key: String,
    val title: String,
    val durationMs: Long,
    val startMs: Long,
    /** Equal to [durationMs] when the tail is not cut. */
    val endMs: Long,
    /** Null when another track is playing now, so its position must not be used. */
    val positionMs: Long? = null,
    val hasSavedTrim: Boolean = false)

class TrackTrimViewModel(
    private val audioPlayer: AudioPlayerInterface,
    private val audioPlayerQueue: AudioPlayerQueue,
    private val repository: TrackTrimRepository) : ViewModel() {
    private val draft = MutableStateFlow<TrackTrimUiState?>(null)

    /** Null while the editor is closed. */
    val uiState: StateFlow<TrackTrimUiState?> = combine(
        draft,
        audioPlayerQueue.currentQueueEntryFlow,
        audioPlayer.positionFlow) { state, entry, position ->
        state?.copy(positionMs = position.inWholeMilliseconds.takeIf { entry?.trimKey == state.key })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open() {
        val entry = audioPlayerQueue.currentQueueEntryFlow.value ?: return
        val metadataDuration = when (entry) {
            is QueueEntry.StreamingTrack -> entry.track.durationMs
            is QueueEntry.LocalTrack -> entry.duration
        }
        // The real audio can be longer than the metadata says (e.g. a video with an ad at the end).
        val duration = audioPlayer.durationFlow.value.inWholeMilliseconds.takeIf { it > 0L }
            ?: metadataDuration
        if (duration <= 0L) return
        val key = entry.trimKey
        val saved = repository.trims.value[key]
        val start = (saved?.startMs ?: 0L).coerceIn(0L, (duration - MIN_LENGTH_MS).coerceAtLeast(0L))
        draft.value = TrackTrimUiState(
            key = key,
            title = when (entry) {
                is QueueEntry.StreamingTrack -> entry.track.title
                is QueueEntry.LocalTrack -> entry.name
            },
            durationMs = duration,
            startMs = start,
            endMs = (saved?.endMs ?: duration).coerceIn(minOf(start + MIN_LENGTH_MS, duration), duration),
            hasSavedTrim = saved != null)
    }

    fun adjustStart(deltaMs: Long) = update { it.withStart(it.startMs + deltaMs) }

    fun adjustEnd(deltaMs: Long) = update { it.withEnd(it.endMs + deltaMs) }

    fun startAtCurrentPosition() {
        val position = uiState.value?.positionMs ?: return
        update { it.withStart(position) }
    }

    fun endAtCurrentPosition() {
        val position = uiState.value?.positionMs ?: return
        update { it.withEnd(position) }
    }

    fun save() {
        val state = draft.value ?: return
        persist(
            state.key,
            TrackTrim(startMs = state.startMs, endMs = state.endMs.takeIf { it < state.durationMs }))
    }

    fun reset() {
        val state = draft.value ?: return
        persist(state.key, TrackTrim())
    }

    fun dismiss() {
        draft.value = null
    }

    private fun persist(key: String, trim: TrackTrim) {
        draft.value = null
        viewModelScope.launch { runCatching { repository.set(key, trim) } }
    }

    private fun update(change: (TrackTrimUiState) -> TrackTrimUiState) {
        draft.update { it?.let(change) }
    }

    private fun TrackTrimUiState.withStart(value: Long) =
        copy(startMs = value.coerceIn(0L, (endMs - MIN_LENGTH_MS).coerceAtLeast(0L)))

    private fun TrackTrimUiState.withEnd(value: Long) =
        copy(endMs = value.coerceIn(minOf(startMs + MIN_LENGTH_MS, durationMs), durationMs))

    private companion object {
        const val MIN_LENGTH_MS = 1_000L
    }
}
