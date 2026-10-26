package com.dt.streamz.scraper

import android.content.Context
import android.content.SharedPreferences
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.MediaKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, TTL-bounded cache for [Provider.browse] results.
 *
 * Why: the Home / Anime / Movies / TV / Latest / Genres tabs each mount a
 * fresh set of BrowseRow composables, and tab selection fires on *focus*
 * (`Tab(onFocus = …)` in DtApp). D-padding across the tab bar therefore
 * re-mounts every screen it passes through and re-hits every provider's
 * browse() endpoint over the network — sluggish and wasteful on the box.
 * Caching keeps tab switches instant for [TTL_MS] instead of re-scraping.
 *
 * It also dedupes *concurrent* callers: on first Home paint, every BrowseRow
 * plus RandomPickCard can ask for the same provider at once. The per-id
 * [Mutex] makes the first caller fetch while the rest await its result, so
 * one network round-trip serves all of them instead of N racing requests.
 *
 * Successful results are also persisted as a small last-known-good snapshot.
 * If a provider later fails, callers receive that snapshot with [Snapshot.stale]
 * set instead of an empty row. A later refresh can replace it without making
 * the stale catalog look current.
 */
object BrowseCache {

    /** How long a successful browse result is served from memory. */
    private const val TTL_MS = 5 * 60 * 1000L

    data class Snapshot(
        val items: List<SearchResult>,
        val fetchedAtMs: Long,
        val stale: Boolean,
        val errorMessage: String? = null,
    )

    private class Entry(
        val items: List<SearchResult>,
        val atMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var prefs: SharedPreferences? = null

    /** Initialise the disk-backed last-known-good catalog cache once at app startup. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    suspend fun browse(provider: Provider): List<SearchResult> {
        return snapshot(provider).items
    }

    /**
     * Return a fresh catalog when possible, otherwise serve the last successful
     * catalog from disk/memory and mark it stale. A stale catalog is preferable
     * to an empty home screen when a provider has a transient outage.
     */
    suspend fun snapshot(provider: Provider): Snapshot {
        fresh(provider.id)?.let { return Snapshot(it.items, it.atMs, stale = false) }
        // Single-flight per provider: first caller fetches, others wait.
        val lock = locks.getOrPut(provider.id) { Mutex() }
        return lock.withLock {
            fresh(provider.id)?.let { return@withLock Snapshot(it.items, it.atMs, stale = false) }
            val fallback = cache[provider.id] ?: loadPersisted(provider.id)?.also {
                cache[provider.id] = it
            }
            runCatching { provider.browse() }
                .map { items ->
                    val now = System.currentTimeMillis()
                    val entry = Entry(items, now)
                    cache[provider.id] = entry
                    persist(provider.id, entry)
                    Snapshot(items, now, stale = false)
                }
                .getOrElse { error ->
                    fallback?.let {
                        Snapshot(
                            items = it.items,
                            fetchedAtMs = it.atMs,
                            stale = true,
                            errorMessage = error.message ?: error::class.simpleName,
                        )
                    } ?: throw error
            }
        }
    }

    /** Return the last known catalog without triggering a network request. */
    fun lastGood(providerId: String): Snapshot? {
        val entry = cache[providerId] ?: loadPersisted(providerId)?.also { cache[providerId] = it }
        return entry?.let { Snapshot(it.items, it.atMs, stale = true) }
    }

    private fun fresh(id: String): Entry? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.atMs < TTL_MS }

    /** Drop cached browse results — optionally only for one provider. */
    fun invalidate(providerId: String? = null) {
        if (providerId == null) cache.clear() else cache.remove(providerId)
    }

    private fun persist(providerId: String, entry: Entry) {
        val target = prefs ?: return
        runCatching {
            val array = JSONArray()
            entry.items.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    putNullable("poster", item.poster)
                    putNullable("year", item.year)
                    put("kind", item.kind.name)
                    put("isLive", item.isLive)
                    putNullable("subtitle", item.subtitle)
                    putNullable("publishedLabel", item.publishedLabel)
                    putNullable("progressFraction", item.progressFraction)
                    putNullable("backdrop", item.backdrop)
                })
            }
            target.edit()
                .putLong("${providerId}_at", entry.atMs)
                .putString("${providerId}_items", array.toString())
                .apply()
        }
    }

    private fun loadPersisted(providerId: String): Entry? {
        val target = prefs ?: return null
        val atMs = target.getLong("${providerId}_at", 0L)
        val raw = target.getString("${providerId}_items", null) ?: return null
        if (atMs <= 0L) return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SearchResult(
                            providerId = providerId,
                            id = item.getString("id"),
                            title = item.getString("title"),
                            poster = item.optString("poster").takeIf { it.isNotBlank() },
                            year = item.optInt("year", 0).takeIf { it != 0 },
                            kind = MediaKind.valueOf(item.getString("kind")),
                            isLive = item.optBoolean("isLive", false),
                            subtitle = item.optString("subtitle").takeIf { it.isNotBlank() },
                            publishedLabel = item.optString("publishedLabel").takeIf { it.isNotBlank() },
                            progressFraction = item.optDouble("progressFraction", Double.NaN)
                                .takeIf { !it.isNaN() }?.toFloat(),
                            backdrop = item.optString("backdrop").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }.let { Entry(it, atMs) }
        }.getOrNull()
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private const val PREFS = "browse_catalog_cache"
}
