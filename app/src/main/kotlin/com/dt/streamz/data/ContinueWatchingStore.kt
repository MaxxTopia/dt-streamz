package com.dt.streamz.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class WatchEntry(
    val providerId: String,
    val titleId: String,
    val titleName: String,
    val poster: String?,
    val episodeId: String,
    val episodeNumber: Int,
    val timestamp: Long,
    // Stored as MediaKind.name (Anime / Movie / Series). Default null lets
    // entries persisted before this field shipped deserialize cleanly; they
    // only show on Home until the user re-watches and re-records them.
    val kind: String? = null,
    // Resume support. positionMs = how far into [episodeId] the user got;
    // durationMs = total length once the player knows it. Both default 0 so
    // entries written before resume shipped deserialize cleanly (they just
    // start from the beginning). 0 duration = unknown (live / not yet ready).
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

private val Context.continueWatchingStore by preferencesDataStore(name = "continue_watching")
private val KEY = stringPreferencesKey("entries")
private const val MAX_ENTRIES = 20

/**
 * Persistent most-recent-first log of plays, keyed uniquely by
 * (providerId, titleId). Re-watching the same title bumps its entry to
 * the top rather than duplicating. Serialized as a single JSON string
 * so Preferences can hold it without custom adapters.
 */
class ContinueWatchingStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(WatchEntry.serializer())

    val entries: Flow<List<WatchEntry>> = context.continueWatchingStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    suspend fun record(entry: WatchEntry) {
        context.continueWatchingStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY]?.let { json.decodeFromString(listSerializer, it) }
            }.getOrNull() ?: emptyList()
            val deduped = current.filterNot {
                it.providerId == entry.providerId && it.titleId == entry.titleId
            }
            val merged = (listOf(entry) + deduped).take(MAX_ENTRIES)
            prefs[KEY] = json.encodeToString(listSerializer, merged)
        }
    }

    /** Current saved entry for a title, or null. Reads a single snapshot. */
    suspend fun find(providerId: String, titleId: String): WatchEntry? =
        entries.first().firstOrNull { it.providerId == providerId && it.titleId == titleId }

    /**
     * Update only the resume position/duration of the matching entry, in
     * place, without reordering Continue Watching. No-ops if the stored
     * entry is for a different episode (the user moved on) or is absent.
     */
    suspend fun updatePosition(
        providerId: String,
        titleId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        context.continueWatchingStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY]?.let { json.decodeFromString(listSerializer, it) }
            }.getOrNull() ?: return@edit
            var changed = false
            val updated = current.map { e ->
                if (e.providerId == providerId && e.titleId == titleId && e.episodeId == episodeId) {
                    changed = true
                    e.copy(
                        positionMs = positionMs,
                        durationMs = if (durationMs > 0) durationMs else e.durationMs,
                    )
                } else {
                    e
                }
            }
            if (changed) prefs[KEY] = json.encodeToString(listSerializer, updated)
        }
    }

    suspend fun remove(providerId: String, titleId: String) {
        context.continueWatchingStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY]?.let { json.decodeFromString(listSerializer, it) }
            }.getOrNull() ?: return@edit
            val filtered = current.filterNot {
                it.providerId == providerId && it.titleId == titleId
            }
            if (filtered.isEmpty()) prefs.remove(KEY)
            else prefs[KEY] = json.encodeToString(listSerializer, filtered)
        }
    }

    suspend fun clear() {
        context.continueWatchingStore.edit { it.remove(KEY) }
    }
}
