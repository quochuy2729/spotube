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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.spotube.spotube.core.audioplayer.QueueEntry
import kotlin.time.Clock

@Serializable
data class LocalPlayStat(val count: Int = 0, val lastPlayedAt: Long = 0)

/**
 * Per-track listen counts for local music, keyed by track `path`. Updates are applied in memory
 * at once and written to disk after [debounceMs] so a burst of listens costs one write.
 */
open class LocalPlayStatsRepository(
    private val dataStore: DataStore<Preferences>,
    listens: SharedFlow<QueueEntry>? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val debounceMs: Long = 2_000L,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), LocalPlayStat.serializer())

    private val _stats = MutableStateFlow<Map<String, LocalPlayStat>>(emptyMap())
    open val stats: StateFlow<Map<String, LocalPlayStat>> = _stats.asStateFlow()

    private var persistJob: Job? = null
    private val loaded = scope.launch {
        val stored = runCatching {
            dataStore.data.first()[KEY]?.let { json.decodeFromString(serializer, it) }
        }.getOrNull().orEmpty()
        // Listens recorded while loading must not be lost.
        _stats.update { current -> merge(stored, current) }
    }

    init {
        if (listens != null) {
            scope.launch {
                listens.collect { entry -> (entry as? QueueEntry.LocalTrack)?.let { record(it.url) } }
            }
        }
    }

    open fun record(path: String) {
        val now = nowMs()
        _stats.update { map ->
            val old = map[path]
            map + (path to LocalPlayStat((old?.count ?: 0) + 1, now))
        }
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(debounceMs)
            flush()
        }
    }

    /** Writes the current stats now (also what the debounce calls). */
    suspend fun flush() {
        loaded.join()
        val snapshot = _stats.value
        dataStore.edit { it[KEY] = json.encodeToString(serializer, snapshot) }
    }

    private fun merge(stored: Map<String, LocalPlayStat>, live: Map<String, LocalPlayStat>): Map<String, LocalPlayStat> {
        if (live.isEmpty()) return stored
        val result = stored.toMutableMap()
        // Live entries started from zero while loading, so their counts add to what was stored.
        for ((path, stat) in live) {
            val old = stored[path]
            result[path] = LocalPlayStat((old?.count ?: 0) + stat.count, maxOf(old?.lastPlayedAt ?: 0, stat.lastPlayedAt))
        }
        return result
    }

    private companion object {
        val KEY = stringPreferencesKey("local_play_stats")
    }
}
