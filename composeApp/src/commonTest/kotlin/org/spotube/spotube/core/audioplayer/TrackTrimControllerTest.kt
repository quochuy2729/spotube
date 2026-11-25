/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.spotube.spotube.core.audioplayer.trim.TrackTrim
import org.spotube.spotube.core.audioplayer.trim.TrackTrimController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrackTrimControllerTest {
    /** Records the commands the controller sends; state flows are set by each test. */
    private class Player(private val base: FakeAudioPlayer = FakeAudioPlayer()) : AudioPlayerInterface by base {
        val state = MutableStateFlow(PlayerState.PLAYING)
        val position = MutableStateFlow(Duration.ZERO)
        val duration = MutableStateFlow(233_000.milliseconds)
        val loop = MutableStateFlow(LoopState.NONE)
        val playlist = MutableStateFlow(listOf(item("a"), item("b")))
        val current = MutableStateFlow<MediaItem?>(item("a"))
        val commands = mutableListOf<String>()

        override val playerStateFlow: StateFlow<PlayerState> get() = state
        override val positionFlow: StateFlow<Duration> get() = position
        override val durationFlow: StateFlow<Duration> get() = duration
        override val loopStateFlow: StateFlow<LoopState> get() = loop
        override val playlistFlow: StateFlow<List<MediaItem>> get() = playlist
        override val currentMediaItemFlow: StateFlow<MediaItem?> get() = current

        override suspend fun seekTo(position: Duration) {
            commands += "seek:${position.inWholeMilliseconds}"
            this.position.value = position
        }
        override suspend fun skipToNext() { commands += "next" }
        override suspend fun jumpTo(index: Int) { commands += "jump:$index" }
        override suspend fun pause() { commands += "pause" }
    }

    private class Queue(
        base: AudioPlayerQueue,
        override val currentQueueEntryFlow: MutableStateFlow<QueueEntry?>) : AudioPlayerQueue by base

    private class Setup(
        val player: Player,
        val entry: MutableStateFlow<QueueEntry?>,
        val trims: MutableStateFlow<Map<String, TrackTrim>>,
        val scope: CoroutineScope)

    private fun TestScope.setup(trim: TrackTrim?, isBlocked: () -> Boolean = { false }): Setup {
        val player = Player()
        val entry = MutableStateFlow<QueueEntry?>(track("a"))
        val trims = MutableStateFlow(trim?.let { mapOf("local:a" to it) } ?: emptyMap())
        val scope = CoroutineScope(coroutineContext + Job())
        TrackTrimController(player, Queue(UnusedQueue, entry), trims, isBlocked, scope)
        return Setup(player, entry, trims, scope)
    }

    private fun TestScope.playAt(setup: Setup, positionMs: Long) {
        setup.player.position.value = positionMs.milliseconds
        advanceTimeBy(500)
        runCurrent()
    }

    private fun trimTest(
        trim: TrackTrim?,
        isBlocked: () -> Boolean = { false },
        body: TestScope.(Setup) -> Unit) = runTest {
        val setup = setup(trim, isBlocked)
        try {
            body(setup)
        } finally {
            setup.scope.cancel()
        }
    }

    @Test
    fun skipsToNextTrackWhenTheCutTailIsReached() = trimTest(TrackTrim(endMs = 228_000)) { s ->
        playAt(s, 100_000)
        assertEquals(emptyList(), s.player.commands)
        playAt(s, 228_100)
        assertEquals(listOf("next"), s.player.commands)
        // The old position stays published for a moment: the cut must not fire twice.
        playAt(s, 228_300)
        assertEquals(listOf("next"), s.player.commands)
    }

    @Test
    fun jumpsOverTheCutIntro() = trimTest(TrackTrim(startMs = 7_000)) { s ->
        playAt(s, 200)
        assertEquals(listOf("seek:7000"), s.player.commands)
    }

    @Test
    fun pausesOnTheLastTrackWithoutRepeat() = trimTest(TrackTrim(endMs = 228_000)) { s ->
        s.player.playlist.value = listOf(item("a"))
        playAt(s, 1_000)
        playAt(s, 229_000)
        assertEquals(listOf("pause", "seek:0"), s.player.commands)
    }

    @Test
    fun repeatOneRestartsAtTheTrimmedStart() = trimTest(TrackTrim(startMs = 3_000, endMs = 228_000)) { s ->
        s.player.loop.value = LoopState.ONE
        playAt(s, 4_000)
        playAt(s, 229_000)
        assertEquals(listOf("seek:3000"), s.player.commands)
    }

    @Test
    fun repeatAllOnTheLastTrackJumpsToTheFirst() = trimTest(TrackTrim(endMs = 228_000)) { s ->
        s.player.loop.value = LoopState.ALL
        s.player.current.value = item("b")
        playAt(s, 1_000)
        playAt(s, 229_000)
        assertEquals(listOf("jump:0"), s.player.commands)
    }

    @Test
    fun stalePositionOfThePreviousTrackDoesNotSkipTheNewOne() = trimTest(TrackTrim(endMs = 100_000)) { s ->
        // The entry changes while the position still belongs to the previous, longer track.
        s.entry.value = track("other")
        s.player.position.value = 200_000.milliseconds
        advanceTimeBy(500)
        s.entry.value = track("a")
        playAt(s, 200_000)
        assertEquals(emptyList(), s.player.commands)
        playAt(s, 500)
        playAt(s, 100_500)
        assertEquals(listOf("next"), s.player.commands)
    }

    @Test
    fun savingATrimBehindThePlayheadSkipsRightAway() = trimTest(null) { s ->
        playAt(s, 230_000)
        s.trims.value = mapOf("local:a" to TrackTrim(endMs = 228_000))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("next"), s.player.commands)
    }

    @Test
    fun doesNothingWhilePaused() = trimTest(TrackTrim(startMs = 7_000, endMs = 228_000)) { s ->
        s.player.state.value = PlayerState.PAUSED
        playAt(s, 200)
        playAt(s, 229_000)
        assertEquals(emptyList(), s.player.commands)
    }

    @Test
    fun ignoresATrimOutsideTheAudio() = trimTest(TrackTrim(startMs = 300_000, endMs = 400_000)) { s ->
        playAt(s, 1_000)
        playAt(s, 232_000)
        assertEquals(emptyList(), s.player.commands)
    }

    @Test
    fun doesNothingWhenBlocked() = trimTest(TrackTrim(endMs = 228_000), isBlocked = { true }) { s ->
        playAt(s, 1_000)
        playAt(s, 229_000)
        assertEquals(emptyList(), s.player.commands)
    }

    private companion object {
        fun item(url: String) = MediaItem(url, "", "", 233_000.milliseconds, "", url)
        fun track(url: String): QueueEntry = QueueEntry.LocalTrack(url, emptyList(), 233_000, null, null, url)

        /** Only the current entry matters to the controller; nothing else may be called. */
        val UnusedQueue: AudioPlayerQueue = object : AudioPlayerQueue {
            override val queueFlow: StateFlow<List<QueueEntry>> = MutableStateFlow(emptyList())
            override val currentQueueEntryFlow: StateFlow<QueueEntry?> = MutableStateFlow(null)
            override val currentCollectionEntryFlow: StateFlow<QueueCollectionEntry?> = MutableStateFlow(null)
            override val collectionHistoryFlow: StateFlow<List<QueueCollectionEntry>> = MutableStateFlow(emptyList())
            override suspend fun load(entries: List<QueueEntry>, autoPlay: Boolean, startPosition: Int, collectionEntry: QueueCollectionEntry?) = error("unused")
            override suspend fun addToQueue(entry: QueueEntry) = error("unused")
            override suspend fun addAllToQueue(entries: List<QueueEntry>, collectionEntry: QueueCollectionEntry?) = error("unused")
            override suspend fun addAllAfterCurrent(entries: List<QueueEntry>) = error("unused")
            override suspend fun removeFromQueue(entry: QueueEntry) = error("unused")
            override suspend fun removeFromQueueByMediaUrl(mediaUrl: String) = error("unused")
            override suspend fun move(fromIndex: Int, toIndex: Int) = error("unused")
            override suspend fun jumpTo(index: Int, autoPlay: Boolean) = error("unused")
            override suspend fun reloadCurrent() = error("unused")
            override suspend fun clear() = error("unused")
            override suspend fun getQueue(): List<QueueEntry> = error("unused")
            override suspend fun getCurrentQueueEntry(): QueueEntry? = error("unused")
            override suspend fun getCurrentCollectionEntry(): QueueCollectionEntry? = error("unused")
            override suspend fun getCollectionHistory(): List<QueueCollectionEntry> = error("unused")
        }
    }
}
