/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.lyrics

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsResponse
import org.spotube.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import org.spotube.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumType
import org.spotube.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.core.lyrics.LrcParser

/**
 * Reads `<name>.lrc` next to the audio file. Only real file paths qualify (desktop, or Android
 * tracks whose path is a plain file); `content://` and other URIs are skipped.
 */
internal fun readSidecarLyrics(trackPath: String, fileSystem: FileSystem = FileSystem.SYSTEM): LyricsResponse? {
    if ("://" in trackPath || trackPath.startsWith("content:")) return null
    return runCatching {
        val dot = trackPath.lastIndexOf('.')
        val sep = maxOf(trackPath.lastIndexOf('/'), trackPath.lastIndexOf('\\'))
        val base = if (dot > sep) trackPath.substring(0, dot) else trackPath
        val lrcPath = "$base.lrc".toPath()
        val size = fileSystem.metadataOrNull(lrcPath)?.takeIf { it.isRegularFile }?.size ?: return null
        // A real .lrc is a few KB; never pull a huge (misnamed) file into memory.
        if (size > MAX_SIDECAR_BYTES) return null
        val text = fileSystem.read(lrcPath) { readUtf8() }
        if (text.isBlank()) return null
        val synced = LrcParser.parse(text).takeIf { it.isNotEmpty() }
        LyricsResponse(syncedLyrics = synced, plainLyrics = LrcParser.toPlainText(text).takeIf { it.isNotBlank() })
    }.getOrNull()
}

private const val MAX_SIDECAR_BYTES = 1L shl 20

/** Builds the metadata the lyrics plugins search with from a local file's tags. */
internal fun QueueEntry.LocalTrack.toLyricsSearchTrack(): MetadataTrack {
    val artistList = artists.filter { it.isNotBlank() }.map {
        MetadataArtist.Basic(id = "local:$it", name = it, thumbnails = emptyList(), externalUri = null)
    }
    val albumTitle = album?.takeIf { it.isNotBlank() }
    return MetadataTrack(
        id = url,
        title = name,
        durationMs = duration,
        trackNumber = null,
        discNumber = null,
        artists = artistList,
        album = albumTitle?.let {
            MetadataAlbum.Detailed(
                releaseDate = null, genres = emptyList(), trackCount = 0,
                id = "local:$it", title = it, description = null, thumbnails = emptyList(),
                albumType = MetadataAlbumType.Album, artists = artistList, externalUri = null)
        },
        thumbnails = null,
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null)
}
