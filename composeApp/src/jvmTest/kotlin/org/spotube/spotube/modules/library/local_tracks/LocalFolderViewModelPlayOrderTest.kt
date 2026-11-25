/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.FakeAudioPlayer
import org.spotube.spotube.core.audioplayer.QueueCollectionEntry
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.core.ui.AppMessages
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaCache
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaFolder
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaLibrary
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaTrack
import org.spotube.spotube.modules.library.local_tracks.media.LocalTrackDeleteResult
import org.spotube.spotube.modules.library.local_tracks.media.LocalTrackDeleter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFolderViewModelPlayOrderTest {
    private class FakeLibrary(folder: LocalMediaFolder) : LocalMediaLibrary {
        val cache = MutableStateFlow(LocalMediaCache(listOf(folder)))
        override val cacheState: StateFlow<LocalMediaCache> = cache
        override suspend fun refreshNow(reason: String) = Unit
    }

    private object NoopDeleter : LocalTrackDeleter {
        override suspend fun delete(path: String) = LocalTrackDeleteResult.Deleted
    }

    /** Queue that really stores its entries, so shuffle/move/remove are observable. */
    private class StatefulQueue : AudioPlayerQueue {
        val entries = MutableStateFlow<List<QueueEntry>>(emptyList())
        var index = 0
        var loads = 0
        var jumps = mutableListOf<Int>()
        /** Like the Android player: one call removes only one copy of a duplicated url. */
        var removeOneCopy = false
        /** When set, [move] suspends until it completes (a drop still being applied). */
        var moveGate: CompletableDeferred<Unit>? = null
        override val queueFlow: StateFlow<List<QueueEntry>> = entries
        override val currentQueueEntryFlow = MutableStateFlow<QueueEntry?>(null)
        override val currentCollectionEntryFlow: StateFlow<QueueCollectionEntry?> = MutableStateFlow(null)
        override val collectionHistoryFlow: StateFlow<List<QueueCollectionEntry>> = MutableStateFlow(emptyList())
        override suspend fun load(
            entries: List<QueueEntry>, autoPlay: Boolean, startPosition: Int,
            collectionEntry: QueueCollectionEntry?
        ) {
            loads++
            this.entries.value = entries
            index = startPosition
            currentQueueEntryFlow.value = entries.getOrNull(startPosition)
        }

        override suspend fun addToQueue(entry: QueueEntry) { entries.value = entries.value + entry }
        override suspend fun addAllToQueue(entries: List<QueueEntry>, collectionEntry: QueueCollectionEntry?) = Unit
        override suspend fun addAllAfterCurrent(entries: List<QueueEntry>) = Unit
        override suspend fun removeFromQueue(entry: QueueEntry) = Unit
        override suspend fun removeFromQueueByMediaUrl(mediaUrl: String) {
            if (removeOneCopy) {
                val i = entries.value.indexOfFirst { (it as? QueueEntry.LocalTrack)?.url == mediaUrl }
                if (i >= 0) entries.value = entries.value.toMutableList().apply { removeAt(i) }
            } else entries.value = entries.value.filter { (it as? QueueEntry.LocalTrack)?.url != mediaUrl }
        }

        override suspend fun move(fromIndex: Int, toIndex: Int) {
            moveGate?.await()
            entries.value = entries.value.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }

        override suspend fun jumpTo(index: Int, autoPlay: Boolean) {
            jumps += index
            currentQueueEntryFlow.value = entries.value.getOrNull(index)
        }

        override suspend fun reloadCurrent() = Unit
        override suspend fun clear() { entries.value = emptyList() }
        override suspend fun getQueue(): List<QueueEntry> = entries.value
        override suspend fun getCurrentQueueEntry(): QueueEntry? = currentQueueEntryFlow.value
        override suspend fun getCurrentCollectionEntry(): QueueCollectionEntry? = null
        override suspend fun getCollectionHistory(): List<QueueCollectionEntry> = emptyList()
    }

    private val tracks = ('a'..'f').map { LocalMediaTrack("/music/$it.mp3", "Song $it", listOf("X"), 1000) }
    private val folder = LocalMediaFolder("/music", "music", tracks)

    @BeforeTest
    fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun teardown() = Dispatchers.resetMain()

    private class Harness(val vm: LocalFolderViewModel, val queue: StatefulQueue, val library: FakeLibrary)

    private fun TestScope.harness(): Harness {
        val library = FakeLibrary(folder)
        val queue = StatefulQueue()
        val vm = LocalFolderViewModel("/music", library, queue, FakeAudioPlayer(), NoopDeleter, AppMessages())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return Harness(vm, queue, library)
    }

    private fun Harness.shown() = vm.state.value.visibleTracks.map { it.path }
    private fun Harness.queued() = queue.entries.value.map { (it as QueueEntry.LocalTrack).url }

    @Test
    fun `shuffle shows the list in queue order`() = runTest {
        val h = harness()
        assertEquals(tracks.map { it.path }, h.shown())
        h.vm.shuffleFolder()
        assertTrue(h.vm.state.value.isPlayOrder)
        assertEquals(6, h.queued().size)
        assertEquals(h.queued(), h.shown())
    }

    @Test
    fun `dragging reorders the queue and the list`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        val before = h.queued()
        h.vm.onDragStart()
        h.vm.onMove(0, 1)
        h.vm.onMove(1, 3)
        // Buffered order is shown immediately, the queue is only touched when the drag ends.
        assertEquals(before, h.queued())
        assertEquals(before.toMutableList().apply { add(3, removeAt(0)) }, h.shown())
        h.vm.onDragStop()
        val expected = before.toMutableList().apply { add(3, removeAt(0)) }
        assertEquals(expected, h.queued())
        assertEquals(expected, h.shown())
    }

    @Test
    fun `tapping a track in play order jumps in the queue without reloading`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        val target = h.queued()[3]
        h.vm.playTrack(target)
        assertEquals(1, h.queue.loads)
        assertEquals(listOf(3), h.queue.jumps)
    }

    @Test
    fun `deleting a track removes it from the play order list`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        val victim = h.queued()[2]
        h.vm.requestDelete(victim)
        h.vm.confirmDelete()
        assertFalse(victim in h.shown())
        assertEquals(5, h.shown().size)
        assertEquals(h.queued(), h.shown())
    }

    @Test
    fun `exiting play order restores the normal sort`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        h.vm.exitPlayOrder()
        assertFalse(h.vm.state.value.isPlayOrder)
        assertEquals(tracks.map { it.path }, h.shown())
    }

    @Test
    fun `replacing the queue with other content leaves play order`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        h.queue.entries.value = listOf(QueueEntry.LocalTrack("Other", listOf("Y"), 1000, null, null, "/other/z.mp3"))
        assertFalse(h.vm.state.value.isPlayOrder)
        assertEquals(tracks.map { it.path }, h.shown())
    }

    @Test
    fun `deleting a track queued twice removes every copy`() = runTest {
        val h = harness()
        h.queue.removeOneCopy = true
        h.vm.playFolder()
        val victim = tracks[1].path
        h.vm.addToQueue(victim)
        assertEquals(2, h.queued().count { it == victim })
        h.vm.requestDelete(victim)
        h.vm.confirmDelete()
        assertFalse(victim in h.queued())
        assertEquals(5, h.queued().size)
    }

    @Test
    fun `a drop is discarded when the queue changed during the drag`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        h.vm.onDragStart()
        h.vm.onMove(0, 3)
        val removed = h.queued()[1]
        h.queue.removeFromQueueByMediaUrl(removed)
        val afterRemoval = h.queued()
        h.vm.onDragStop()
        assertEquals(afterRemoval, h.queued())
        assertEquals(h.queued(), h.shown())
    }

    @Test
    fun `a drag started while the previous drop is applied is ignored`() = runTest {
        val h = harness()
        h.vm.shuffleFolder()
        val before = h.queued()
        val gate = CompletableDeferred<Unit>()
        h.queue.moveGate = gate
        h.vm.onDragStart()
        h.vm.onMove(0, 1)
        h.vm.onDragStop() // move suspended on the gate
        h.vm.onDragStart()
        h.vm.onMove(0, 3)
        h.vm.onDragStop()
        gate.complete(Unit)
        assertEquals(before.toMutableList().apply { add(1, removeAt(0)) }, h.queued())
        assertEquals(h.queued(), h.shown())
    }
}
