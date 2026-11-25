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

package org.spotube.spotube.modules.shell


import org.spotube.millennium.composables.MillenniumIcon
import org.spotube.millennium.composables.MillenniumIconButton
import org.spotube.millennium.composables.MillenniumIconButtonStyle
import androidx.compose.foundation.shape.CircleShape

import org.spotube.spotube.core.ui.theming.AppTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.koin.compose.viewmodel.koinViewModel
import org.spotube.spotube.modules.library.local_tracks.user.LocalFavoriteViewModel
import org.spotube.spotube.modules.saved_tracks.SavedState
import org.spotube.spotube.modules.saved_tracks.SavedTracksViewModel
import org.spotube.spotube.modules.saved_tracks.rememberIsSavedTracks
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.IconsaxHeart
import org.spotube.spotube.resources.iconsax.IconsaxHeart2
import org.jetbrains.compose.resources.stringResource
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_like_content_description
import spotube.composeapp.generated.resources.player_unlike_content_description

@Composable
fun PlayerHeartButton(
    audioPlayerQueue: AudioPlayerQueue,
    savedTracksViewModel: SavedTracksViewModel,
    localFavoriteViewModel: LocalFavoriteViewModel = koinViewModel()) {
    val currentQueueEntry by audioPlayerQueue.currentQueueEntryFlow.collectAsStateWithLifecycle()
    val localPath = (currentQueueEntry as? QueueEntry.LocalTrack)?.url
    if (localPath != null) {
        val localFavorites by localFavoriteViewModel.favorites.collectAsStateWithLifecycle()
        val isLocalFavorite = localPath in localFavorites
        HeartIconButton(
            isLiked = isLocalFavorite,
            enabled = true,
            loading = false,
            onClick = { localFavoriteViewModel.toggle(localPath) })
        return
    }
    val currentTrackId = (currentQueueEntry as? QueueEntry.StreamingTrack)?.track?.id
    val isSavedTrackState =
        rememberIsSavedTracks(trackIds = currentTrackId?.let { listOf(it) } ?: emptyList())
    val savedTrackIds by savedTracksViewModel.savedTrackIdsFlow.collectAsStateWithLifecycle()
    val pendingSavedTrackIds by savedTracksViewModel.pendingSavedTrackIds.collectAsStateWithLifecycle()

    val isInSavedIds = if (currentTrackId != null) currentTrackId in savedTrackIds else false
    val isLiked = if (isSavedTrackState is SavedState.Success) {
        isSavedTrackState.data.firstOrNull() == true || isInSavedIds
    } else {
        isInSavedIds
    }

    HeartIconButton(
        isLiked = isLiked,
        enabled = isSavedTrackState is SavedState.Success,
        loading = currentTrackId != null && currentTrackId in pendingSavedTrackIds,
        onClick = { currentTrackId?.let { savedTracksViewModel.toggleSavedTrack(it, isLiked) } })
}

@Composable
private fun HeartIconButton(isLiked: Boolean, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    MillenniumIconButton(
        onClick = onClick,
        enabled = enabled,
        loading = loading,
        style = MillenniumIconButtonStyle.Secondary) {
        MillenniumIcon(
            imageVector = if (isLiked) Iconsax.IconsaxHeart2 else Iconsax.IconsaxHeart,
            contentDescription = stringResource(if (isLiked) Res.string.player_unlike_content_description else Res.string.player_like_content_description),
            tint = if (isLiked) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant)
    }
}
