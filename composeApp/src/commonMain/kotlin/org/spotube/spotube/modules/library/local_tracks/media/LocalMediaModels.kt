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

package org.spotube.spotube.modules.library.local_tracks.media

import kotlinx.serialization.Serializable

@Serializable
data class LocalMediaTrack(
    val path: String,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val album: String? = null,
    val coverBytes: ByteArray? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null)

/** Tracks without a track number last; otherwise disc (missing = 1), then track number, then name. */
val LocalMediaTrackComparator: Comparator<LocalMediaTrack> =
    compareBy<LocalMediaTrack>({ it.trackNumber == null }, { it.discNumber ?: 1 }, { it.trackNumber ?: 0 })
        .thenBy { it.name.lowercase() }

@Serializable
data class LocalMediaFolder(
    val path: String,
    val name: String,
    val tracks: List<LocalMediaTrack>) {
    val trackCount: Int
        get() = tracks.size

    /** Virtual collections (favorites...) are built on the fly: never scanned, never deleted. */
    val isVirtual: Boolean
        get() = path.startsWith(VIRTUAL_FOLDER_PREFIX)
}

const val VIRTUAL_FOLDER_PREFIX = "spotube-virtual:"
const val FAVORITES_FOLDER_PATH = "${VIRTUAL_FOLDER_PREFIX}favorites"
const val RECENT_FOLDER_PATH = "${VIRTUAL_FOLDER_PREFIX}recent"
const val MOST_PLAYED_FOLDER_PATH = "${VIRTUAL_FOLDER_PREFIX}most_played"


@Serializable
data class LocalMediaCache(
    val folders: List<LocalMediaFolder> = emptyList(),
    val indexedAtEpochMs: Long = 0)
