/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Library tracks are MediaStore content URIs (path = "content://media/..."), files outside the app
 * sandbox, so removal needs the user's consent through a system dialog:
 * - API 30+: MediaStore.createDeleteRequest (permanent delete, system confirmation dialog).
 * - API 29: ContentResolver.delete; on RecoverableSecurityException ask the user, then retry.
 * - API 26-28 or plain file paths (filesystem-scan fallback): ContentResolver / File.delete.
 */
class AndroidLocalTrackDeleter(
    private val context: Context,
    private val launcher: AndroidDeleteRequestLauncher) : LocalTrackDeleter {

    override val isPermanent: Boolean = true

    override suspend fun delete(path: String): LocalTrackDeleteResult = try {
        if (path.startsWith("content://")) deleteMediaStore(Uri.parse(path)) else deleteFile(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LocalTrackDeleteResult.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    private suspend fun deleteMediaStore(uri: Uri): LocalTrackDeleteResult {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender
            return when (launcher.request(sender)) {
                true -> LocalTrackDeleteResult.Deleted
                false -> LocalTrackDeleteResult.Cancelled
                null -> LocalTrackDeleteResult.Failed("no active screen to ask for permission")
            }
        }
        return try {
            deleteRow(uri)
        } catch (e: SecurityException) {
            val sender = (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                ?: return LocalTrackDeleteResult.Failed("permission denied")
            when (launcher.request(sender)) {
                true -> deleteRow(uri)
                false -> LocalTrackDeleteResult.Cancelled
                null -> LocalTrackDeleteResult.Failed("no active screen to ask for permission")
            }
        }
    }

    private suspend fun deleteRow(uri: Uri): LocalTrackDeleteResult = withContext(Dispatchers.IO) {
        if (context.contentResolver.delete(uri, null, null) > 0) LocalTrackDeleteResult.Deleted
        else LocalTrackDeleteResult.Failed("file not found")
    }

    private suspend fun deleteFile(path: String): LocalTrackDeleteResult = withContext(Dispatchers.IO) {
        val file = File(path)
        when {
            !file.exists() -> LocalTrackDeleteResult.Failed("file not found")
            file.delete() -> LocalTrackDeleteResult.Deleted
            else -> LocalTrackDeleteResult.Failed("not allowed to delete this file")
        }
    }
}
