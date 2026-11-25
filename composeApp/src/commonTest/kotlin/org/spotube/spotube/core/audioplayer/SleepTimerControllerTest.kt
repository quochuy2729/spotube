package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {
    private class StubPlayer : AudioPlayerInterface {
        val volume = MutableStateFlow(0.8f)
        val position = MutableStateFlow(Duration.ZERO)
        val duration = MutableStateFlow(Duration.ZERO)
        var pauseCount = 0

        override suspend fun pause() { pauseCount++ }
        override suspend fun setVolume(volume: Float) { this.volume.value = volume }
        override val volumeFlow: StateFlow<Float> get() = volume
        override val positionFlow: StateFlow<Duration> get() = position
        override val durationFlow: StateFlow<Duration> get() = duration

        override suspend fun play() = Unit
        override suspend fun stop() = Unit
        override suspend fun seekTo(position: Duration) = Unit
        override suspend fun loop(state: LoopState) = Unit
        override suspend fun shuffle(enabled: Boolean) = Unit
        override suspend fun load(playlist: List<MediaItem>, autoPlay: Boolean, startPosition: Int) = Unit
        override suspend fun addMediaItem(mediaItem: MediaItem) = Unit
        override suspend fun insertMediaItemAtNextIndex(mediaItem: MediaItem) = Unit
        override suspend fun removeMediaItem(mediaItem: MediaItem) = Unit
        override suspend fun moveMediaItem(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun skipToNext() = Unit
        override suspend fun skipToPrevious() = Unit
        override suspend fun jumpTo(index: Int) = Unit
        override val playerStateFlow: StateFlow<PlayerState> = MutableStateFlow(PlayerState.PLAYING)
        override val currentMediaItemFlow: StateFlow<MediaItem?> = MutableStateFlow(null)
        override val playlistFlow: StateFlow<List<MediaItem>> = MutableStateFlow(emptyList())
        override val bufferingPositionFlow: StateFlow<Duration> = MutableStateFlow(Duration.ZERO)
        override val loopStateFlow: StateFlow<LoopState> = MutableStateFlow(LoopState.NONE)
        override val shuffleModeFlow: StateFlow<Boolean> = MutableStateFlow(false)
        override val playbackSpeedFlow: StateFlow<Float> = MutableStateFlow(1f)
        override val completionFlow: Flow<Unit> = emptyFlow()
        override val errorFlow: Flow<Throwable> = emptyFlow()
        override suspend fun setPlaybackSpeed(speed: Float) = Unit
        override fun isDisposed(): Boolean = false
        override fun dispose() = Unit
    }

    private fun queue(): AudioPlayerQueue = object : AudioPlayerQueue {
        override val queueFlow: StateFlow<List<QueueEntry>> = MutableStateFlow(emptyList())
        override val currentQueueEntryFlow: StateFlow<QueueEntry?> = MutableStateFlow(null)
        override val currentCollectionEntryFlow: StateFlow<QueueCollectionEntry?> = MutableStateFlow(null)
        override val collectionHistoryFlow: StateFlow<List<QueueCollectionEntry>> = MutableStateFlow(emptyList())
        override suspend fun load(entries: List<QueueEntry>, autoPlay: Boolean, startPosition: Int, collectionEntry: QueueCollectionEntry?) = Unit
        override suspend fun addToQueue(entry: QueueEntry) = Unit
        override suspend fun addAllToQueue(entries: List<QueueEntry>, collectionEntry: QueueCollectionEntry?) = Unit
        override suspend fun addAllAfterCurrent(entries: List<QueueEntry>) = Unit
        override suspend fun removeFromQueue(entry: QueueEntry) = Unit
        override suspend fun removeFromQueueByMediaUrl(mediaUrl: String) = Unit
        override suspend fun move(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun jumpTo(index: Int, autoPlay: Boolean) = Unit
        override suspend fun reloadCurrent() = Unit
        override suspend fun clear() = Unit
        override suspend fun getQueue(): List<QueueEntry> = emptyList()
        override suspend fun getCurrentQueueEntry(): QueueEntry? = null
        override suspend fun getCurrentCollectionEntry(): QueueCollectionEntry? = null
        override suspend fun getCollectionHistory(): List<QueueCollectionEntry> = emptyList()
    }

    private fun TestScope.controller(player: StubPlayer) =
        SleepTimerController(player, queue(), CoroutineScope(coroutineContext))

    @Test
    fun countdownPausesAndRestoresVolume() = runTest {
        val player = StubPlayer()
        val timer = controller(player)
        timer.startCountdown(1)
        runCurrent()
        assertNotNull(timer.state.value.remainingMs)
        advanceTimeBy(40_000)
        runCurrent()
        // Inside the fade window the volume is lower than the original.
        assertTrue(player.volume.value < 0.8f)
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(1, player.pauseCount)
        assertEquals(0.8f, player.volume.value)
        assertFalse(timer.state.value.isActive)
    }

    @Test
    fun cancelRestoresVolumeDuringFade() = runTest {
        val player = StubPlayer()
        val timer = controller(player)
        timer.startCountdown(1)
        advanceTimeBy(50_000)
        runCurrent()
        assertTrue(player.volume.value < 0.8f)
        timer.cancel()
        runCurrent()
        assertEquals(0.8f, player.volume.value)
        assertEquals(0, player.pauseCount)
        assertNull(timer.state.value.remainingMs)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, player.pauseCount)
    }

    @Test
    fun untilTrackEndPausesNearTheEnd() = runTest {
        val player = StubPlayer()
        player.duration.value = 100_000.milliseconds
        val timer = controller(player)
        timer.startUntilTrackEnd()
        runCurrent()
        assertTrue(timer.state.value.untilTrackEnd)
        player.position.value = 50_000.milliseconds
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, player.pauseCount)
        player.position.value = 99_800.milliseconds
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, player.pauseCount)
        assertFalse(timer.state.value.isActive)
    }

    @Test
    fun untilTrackEndPausesWhenRepeatOneRestartsTheTrack() = runTest {
        // The end window was never sampled (sparse position updates / fast speed) and the entry
        // stays the same with repeat-one: the jump back to the start must still count as the end.
        val player = StubPlayer()
        player.duration.value = 100_000.milliseconds
        val timer = controller(player)
        timer.startUntilTrackEnd()
        runCurrent()
        player.position.value = 99_000.milliseconds
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, player.pauseCount)
        player.position.value = 200.milliseconds
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, player.pauseCount)
        assertFalse(timer.state.value.isActive)
    }

    @Test
    fun untilTrackEndIgnoresSeekingBackFromTheMiddle() = runTest {
        val player = StubPlayer()
        player.duration.value = 100_000.milliseconds
        val timer = controller(player)
        timer.startUntilTrackEnd()
        runCurrent()
        player.position.value = 60_000.milliseconds
        advanceTimeBy(300)
        player.position.value = 10_000.milliseconds
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, player.pauseCount)
        assertTrue(timer.state.value.untilTrackEnd)
        timer.cancel()
        runCurrent()
    }

    @Test
    fun rejectsOutOfRangeMinutes() = runTest {
        val timer = controller(StubPlayer())
        timer.startCountdown(0)
        timer.startCountdown(601)
        runCurrent()
        assertFalse(timer.state.value.isActive)
    }
}
