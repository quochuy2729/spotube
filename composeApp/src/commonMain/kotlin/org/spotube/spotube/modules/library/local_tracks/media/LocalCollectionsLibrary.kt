/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.spotube.spotube.modules.library.local_tracks.user.LocalFavoritesRepository
import org.spotube.spotube.modules.library.local_tracks.user.LocalPlayStat
import org.spotube.spotube.modules.library.local_tracks.user.LocalPlayStatsRepository

/**
 * Wraps the real library and prepends virtual folders (Favorites, Recently played, Most played).
 * Their tracks are looked up in the real cache by path, so a deleted file simply drops out.
 */
class LocalCollectionsLibrary(
    private val delegate: LocalMediaLibrary,
    private val favorites: LocalFavoritesRepository,
    private val playStats: LocalPlayStatsRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) : LocalMediaLibrary {

    override val cacheState: StateFlow<LocalMediaCache> = combine(
        delegate.cacheState, favorites.favorites, playStats.stats, ::withVirtualFolders
    ).stateIn(
        scope, SharingStarted.Eagerly,
        withVirtualFolders(delegate.cacheState.value, emptySet(), playStats.stats.value)
    )

    override suspend fun refreshNow(reason: String) = delegate.refreshNow(reason)
    override suspend fun refreshIfStale() = delegate.refreshIfStale()

    companion object {
        const val RECENT_LIMIT = 100
        const val MOST_PLAYED_LIMIT = 50

        internal fun withVirtualFolders(
            cache: LocalMediaCache,
            favoritePaths: Set<String>,
            stats: Map<String, LocalPlayStat>): LocalMediaCache {
            val real = cache.folders.filter { !it.isVirtual }
            val byPath = LinkedHashMap<String, LocalMediaTrack>()
            real.forEach { folder -> folder.tracks.forEach { byPath.getOrPut(it.path) { it } } }
            if (byPath.isEmpty()) return cache.copy(folders = real)

            // Newest favourite first (the set keeps insertion order).
            val favoriteTracks = favoritePaths.reversed().mapNotNull { byPath[it] }
            val recent = stats.entries
                .filter { it.value.lastPlayedAt > 0 }
                .sortedByDescending { it.value.lastPlayedAt }
                .mapNotNull { byPath[it.key] }
                .take(RECENT_LIMIT)
            val most = stats.entries
                .filter { it.value.count > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, LocalPlayStat>> { it.value.count }
                    .thenByDescending { it.value.lastPlayedAt })
                .mapNotNull { byPath[it.key] }
                .take(MOST_PLAYED_LIMIT)

            val virtual = buildList {
                // Favorites is always listed so the feature is discoverable, even when empty.
                add(LocalMediaFolder(FAVORITES_FOLDER_PATH, "Favorites", favoriteTracks))
                if (recent.isNotEmpty()) add(LocalMediaFolder(RECENT_FOLDER_PATH, "Recently played", recent))
                if (most.isNotEmpty()) add(LocalMediaFolder(MOST_PLAYED_FOLDER_PATH, "Most played", most))
            }
            return cache.copy(folders = virtual + real)
        }
    }
}
