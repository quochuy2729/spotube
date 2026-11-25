/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import org.jetbrains.compose.resources.stringResource
import org.spotube.millennium.composables.MillenniumAlertDialog
import org.spotube.millennium.composables.MillenniumButton
import org.spotube.millennium.composables.MillenniumButtonStyle
import spotube.composeapp.generated.resources.*
import org.spotube.spotube.core.ui.component.ApplicationMainBar

@Composable
fun LocalFolderScreen(viewModel: LocalFolderViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    Column {
        ApplicationMainBar(
            backButton = true,
            title = { Text(state.currentFolder?.let { localFolderTitle(it.path, it.name) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        LocalTracksContent(
            state,
            onOpenFolder = {},
            onPlayTrack = viewModel::onTrackClick,
            onAddToQueue = viewModel::addToQueue,
            onDeleteTrack = viewModel::requestDelete,
            onNavigateUp = onBack,
            onPlayFolder = viewModel::playFolder,
            onShuffleFolder = viewModel::shuffleFolder,
            onExitPlayOrder = viewModel::exitPlayOrder,
            onMoveTrack = viewModel::onMove,
            onDragStart = viewModel::onDragStart,
            onDragStop = viewModel::onDragStop,
            onRequestPermission = {},
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
    pendingDelete?.let { track ->
        MillenniumAlertDialog(
            visible = true,
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(Res.string.library_local_delete_title)) },
            description = { Text(stringResource(
                if (viewModel.deleteIsPermanent) Res.string.library_local_delete_message_permanent
                else Res.string.library_local_delete_message,
                track.title
            )) }
        ) {
            MillenniumButton(onClick = viewModel::cancelDelete, style = MillenniumButtonStyle.Secondary) {
                Text(stringResource(Res.string.library_local_delete_cancel))
            }
            MillenniumButton(onClick = viewModel::confirmDelete) {
                Text(stringResource(Res.string.library_local_delete_confirm))
            }
        }
    }
}
