package com.dt.streamz.twitch

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pinnedDataStore by preferencesDataStore(name = "twitch_pinned")
private val KEY = stringSetPreferencesKey("channels")

/**
 * User-editable list of pinned Twitch channel logins. Persists across
 * app launches via DataStore Preferences. Falls back to
 * TwitchConfig.PINNED_CHANNELS if the store is empty (first-run seed).
 */
class PinnedChannelsStore(private val context: Context) {

    val channels: Flow<List<String>> = context.pinnedDataStore.data.map { prefs ->
        val set = prefs[KEY]
        val list = when {
            set.isNullOrEmpty() -> TwitchConfig.PINNED_CHANNELS
            else -> set.toList().sorted()
        }
        list.map { it.lowercase().trim() }.filter { it.isValidChannel() }.distinct()
    }

    suspend fun add(channel: String) {
        val cleaned = channel.lowercase().trim()
        if (!cleaned.isValidChannel()) return
        context.pinnedDataStore.edit { prefs ->
            val current = prefs[KEY]?.toMutableSet() ?: TwitchConfig.PINNED_CHANNELS.toMutableSet()
            current.add(cleaned)
            prefs[KEY] = current
        }
    }

    suspend fun remove(channel: String) {
        val cleaned = channel.lowercase().trim()
        context.pinnedDataStore.edit { prefs ->
            val current = prefs[KEY]?.toMutableSet() ?: TwitchConfig.PINNED_CHANNELS.toMutableSet()
            current.remove(cleaned)
            prefs[KEY] = current
        }
    }

    private fun String.isValidChannel(): Boolean =
        length in 3..25 && all { it.isLetterOrDigit() || it == '_' }
}
