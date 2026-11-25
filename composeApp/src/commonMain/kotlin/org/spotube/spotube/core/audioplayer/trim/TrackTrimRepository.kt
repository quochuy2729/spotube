/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer.trim

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Saved trims, keyed by [trimKey]. Read from disk once and then kept in memory (same approach as
 * LocalFavoritesRepository, for the same DataStore reason). The only writer of its key.
 */
open class TrackTrimRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), TrackTrim.serializer())

    private val state = MutableStateFlow<Map<String, TrackTrim>>(emptyMap())
    val trims: StateFlow<Map<String, TrackTrim>> = state.asStateFlow()

    private val editMutex = Mutex()
    private val loaded = scope.launch {
        state.value = runCatching { decode(dataStore.data.first()[KEY]) }.getOrDefault(emptyMap())
    }

    /** Saves the trim of a track; an empty trim removes it. */
    open suspend fun set(key: String, trim: TrackTrim) {
        editMutex.withLock {
            loaded.join()
            val prefs = dataStore.edit { prefs ->
                val current = decode(prefs[KEY])
                val updated = if (trim.isEmpty) current - key else current + (key to trim)
                prefs[KEY] = json.encodeToString(serializer, updated)
            }
            state.value = decode(prefs[KEY])
        }
    }

    private fun decode(raw: String?): Map<String, TrackTrim> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("track_trims")
    }
}
