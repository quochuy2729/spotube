/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.user

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Favourite local tracks, keyed by the track `path` (the only stable identity a local track has).
 *
 * The set is read from disk once and then kept in memory, updated from the result of each edit.
 * Collecting `dataStore.data` directly is not reliable here: a collector that subscribes while an
 * edit is being written can miss that edit for good (reproduced in LocalCollectionsTest), which
 * left the heart button / Favorites folder stale. This repository is the only writer of its key.
 */
open class LocalFavoritesRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = SetSerializer(String.serializer())

    /** Null until the stored set has been read. */
    private val state = MutableStateFlow<Set<String>?>(null)
    private val editMutex = Mutex()
    private val loaded = scope.launch {
        val stored = runCatching { decode(dataStore.data.first()[KEY]) }.getOrDefault(emptySet())
        state.compareAndSet(null, stored)
    }

    open val favorites: Flow<Set<String>> = state.filterNotNull()

    open fun isFavorite(path: String): Flow<Boolean> = favorites.map { path in it }.distinctUntilChanged()

    open suspend fun toggle(path: String) {
        editMutex.withLock {
            loaded.join()
            val prefs = dataStore.edit { prefs ->
                val current = decode(prefs[KEY])
                prefs[KEY] = json.encodeToString(serializer, if (path in current) current - path else current + path)
            }
            state.value = decode(prefs[KEY])
        }
    }

    private fun decode(raw: String?): Set<String> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptySet()

    private companion object {
        val KEY = stringPreferencesKey("local_favorite_paths")
    }
}
