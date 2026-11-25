/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/** Moves the file to the OS trash. Never deletes permanently: no trash support means Failed. */
class JvmLocalTrackDeleter : LocalTrackDeleter {
    override suspend fun delete(path: String): LocalTrackDeleteResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext LocalTrackDeleteResult.Failed("file not found")
        try {
            if (!Desktop.isDesktopSupported() ||
                !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
            ) return@withContext LocalTrackDeleteResult.Failed("moving to the trash is not supported here")
            if (Desktop.getDesktop().moveToTrash(file)) LocalTrackDeleteResult.Deleted
            else LocalTrackDeleteResult.Failed("could not move the file to the trash")
        } catch (e: Exception) {
            LocalTrackDeleteResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}
