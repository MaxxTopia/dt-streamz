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
data class FavoriteEntry(
    val providerId: String,
    val titleId: String,
    val title: String,
    val poster: String?,
    val kind: String,
    val timestamp: Long,
)

private val Context.favoritesStore by preferencesDataStore(name = "favorites")
private val KEY = stringPreferencesKey("entries")

/**
 * Bookmark list keyed by (providerId, titleId). Same storage pattern as
 * ContinueWatchingStore — single serialized JSON list in DataStore
 * Preferences so no custom adapter is needed.
 */
class FavoritesStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(FavoriteEntry.serializer())

    val entries: Flow<List<FavoriteEntry>> = context.favoritesStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    suspend fun toggle(entry: FavoriteEntry) {
        context.favoritesStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY]?.let { json.decodeFromString(listSerializer, it) }
            }.getOrNull() ?: emptyList()
            val match = current.firstOrNull {
                it.providerId == entry.providerId && it.titleId == entry.titleId
            }
            val merged = if (match != null) {
                current - match
            } else {
                listOf(entry) + current
            }
            if (merged.isEmpty()) prefs.remove(KEY)
            else prefs[KEY] = json.encodeToString(listSerializer, merged)
        }
    }
}
