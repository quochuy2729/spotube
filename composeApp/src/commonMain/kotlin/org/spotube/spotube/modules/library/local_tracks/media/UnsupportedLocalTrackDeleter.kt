/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

/** For platforms without a safe way to remove local files (iOS). */
class UnsupportedLocalTrackDeleter : LocalTrackDeleter {
    override suspend fun delete(path: String): LocalTrackDeleteResult =
        LocalTrackDeleteResult.Failed("unsupported")
}
