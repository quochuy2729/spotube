/*
 * Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.spotube.spotube.modules.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsLine
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.modules.plugin.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
enum class LyricType {
    STATIC,
    SYNCED
}

data class LyricsUiState(
    val mode: LyricType = LyricType.SYNCED,
    val syncedLyrics: List<LyricsLine>? = null,
    val plainLyrics: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentIndex: Int = -1,
    val trackTitle: String = "",
    val trackArtists: String = "",
    val coverUrl: String = "")

class LyricsViewModel(
    private val pluginManager: PluginManager,
    private val audioPlayer: AudioPlayerInterface,
    private val audioPlayerQueue: AudioPlayerQueue) : ViewModel() {

    private val _uiState = MutableStateFlow(LyricsUiState())

    // Only re-emits when the active line changes, not on every position tick.
    private val currentIndexFlow = combine(
        _uiState.map { it.syncedLyrics }.distinctUntilChanged(),
        audioPlayer.positionFlow
    ) { lyrics, position -> activeLineIndex(lyrics, position.inWholeMilliseconds) }
        .distinctUntilChanged()

    val uiState: StateFlow<LyricsUiState> = combine(_uiState, currentIndexFlow) { state, index ->
        state.copy(currentIndex = index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsUiState())

    private var currentTrackId: String? = null

    fun setMode(mode: LyricType) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun loadLyrics(forceReload: Boolean = false) {
        viewModelScope.launch {
            val entry = audioPlayerQueue.currentQueueEntryFlow.value ?: return@launch
            val key = entry.lyricsKey()

            if (!forceReload && currentTrackId == key && _uiState.value.syncedLyrics != null) {
                return@launch
            }

            currentTrackId = key
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // A .lrc file next to a local track wins over any online lookup.
                val sidecar = (entry as? QueueEntry.LocalTrack)?.let { local ->
                    withContext(Dispatchers.Default) { readSidecarLyrics(local.url) }
                }
                if (sidecar != null) {
                    publish(key, sidecar)
                    return@launch
                }
                val track = when (entry) {
                    is QueueEntry.StreamingTrack -> entry.track
                    is QueueEntry.LocalTrack -> entry.toLyricsSearchTrack()
                }
                val plugin = pluginManager.selectedLyricsPlugin.value
                if (plugin == null) {
                    publish(key, null)
                } else {
                    val lyrics = plugin.use { lyricsAPI.getLyrics(track) }
                    publish(key, lyrics)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentTrackId == key) _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** Applies a result only if the track it was loaded for is still the current one. */
    private fun publish(key: String, lyrics: LyricsResponse?) {
        if (currentTrackId != key) return
        _uiState.update {
            it.copy(
                syncedLyrics = lyrics?.syncedLyrics,
                plainLyrics = lyrics?.plainLyrics,
                isLoading = false)
        }
    }

    private fun QueueEntry.lyricsKey(): String = when (this) {
        is QueueEntry.StreamingTrack -> track.id
        is QueueEntry.LocalTrack -> url
    }

    init {
        viewModelScope.launch {
            audioPlayerQueue.currentQueueEntryFlow.collect { entry ->
                val key = entry?.lyricsKey()
                // Switching tracks must not keep showing the previous track's lyrics (this used to
                // leak the last online track's lyrics into the preview of a local track).
                if (key != currentTrackId) {
                    currentTrackId = null
                    _uiState.update {
                        it.copy(syncedLyrics = null, plainLyrics = null, error = null, isLoading = false)
                    }
                }
                when (entry) {
                    is QueueEntry.StreamingTrack -> {
                        val track = entry.track
                        _uiState.update {
                            it.copy(
                                trackTitle = track.title,
                                trackArtists = track.artists.joinToString(", ") { artist -> artist.name },
                                coverUrl = (track.album?.thumbnails ?: track.thumbnails)
                                    ?.firstOrNull()?.url.orEmpty()
                            )
                        }
                    }
                    is QueueEntry.LocalTrack -> _uiState.update {
                        it.copy(
                            trackTitle = entry.name,
                            trackArtists = entry.artists.joinToString(", "),
                            coverUrl = ""
                        )
                    }
                    null -> Unit
                }
                if (key != null && key != currentTrackId) {
                    loadLyrics()
                }
            }
        }
    }

    fun seekToLine(line: LyricsLine) {
        viewModelScope.launch {
            audioPlayer.seekTo(line.time.milliseconds)
        }
    }

    private fun activeLineIndex(lyrics: List<LyricsLine>?, positionMillis: Long): Int {
        if (lyrics.isNullOrEmpty()) return -1
        val index = lyrics.indexOfLast { it.time <= positionMillis }
        return index.coerceAtLeast(0)
    }
}
