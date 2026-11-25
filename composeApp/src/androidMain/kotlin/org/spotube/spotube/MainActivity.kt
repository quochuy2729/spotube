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

package org.spotube.spotube

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.mp.KoinPlatform
import org.spotube.spotube.modules.library.local_tracks.media.AndroidDeleteRequestLauncher
import org.spotube.spotube.core.deeplink.ExternalUriHandler
import org.spotube.spotube.core.newpipe.NewPipeDownloader
import org.spotube.spotube.core.paths.Paths
import org.spotube.spotube.media.PlaybackService
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class MainActivity : ComponentActivity() {
    private val deleteRequests: AndroidDeleteRequestLauncher by lazy { KoinPlatform.getKoin().get() }
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> deleteRequests.onResult(result.resultCode == Activity.RESULT_OK) }
    private val launchDeleteRequest: (android.content.IntentSender) -> Unit =
        { sender -> deleteLauncher.launch(IntentSenderRequest.Builder(sender).build()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        deleteRequests.attach(launchDeleteRequest)
        NewPipeDownloader.init(Paths())
        
        // Plain start from the foreground; Media3 promotes PlaybackService itself once playback starts
        startService(Intent(this, PlaybackService::class.java))
        
        intent?.dataString?.let(ExternalUriHandler::onNewUri)
        requestHighRefreshRate()
        setContent {
            App()
        }
    }

    /**
     * Android defaults many apps to 60 Hz. Ask for the display's highest refresh rate at the
     * current resolution (e.g. 120 Hz) so scrolling is smooth. The system may still lower it
     * (battery saver, "Smoothness" setting), this is a preference, not a guarantee.
     */
    private fun requestHighRefreshRate() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
    }

    override fun onDestroy() {
        deleteRequests.detach(launchDeleteRequest)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(ExternalUriHandler::onNewUri)
    }
}