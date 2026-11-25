/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer.trim

import kotlinx.serialization.Serializable
import org.spotube.spotube.core.audioplayer.QueueEntry

/** The part of a track that is played. The audio itself is never modified. */
@Serializable
data class TrackTrim(
    val startMs: Long = 0L,
    /** Null means "play to the end". */
    val endMs: Long? = null) {
    val isEmpty: Boolean get() = startMs <= 0L && endMs == null
}

/** Stable identity of a track across restarts: metadata id when streaming, file path when local. */
val QueueEntry.trimKey: String
    get() = when (this) {
        is QueueEntry.StreamingTrack -> "track:${track.id}"
        is QueueEntry.LocalTrack -> "local:$url"
    }
