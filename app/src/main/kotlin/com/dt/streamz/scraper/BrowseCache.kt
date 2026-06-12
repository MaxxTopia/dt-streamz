package com.dt.streamz.scraper

import com.dt.streamz.data.SearchResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Failures are never cached — if [Provider.browse] throws, it propagates to
 * the caller's own runCatching and the next visit retries cleanly.
 */
object BrowseCache {

    /** How long a successful browse result is served from memory. */
    private const val TTL_MS = 5 * 60 * 1000L

    private class Entry(val items: List<SearchResult>, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun browse(provider: Provider): List<SearchResult> {
        fresh(provider.id)?.let { return it }
        // Single-flight per provider: first caller fetches, others wait.
        val lock = locks.getOrPut(provider.id) { Mutex() }
        return lock.withLock {
            fresh(provider.id) ?: provider.browse().also {
                cache[provider.id] = Entry(it, System.currentTimeMillis())
            }
        }
    }

    private fun fresh(id: String): List<SearchResult>? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.atMs < TTL_MS }?.items

    /** Drop all cached browse results — call after a manual content refresh. */
    fun invalidate() = cache.clear()
}
