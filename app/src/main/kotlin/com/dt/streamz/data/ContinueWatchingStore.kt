package com.dt.streamz.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
