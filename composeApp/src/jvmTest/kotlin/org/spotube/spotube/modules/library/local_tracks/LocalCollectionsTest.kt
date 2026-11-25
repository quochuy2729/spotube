/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.modules.library.local_tracks.media.FAVORITES_FOLDER_PATH
import org.spotube.spotube.modules.library.local_tracks.media.LocalCollectionsLibrary
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaCache
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaFolder
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaLibrary
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaTrack
import org.spotube.spotube.modules.library.local_tracks.media.MOST_PLAYED_FOLDER_PATH
import org.spotube.spotube.modules.library.local_tracks.media.RECENT_FOLDER_PATH
import org.spotube.spotube.modules.library.local_tracks.user.LocalFavoritesRepository
import org.spotube.spotube.modules.library.local_tracks.user.LocalPlayStat
import org.spotube.spotube.modules.library.local_tracks.user.LocalPlayStatsRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCollectionsTest {
    private fun track(path: String) = LocalMediaTrack(path, path, listOf("a"), 100_000)
    private fun dataStore(dir: File = Files.createTempDirectory("local-user-test").toFile()) =
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { File(dir, "user.preferences_pb").absolutePath.toPath() })

    private class FakeLibrary(initial: LocalMediaCache) : LocalMediaLibrary {
        val cache = MutableStateFlow(initial)
        override val cacheState: StateFlow<LocalMediaCache> = cache
        override suspend fun refreshNow(reason: String) = Unit
    }

    private val cache = LocalMediaCache(listOf(LocalMediaFolder("/m", "m", listOf(track("a"), track("b"), track("c")))))

    @Test
    fun favoritesToggleAndPersist() = runBlocking {
        val dir = Files.createTempDirectory("fav").toFile()
        val repo = LocalFavoritesRepository(dataStore(dir))
        repo.toggle("a"); repo.toggle("b"); repo.toggle("a")
        assertEquals(setOf("b"), repo.favorites.first())
        assertTrue(repo.isFavorite("b").first())
        assertFalse(repo.isFavorite("a").first())
    }

    @Test
    fun toggleIsSeenEvenWhenACollectorSubscribesAtTheSameTime() = runBlocking {
        // Regression: a subscriber starting while the edit was being written sometimes never saw it.
        repeat(60) { round ->
            val bg = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val repo = LocalFavoritesRepository(dataStore())
            val seen = MutableStateFlow<Set<String>?>(null)
            val job = bg.launch { repo.favorites.collect { seen.value = it } }
            repo.toggle("p$round")
            withTimeout(5_000) { seen.first { it == setOf("p$round") } }
            job.cancel()
        }
    }

    @Test
    fun playStatsCountAndFlushToDisk() = runBlocking {
        val dir = Files.createTempDirectory("stats").toFile()
        var now = 1000L
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val store = dataStore(dir) // one store per file; a second repository on it simulates a restart
        val repo = LocalPlayStatsRepository(store, null, scope, debounceMs = 10, nowMs = { now })
        repo.record("a"); now = 2000; repo.record("a"); repo.record("b")
        assertEquals(LocalPlayStat(2, 2000), repo.stats.value["a"])
        repo.flush()
        val reloaded = LocalPlayStatsRepository(store, null, scope, debounceMs = 10)
        withTimeout(5_000) {
            while (reloaded.stats.value["a"]?.count != 2) kotlinx.coroutines.delay(10)
        }
        assertEquals(1, reloaded.stats.value["b"]?.count)
    }

    @Test
    fun playStatsCollectOnlyLocalListens() = runBlocking {
        val listens = MutableSharedFlow<QueueEntry>(extraBufferCapacity = 4)
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val repo = LocalPlayStatsRepository(dataStore(), listens, scope, debounceMs = 10)
        kotlinx.coroutines.delay(300) // let the collector subscribe
        listens.emit(QueueEntry.LocalTrack("n", listOf("a"), 1, null, null, "p1"))
        withTimeout(5_000) {
            while (repo.stats.value["p1"] == null) kotlinx.coroutines.delay(10)
        }
        assertEquals(1, repo.stats.value["p1"]?.count)
    }

    @Test
    fun virtualFoldersAreBuiltFromCacheFavoritesAndStats() {
        val stats = mapOf(
            "a" to LocalPlayStat(5, 10), "b" to LocalPlayStat(9, 30),
            "gone" to LocalPlayStat(99, 99), "c" to LocalPlayStat(5, 20))
        val result = LocalCollectionsLibrary.withVirtualFolders(cache, linkedSetOf("a", "gone", "c"), stats)
        assertEquals(
            listOf(FAVORITES_FOLDER_PATH, RECENT_FOLDER_PATH, MOST_PLAYED_FOLDER_PATH, "/m"),
            result.folders.map { it.path })
        val byPath = result.folders.associateBy { it.path }
        // Newest favourite first; a deleted track ("gone") disappears.
        assertEquals(listOf("c", "a"), byPath[FAVORITES_FOLDER_PATH]!!.tracks.map { it.path })
        assertEquals(listOf("b", "c", "a"), byPath[RECENT_FOLDER_PATH]!!.tracks.map { it.path })
        assertEquals(listOf("b", "c", "a"), byPath[MOST_PLAYED_FOLDER_PATH]!!.tracks.map { it.path })
        assertTrue(byPath[FAVORITES_FOLDER_PATH]!!.isVirtual)
        assertFalse(byPath["/m"]!!.isVirtual)
    }

    @Test
    fun emptyFavoritesStillListedButEmptyStatsFoldersAreNot() {
        val result = LocalCollectionsLibrary.withVirtualFolders(cache, emptySet(), emptyMap())
        assertEquals(listOf(FAVORITES_FOLDER_PATH, "/m"), result.folders.map { it.path })
        assertEquals(emptyList(), LocalCollectionsLibrary.withVirtualFolders(LocalMediaCache(), setOf("a"), emptyMap()).folders)
    }

    @Test
    fun limitsAreApplied() {
        val many = LocalMediaCache(listOf(LocalMediaFolder("/m", "m", (1..150).map { track("t$it") })))
        val stats = (1..150).associate { "t$it" to LocalPlayStat(it, it.toLong()) }
        val byPath = LocalCollectionsLibrary.withVirtualFolders(many, emptySet(), stats).folders.associateBy { it.path }
        assertEquals(100, byPath[RECENT_FOLDER_PATH]!!.trackCount)
        assertEquals(50, byPath[MOST_PLAYED_FOLDER_PATH]!!.trackCount)
        assertEquals("t150", byPath[MOST_PLAYED_FOLDER_PATH]!!.tracks.first().path)
    }

    @Test
    fun wrapperFollowsFavoritesAndCacheChanges() = runBlocking {
        repeat(20) { wrapperRound() }
    }

    private suspend fun wrapperRound() {
        val bg = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val library = FakeLibrary(cache)
        val favorites = LocalFavoritesRepository(dataStore())
        val stats = LocalPlayStatsRepository(dataStore(), null, bg, debounceMs = 60_000)
        val wrapper = LocalCollectionsLibrary(library, favorites, stats, bg)
        // No wait on purpose: the edit races the wrapper's first DataStore read.
        favorites.toggle("b")
        withTimeout(5_000) {
            while (wrapper.cacheState.value.folders.first().tracks.map { it.path } != listOf("b")) {
                kotlinx.coroutines.delay(10)
            }
        }
        library.cache.value = LocalMediaCache(listOf(LocalMediaFolder("/m", "m", listOf(track("a")))))
        withTimeout(5_000) {
            while (wrapper.cacheState.value.folders.first().tracks.isNotEmpty()) kotlinx.coroutines.delay(10)
        }
    }
}
