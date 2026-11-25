/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

import android.content.IntentSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridge between the DI-managed deleter and the Activity: MainActivity registers an
 * ActivityResultLauncher for IntentSenders (system "allow deleting this file?" dialogs) and
 * forwards results here. Results survive Activity recreation because [pending] lives in this singleton.
 */
class AndroidDeleteRequestLauncher {
    private val mutex = Mutex()
    @Volatile private var launcher: ((IntentSender) -> Unit)? = null
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    fun attach(launch: (IntentSender) -> Unit) {
        launcher = launch
    }

    /** Only clears [launch] if it is still the attached one, so a second, older Activity being destroyed
     *  does not disconnect the Activity that is on screen. */
    fun detach(launch: (IntentSender) -> Unit) {
        if (launcher === launch) launcher = null
    }

    fun onResult(granted: Boolean) {
        pending?.complete(granted)
    }

    /** Returns whether the user approved, or null when no Activity is available to show the dialog. */
    suspend fun request(sender: IntentSender): Boolean? = mutex.withLock {
        val launch = launcher ?: return null
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        try {
            launch(sender)
            deferred.await()
        } finally {
            pending = null
        }
    }
}
