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
    // Provider-supplied episode name. Older saved entries deserialize with
    // null and retain the normal "Ep N" fallback.
    val episodeTitle: String? = null,
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
    // Completed episode ids for this title. Continue Watching intentionally
    // keeps one current row per title, so this small history travels with that
    // row instead of being mistaken for another home-screen entry.
    val watchedEpisodeIds: List<String> = emptyList(),
)

private val Context.continueWatchingStore by preferencesDataStore(name = "continue_watching")
private val KEY = stringPreferencesKey("entries")
private const val MAX_ENTRIES = 20
private const val FINISHED_MOVIE_END_GUARD_MS = 20_000L
private const val FINISHED_EPISODE_END_GUARD_MS = 20_000L

/** True when an episodic video reached its end guard. */
internal fun WatchEntry.isEpisodeFinished(): Boolean {
    if (durationMs <= 0) return false
    val endThreshold = (durationMs - FINISHED_EPISODE_END_GUARD_MS).coerceAtLeast(0L)
    return positionMs >= endThreshold
}

/** History plus the current entry when its saved position is effectively done. */
internal fun WatchEntry.completedEpisodeIds(): Set<String> =
    (watchedEpisodeIds + episodeId.takeIf { isEpisodeFinished() }).filterNotNull().toSet()

/** Movies should leave Continue Watching once the user is effectively at the end. */
private fun WatchEntry.isFinishedMovie(): Boolean {
    // Entries written before MediaKind was persisted can still be identified by
    // the movie sentinel episode id used by the movie providers.
    val movie = kind == MediaKind.Movie.name || (kind == null && episodeId == "movie")
    if (!movie || durationMs <= 0) return false
    val endThreshold = (durationMs - FINISHED_MOVIE_END_GUARD_MS).coerceAtLeast(0L)
    return positionMs >= endThreshold
}

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
        canonicalEntries(decodeEntries(prefs[KEY]))
            .filterNot { it.isFinishedMovie() }
    }

    suspend fun record(entry: WatchEntry) {
        context.continueWatchingStore.edit { prefs ->
            val canonicalEntry = entry.canonicalizedForCatalog()
            val rawCurrent = decodeEntries(prefs[KEY])
            val current = canonicalEntries(rawCurrent)
            val deduped = current.filterNot {
                (it.providerId == canonicalEntry.providerId && it.titleId == canonicalEntry.titleId) ||
                    it.isFinishedMovie()
            }
            val merged = (listOf(canonicalEntry) + deduped).take(MAX_ENTRIES)
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
            val rawCurrent = decodeEntries(prefs[KEY])
            if (rawCurrent.isEmpty()) return@edit
            val current = canonicalEntries(rawCurrent)
            var changed = current != rawCurrent
            val updated = current.mapNotNull { e ->
                if (e.providerId == providerId && e.titleId == titleId && e.episodeId == episodeId) {
                    changed = true
                    val savedDuration = if (durationMs > 0) durationMs else e.durationMs
                    val finished = savedDuration > 0 &&
                        positionMs >= (savedDuration - FINISHED_EPISODE_END_GUARD_MS).coerceAtLeast(0L)
                    val watched = if (finished) {
                        (e.watchedEpisodeIds + e.episodeId).distinct()
                    } else {
                        e.watchedEpisodeIds
                    }
                    e.copy(
                        positionMs = positionMs,
                        durationMs = savedDuration,
                        watchedEpisodeIds = watched,
                    ).takeUnless { it.isFinishedMovie() }
                } else if (e.isFinishedMovie()) {
                    changed = true
                    null
                } else {
                    e
                }
            }
            if (changed) prefs[KEY] = json.encodeToString(listSerializer, updated)
        }
    }

    /** Mark the current episode complete when an embed reports a clean end. */
    suspend fun markEpisodeWatched(providerId: String, titleId: String, episodeId: String) {
        context.continueWatchingStore.edit { prefs ->
            val rawCurrent = decodeEntries(prefs[KEY])
            if (rawCurrent.isEmpty()) return@edit
            val current = canonicalEntries(rawCurrent)
            var changed = current != rawCurrent
            val updated = current.map { e ->
                if (e.providerId == providerId && e.titleId == titleId && e.episodeId == episodeId) {
                    val watched = (e.watchedEpisodeIds + episodeId).distinct()
                    if (watched != e.watchedEpisodeIds) changed = true
                    e.copy(watchedEpisodeIds = watched)
                } else {
                    e
                }
            }
            if (changed) prefs[KEY] = json.encodeToString(listSerializer, updated)
        }
    }

    suspend fun remove(providerId: String, titleId: String) {
        context.continueWatchingStore.edit { prefs ->
            val rawCurrent = decodeEntries(prefs[KEY])
            if (rawCurrent.isEmpty()) return@edit
            val current = canonicalEntries(rawCurrent)
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

    private fun decodeEntries(raw: String?): List<WatchEntry> =
        raw?.let { value -> runCatching { json.decodeFromString(listSerializer, value) }.getOrNull() }
            ?: emptyList()

    /** Canonicalize and collapse aliases before any UI or position update sees them. */
    private fun canonicalEntries(entries: List<WatchEntry>): List<WatchEntry> =
        entries
            .map { it.canonicalizedForCatalog() }
            .distinctBy { "${it.providerId}:${it.titleId}" }
}
