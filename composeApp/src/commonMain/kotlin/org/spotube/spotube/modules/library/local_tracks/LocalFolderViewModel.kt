/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.getString
import org.spotube.spotube.core.ui.AppMessages
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.library_local_delete_failed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.PlayerState
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaCache
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaLibrary
import org.spotube.spotube.modules.library.local_tracks.media.LocalTrackDeleteResult
import org.spotube.spotube.modules.library.local_tracks.media.LocalTrackDeleter
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaTrack

class LocalFolderViewModel(
    private val folderPath: String,
    private val library: LocalMediaLibrary,
    private val audioPlayerQueue: AudioPlayerQueue,
    private val audioPlayer: AudioPlayerInterface,
    private val deleter: LocalTrackDeleter,
    private val messages: AppMessages
) : ViewModel() {
    private val _pendingDelete = MutableStateFlow<LocalTrackUi?>(null)

    /** Track waiting for the user's confirmation; nothing is deleted until [confirmDelete]. */
    val pendingDelete: StateFlow<LocalTrackUi?> = _pendingDelete.asStateFlow()
    private var deleting = false

    /** Whether confirming removes the file for good (no trash); the dialog wording depends on it. */
    val deleteIsPermanent: Boolean get() = deleter.isPermanent

    private val playOrderMode = MutableStateFlow(false)
    private val reorderBuffer = MutableStateFlow<List<String>?>(null)
    private var playOrderSeen = false
    private var dragQueueIndices: List<Int> = emptyList()
    private var dragFrom: Int? = null
    private var dragTo: Int? = null
    private var dragActive = false
    private var dragStartOrder: List<Pair<Int, String>> = emptyList()

    val state: StateFlow<LibraryLocalTracksState> = combine(
        listOf(
            library.cacheState,
            audioPlayerQueue.queueFlow,
            audioPlayerQueue.currentQueueEntryFlow,
            audioPlayer.playerStateFlow,
            playOrderMode,
            reorderBuffer
        )
    ) { values ->
        val cache = values[0] as LocalMediaCache
        @Suppress("UNCHECKED_CAST")
        val queue = values[1] as List<QueueEntry>
        val currentEntry = values[2] as QueueEntry?
        val playerState = values[3] as PlayerState
        val mode = values[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val buffer = values[5] as List<String>?
        val ui = localTracksUiState(
            LibraryLocalTracksState(folders = cache.folders, currentFolderPath = folderPath),
            ""
        )
        val ordered = if (mode) orderedQueue(queue, ui.currentFolder?.tracks.orEmpty()) else emptyList()
        if (mode) {
            if (ordered.isNotEmpty()) playOrderSeen = true
            // The queue no longer holds this folder (something else was played): leave play-order mode.
            else if (playOrderSeen) {
                playOrderSeen = false
                playOrderMode.value = false
            }
        }
        val playOrder = mode && ordered.isNotEmpty()
        val playingUrl = (currentEntry as? QueueEntry.LocalTrack)?.url
            ?.takeIf { playerState == PlayerState.PLAYING }
        var tracksUi = ui.visibleTracks
        if (playOrder) {
            val byPath = tracksUi.associateBy { it.path }
            val queuePaths = ordered.map { it.second }
            val paths = buffer?.takeIf { it.toSet() == queuePaths.toSet() } ?: queuePaths
            tracksUi = paths.mapNotNull { byPath[it] }
        }
        ui.copy(
            visibleTracks = tracksUi.map { it.copy(isPlaying = it.path == playingUrl) },
            isPlayOrder = playOrder
        )
    }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            localTracksUiState(LibraryLocalTracksState(currentFolderPath = folderPath), "")
        )

    /** Queue positions (index, path) of this folder's tracks in play order, each path once. */
    private fun orderedQueue(queue: List<QueueEntry>, folderTracks: List<LocalMediaTrack>): List<Pair<Int, String>> {
        val paths = folderTracks.mapTo(HashSet()) { it.path }
        val seen = HashSet<String>()
        return queue.withIndex().mapNotNull { (i, e) ->
            val url = (e as? QueueEntry.LocalTrack)?.url
            if (url != null && url in paths && seen.add(url)) i to url else null
        }
    }

    /** Leaves play-order mode and goes back to the normal folder order. */
    fun exitPlayOrder() {
        dragActive = false
        playOrderSeen = false
        playOrderMode.value = false
        reorderBuffer.value = null
    }

    fun onDragStart() {
        // A drag that starts while the previous drop is still being applied is ignored entirely
        // (its onMove/onDragStop too), so it can never act on the previous drag's indices.
        if (!state.value.isPlayOrder || reorderBuffer.value != null) return
        val ordered = orderedQueue(audioPlayerQueue.queueFlow.value, tracks())
        dragStartOrder = ordered
        dragQueueIndices = ordered.map { it.first }
        dragFrom = null
        dragTo = null
        dragActive = true
        reorderBuffer.value = ordered.map { it.second }
    }

    /** Called while dragging with display indices; only the buffered list changes until [onDragStop]. */
    fun onMove(from: Int, to: Int) {
        if (!dragActive) return
        val buffer = reorderBuffer.value ?: return
        if (from == to || from !in buffer.indices || to !in buffer.indices) return
        reorderBuffer.value = buffer.toMutableList().apply { add(to, removeAt(from)) }
        if (dragFrom == null) dragFrom = from
        dragTo = to
    }

    fun onDragStop() {
        if (!dragActive) return
        dragActive = false
        // The queue changed during the drag (track deleted, queue replaced...): the saved queue
        // indices are stale, so drop the move instead of moving the wrong entry.
        val unchanged = orderedQueue(audioPlayerQueue.queueFlow.value, tracks()) == dragStartOrder
        val from = dragFrom?.let { dragQueueIndices.getOrNull(it) }?.takeIf { unchanged }
        val to = dragTo?.let { dragQueueIndices.getOrNull(it) }?.takeIf { unchanged }
        dragFrom = null
        dragTo = null
        viewModelScope.launch {
            try {
                if (from != null && to != null && from != to) audioPlayerQueue.move(from, to)
            } finally {
                reorderBuffer.value = null
            }
        }
    }

    fun playFolder() {
        exitPlayOrder()
        play(0)
    }

    /**
     * Plays the whole folder in a random order, starting from the first shuffled track; the list then
     * follows the live queue order ("play order") and can be reordered by dragging.
     */
    fun shuffleFolder() = play(0, shuffle = true)

    /** Tapping the current track toggles play/pause, any other track starts playing from there. */
    fun onTrackClick(trackPath: String) {
        val current = (audioPlayerQueue.currentQueueEntryFlow.value as? QueueEntry.LocalTrack)?.url
        if (current != trackPath) {
            playTrack(trackPath)
            return
        }
        viewModelScope.launch {
            if (audioPlayer.playerStateFlow.value == PlayerState.PLAYING) audioPlayer.pause()
            else audioPlayer.play()
        }
    }

    fun addToQueue(trackPath: String) {
        val track = tracks().firstOrNull { it.path == trackPath } ?: return
        viewModelScope.launch { audioPlayerQueue.addToQueue(track.toQueueEntry()) }
    }

    fun playTrack(trackPath: String) {
        if (state.value.isPlayOrder) {
            // Keep the current (possibly shuffled) order: jump inside the queue instead of reloading it.
            val queueIndex = audioPlayerQueue.queueFlow.value.indexOfFirst {
                (it as? QueueEntry.LocalTrack)?.url == trackPath
            }
            if (queueIndex >= 0) {
                viewModelScope.launch { audioPlayerQueue.jumpTo(queueIndex, autoPlay = true) }
                return
            }
        }
        val index = tracks().indexOfFirst { it.path == trackPath }
        if (index >= 0) play(index)
    }

    fun requestDelete(trackPath: String) {
        if (deleting) return
        _pendingDelete.value = state.value.visibleTracks.firstOrNull { it.path == trackPath }
    }

    fun cancelDelete() {
        if (!deleting) _pendingDelete.value = null
    }

    fun confirmDelete() {
        val target = _pendingDelete.value ?: return
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            try {
                when (val result = deleter.delete(target.path)) {
                    LocalTrackDeleteResult.Deleted -> {
                        // removeFromQueueByMediaUrl drops one copy per call (Android player); the same
                        // track queued twice would otherwise leave an entry pointing at a deleted file.
                        val copies = audioPlayerQueue.queueFlow.value
                            .count { (it as? QueueEntry.LocalTrack)?.url == target.path }
                        repeat(copies.coerceAtLeast(1)) {
                            runCatching { audioPlayerQueue.removeFromQueueByMediaUrl(target.path) }
                        }
                        // The file is already gone: a failed refresh must not be reported as a failed delete.
                        runCatching { library.refreshNow("track_deleted") }
                    }
                    LocalTrackDeleteResult.Cancelled -> Unit
                    is LocalTrackDeleteResult.Failed -> messages.show(
                        getString(Res.string.library_local_delete_failed, target.title, result.reason)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show(
                    getString(Res.string.library_local_delete_failed, target.title, e.message.orEmpty())
                )
            } finally {
                deleting = false
                _pendingDelete.value = null
            }
        }
    }

    private fun tracks(): List<LocalMediaTrack> =
        state.value.currentFolder?.tracks.orEmpty()

    private fun play(startPosition: Int, shuffle: Boolean = false) {
        val tracks = tracks().let { if (shuffle) it.shuffled() else it }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            audioPlayerQueue.load(
                entries = tracks.map { it.toQueueEntry() },
                autoPlay = true,
                startPosition = startPosition,
                collectionEntry = null
            )
            if (shuffle) {
                playOrderSeen = false
                playOrderMode.value = true
            } else exitPlayOrder()
        }
    }
}

private fun LocalMediaTrack.toQueueEntry() = QueueEntry.LocalTrack(
    name = name,
    artists = artists,
    duration = durationMs,
    album = album,
    coverBytes = coverBytes,
    url = path
)
