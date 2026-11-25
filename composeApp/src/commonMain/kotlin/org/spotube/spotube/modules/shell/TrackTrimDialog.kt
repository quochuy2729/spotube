/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import org.jetbrains.compose.resources.stringResource
import org.spotube.millennium.composables.MillenniumButton
import org.spotube.millennium.composables.MillenniumButtonStyle
import org.spotube.millennium.composables.MillenniumDialog
import org.spotube.millennium.composables.MillenniumDialogBody
import org.spotube.millennium.composables.MillenniumDialogClose
import org.spotube.millennium.composables.MillenniumDialogFooter
import org.spotube.millennium.composables.MillenniumDialogHeader
import org.spotube.millennium.composables.MillenniumDialogTitle
import org.spotube.spotube.core.ui.theming.AppTheme
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.common_close
import spotube.composeapp.generated.resources.player_trim
import spotube.composeapp.generated.resources.player_trim_description
import spotube.composeapp.generated.resources.player_trim_end
import spotube.composeapp.generated.resources.player_trim_other_track
import spotube.composeapp.generated.resources.player_trim_reset
import spotube.composeapp.generated.resources.player_trim_save
import spotube.composeapp.generated.resources.player_trim_start
import spotube.composeapp.generated.resources.player_trim_step
import spotube.composeapp.generated.resources.player_trim_use_position

@Composable
internal fun TrackTrimDialog(viewModel: TrackTrimViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState ?: return
    MillenniumDialog(visible = true, onDismissRequest = viewModel::dismiss) {
        MillenniumDialogHeader {
            MillenniumDialogTitle(stringResource(Res.string.player_trim))
            MillenniumDialogClose()
        }
        MillenniumDialogBody {
            Text(state.title, style = AppTheme.typography.titleSmall)
            Text(
                stringResource(Res.string.player_trim_description),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant)
            TrimPoint(
                label = stringResource(Res.string.player_trim_start, formatRemainingTime(state.startMs)),
                positionMs = state.positionMs,
                onAdjust = viewModel::adjustStart,
                onUsePosition = viewModel::startAtCurrentPosition)
            TrimPoint(
                label = stringResource(
                    Res.string.player_trim_end,
                    formatRemainingTime(state.endMs),
                    formatRemainingTime(state.durationMs - state.endMs)),
                positionMs = state.positionMs,
                onAdjust = viewModel::adjustEnd,
                onUsePosition = viewModel::endAtCurrentPosition)
            if (state.positionMs == null) {
                Text(
                    stringResource(Res.string.player_trim_other_track),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant)
            }
        }
        MillenniumDialogFooter {
            if (state.hasSavedTrim) {
                MillenniumButton(
                    onClick = viewModel::reset,
                    style = MillenniumButtonStyle.Secondary) { Text(stringResource(Res.string.player_trim_reset)) }
            }
            MillenniumButton(
                onClick = viewModel::dismiss,
                style = MillenniumButtonStyle.Secondary) { Text(stringResource(Res.string.common_close)) }
            MillenniumButton(onClick = viewModel::save) { Text(stringResource(Res.string.player_trim_save)) }
        }
    }
}

@Composable
private fun TrimPoint(
    label: String,
    positionMs: Long?,
    onAdjust: (Long) -> Unit,
    onUsePosition: () -> Unit) {
    Text(label, style = AppTheme.typography.bodyMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TRIM_STEPS_SECONDS.forEach { seconds ->
            MillenniumButton(
                onClick = { onAdjust(seconds * 1_000L) },
                style = MillenniumButtonStyle.Secondary,
                modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.player_trim_step, if (seconds > 0) "+$seconds" else "$seconds"))
            }
        }
    }
    MillenniumButton(
        onClick = onUsePosition,
        enabled = positionMs != null,
        style = MillenniumButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                Res.string.player_trim_use_position,
                positionMs?.let { formatRemainingTime(it) } ?: "--:--"))
    }
}

private val TRIM_STEPS_SECONDS = listOf(-5, -1, 1, 5)
