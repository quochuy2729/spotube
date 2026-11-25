/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

import kotlinx.coroutines.flow.StateFlow

/** The slice of [LocalMediaLibraryCoordinator] that screens need; lets view models be tested with a fake. */
interface LocalMediaLibrary {
    val cacheState: StateFlow<LocalMediaCache>
    suspend fun refreshNow(reason: String)

    /** Refreshes only when the cache is old; the default does nothing. */
    suspend fun refreshIfStale() = Unit
}
