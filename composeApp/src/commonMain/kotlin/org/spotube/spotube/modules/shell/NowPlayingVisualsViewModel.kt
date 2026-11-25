package org.spotube.spotube.modules.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.ui.ArtworkPalette
import org.spotube.spotube.core.ui.ArtworkPixelLoader
import org.spotube.spotube.core.ui.extractArtworkPalette

data class NowPlayingVisualsState(
    /** Colours of the current cover; null while loading or when the cover has no usable colour. */
    val palette: ArtworkPalette? = null,
    /** The cover itself, drawn blurred at low alpha behind the player where blur is supported. */
    val backdropModel: Any? = null)

/**
 * Derives the now-playing backdrop from the current queue entry's cover (local cover bytes or the
 * existing artwork URL). Decoding and the histogram run off the main thread on a 32 px thumbnail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingVisualsViewModel(audioPlayerQueue: AudioPlayerQueue) : ViewModel() {
    private val pixelLoader = MutableStateFlow<ArtworkPixelLoader?>(null)
    // Kept while the next cover loads so the backdrop cross-fades instead of flashing empty.
    private var lastPalette: ArtworkPalette? = null

    /** Supplied by the UI once it has a Coil loader and platform context. */
    fun attachPixelLoader(loader: ArtworkPixelLoader) {
        pixelLoader.value = loader
    }

    val uiState: StateFlow<NowPlayingVisualsState> = combine(
        audioPlayerQueue.currentQueueEntryFlow.map { it?.artworkModel }.distinctUntilChanged(),
        pixelLoader
    ) { model, loader -> model to loader }
        .flatMapLatest { (model, loader) ->
            flow {
                // Show the cover straight away; the palette follows once it is computed.
                emit(NowPlayingVisualsState(palette = lastPalette.takeIf { model != null }, backdropModel = model))
                if (model != null && loader != null) {
                    val palette = try {
                        loader.load(model)?.let { pixels ->
                            withContext(Dispatchers.Default) { extractArtworkPalette(pixels) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    lastPalette = palette
                    emit(NowPlayingVisualsState(palette = palette, backdropModel = model))
                } else if (model == null) {
                    lastPalette = null
                }
            }.flowOn(Dispatchers.Default)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NowPlayingVisualsState())
}
