/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFolderViewModelDeleteTest {
    private class FakeLibrary(folder: LocalMediaFolder) : LocalMediaLibrary {
        val cache = MutableStateFlow(LocalMediaCache(listOf(folder)))
        override val cacheState: StateFlow<LocalMediaCache> = cache
        var refreshCount = 0
        var onRefresh: () -> Unit = {}
        override suspend fun refreshNow(reason: String) {
            refreshCount++
            onRefresh()
        }
    }

    private class FakeDeleter(var result: LocalTrackDeleteResult) : LocalTrackDeleter {
        val calls = mutableListOf<String>()
        override suspend fun delete(path: String): LocalTrackDeleteResult {
            calls += path
            return result
        }
    }

    private class RecordingQueue : AudioPlayerQueue {
        val removed = mutableListOf<String>()
        override val queueFlow: StateFlow<List<QueueEntry>> = MutableStateFlow(emptyList())
        override val currentQueueEntryFlow: StateFlow<QueueEntry?> = MutableStateFlow(null)
        override val currentCollectionEntryFlow: StateFlow<QueueCollectionEntry?> = MutableStateFlow(null)
        override val collectionHistoryFlow: StateFlow<List<QueueCollectionEntry>> = MutableStateFlow(emptyList())
        override suspend fun load(
            entries: List<QueueEntry>, autoPlay: Boolean, startPosition: Int,
            collectionEntry: QueueCollectionEntry?
        ) = Unit

        override suspend fun addToQueue(entry: QueueEntry) = Unit
        override suspend fun addAllToQueue(entries: List<QueueEntry>, collectionEntry: QueueCollectionEntry?) = Unit
        override suspend fun addAllAfterCurrent(entries: List<QueueEntry>) = Unit
        override suspend fun removeFromQueue(entry: QueueEntry) = Unit
        override suspend fun removeFromQueueByMediaUrl(mediaUrl: String) {
            removed += mediaUrl
        }

        override suspend fun move(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun jumpTo(index: Int, autoPlay: Boolean) = Unit
        override suspend fun reloadCurrent() = Unit
        override suspend fun clear() = Unit
        override suspend fun getQueue(): List<QueueEntry> = emptyList()
        override suspend fun getCurrentQueueEntry(): QueueEntry? = null
        override suspend fun getCurrentCollectionEntry(): QueueCollectionEntry? = null
        override suspend fun getCollectionHistory(): List<QueueCollectionEntry> = emptyList()
    }

    private val trackA = LocalMediaTrack("/music/a.mp3", "Song A", listOf("X"), 1000)
    private val trackB = LocalMediaTrack("/music/b.mp3", "Song B", listOf("X"), 1000)
    private val folder = LocalMediaFolder("/music", "music", listOf(trackA, trackB))

    @BeforeTest
    fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun teardown() = Dispatchers.resetMain()

    private class Harness(
        val vm: LocalFolderViewModel, val library: FakeLibrary, val deleter: FakeDeleter,
        val queue: RecordingQueue, val messages: AppMessages
    )

    private fun harness(result: LocalTrackDeleteResult): Harness {
        val library = FakeLibrary(folder)
        val deleter = FakeDeleter(result)
        val queue = RecordingQueue()
        val messages = AppMessages()
        return Harness(
            LocalFolderViewModel("/music", library, queue, FakeAudioPlayer(), deleter, messages),
            library, deleter, queue, messages
        )
    }

    @Test
    fun `requesting delete only asks for confirmation`() = runTest {
        val h = harness(LocalTrackDeleteResult.Deleted)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect {} }
        h.vm.requestDelete(trackA.path)
        assertEquals(trackA.path, h.vm.pendingDelete.value?.path)
        assertTrue(h.deleter.calls.isEmpty())
    }

    @Test
    fun `confirm calls deleter exactly once, dequeues track and refreshes library`() = runTest {
        val h = harness(LocalTrackDeleteResult.Deleted)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect {} }
        h.vm.requestDelete(trackA.path)
        h.vm.confirmDelete()
        h.vm.confirmDelete() // second call has nothing pending
        assertEquals(listOf(trackA.path), h.deleter.calls)
        assertEquals(listOf(trackA.path), h.queue.removed)
        assertEquals(1, h.library.refreshCount)
        assertNull(h.vm.pendingDelete.value)
    }

    @Test
    fun `cancel never calls deleter`() = runTest {
        val h = harness(LocalTrackDeleteResult.Deleted)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect {} }
        h.vm.requestDelete(trackA.path)
        h.vm.cancelDelete()
        h.vm.confirmDelete()
        assertTrue(h.deleter.calls.isEmpty())
        assertNull(h.vm.pendingDelete.value)
        assertEquals(0, h.library.refreshCount)
    }

    @Test
    fun `user cancelled system dialog keeps the track and shows no error`() = runTest {
        val h = harness(LocalTrackDeleteResult.Cancelled)
        val shown = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.messages.messages.collect { shown += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect {} }
        h.vm.requestDelete(trackA.path)
        h.vm.confirmDelete()
        assertEquals(1, h.deleter.calls.size)
        assertTrue(shown.isEmpty())
        assertTrue(h.queue.removed.isEmpty())
        assertEquals(0, h.library.refreshCount)
    }

    @Test
    fun `failure keeps the track in the list and reports an error`() = runTest {
        val h = harness(LocalTrackDeleteResult.Failed("boom"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect {} }
        // getString() loads resources off the test scheduler, so wait in real time.
        val message = backgroundScope.async(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { h.messages.messages.first() }
        }
        kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        h.vm.requestDelete(trackA.path)
        h.vm.confirmDelete()
        assertTrue(message.await().contains("boom"))
        assertEquals(1, h.deleter.calls.size)
        assertTrue(h.queue.removed.isEmpty())
        assertEquals(0, h.library.refreshCount)
        assertEquals(
            listOf(trackA.path, trackB.path),
            h.vm.state.value.visibleTracks.map { it.path }
        )
        assertNotNull(h.vm.state.value.currentFolder)
        assertNull(h.vm.pendingDelete.value)
    }

    @Test
    fun `successful delete drops the track once the library refreshes`() = runTest {
        val h = harness(LocalTrackDeleteResult.Deleted)
        h.library.onRefresh = {
            h.library.cache.value = LocalMediaCache(listOf(folder.copy(tracks = listOf(trackB))))
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.collect {} }
        h.vm.requestDelete(trackA.path)
        h.vm.confirmDelete()
        assertEquals(listOf(trackB.path), h.vm.state.value.visibleTracks.map { it.path })
    }
}
