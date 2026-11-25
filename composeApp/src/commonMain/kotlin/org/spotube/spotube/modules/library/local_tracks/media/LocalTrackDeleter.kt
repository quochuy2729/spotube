/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

sealed interface LocalTrackDeleteResult {
    data object Deleted : LocalTrackDeleteResult
    /** The user declined the system confirmation (Android) - nothing was removed. */
    data object Cancelled : LocalTrackDeleteResult
    data class Failed(val reason: String) : LocalTrackDeleteResult
}

/**
 * Removes one local track from the device. Desktop moves to the trash and never falls back to a
 * permanent delete; Android deletes permanently (see [isPermanent]).
 */
interface LocalTrackDeleter {
    /** True when [delete] removes the file for good (no trash), so the UI must warn clearly. */
    val isPermanent: Boolean get() = false

    suspend fun delete(path: String): LocalTrackDeleteResult
}
