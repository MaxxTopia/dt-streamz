package com.dt.streamz.scraper

import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Binge-watching helpers: a short-lived cache of title details + the next
 * episode's resolved streams, so the Next button / auto-play-on-end land on
 * the following episode instantly instead of re-running the (for anime,
 * WebView-based and slow) details + stream scrape.
 *
 * Flow: while an episode plays, the host calls [prefetchNext]; it resolves
 * the next episode's streams in the background and stashes them. When the
 * user advances, [details] hits the cached episode list and [takeStreams]
 * consumes the prefetched streams — no scrape on the hot path.
 *
 * Everything degrades gracefully: a miss just resolves live, exactly as
 * before. Entries expire after [TTL_MS].
 */
object Binge {

    private const val TTL_MS = 5 * 60 * 1000L

    private class CachedDetails(val details: TitleDetails, val atMs: Long)
    private class CachedStreams(val sources: List<StreamSource>, val atMs: Long)

    private val detailsCache = ConcurrentHashMap<String, CachedDetails>()
    private val streamCache = ConcurrentHashMap<String, CachedStreams>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val now: Long get() = System.currentTimeMillis()

    /** Title details, memoized for [TTL_MS] (shared by prefetch + advance). */
    suspend fun details(provider: Provider, titleId: String): TitleDetails? {
        val k = "${provider.id}:$titleId"
        detailsCache[k]?.let { if (now - it.atMs < TTL_MS) return it.details }
        return runCatching { provider.details(titleId) }.getOrNull()
            ?.also { detailsCache[k] = CachedDetails(it, now) }
    }

    /** Resolve + stash the episode after [currentEpId]. Best-effort, no-op on miss. */
    suspend fun prefetchNext(provider: Provider, titleId: String, currentEpId: String) {
        val d = details(provider, titleId) ?: return
        val idx = d.episodes.indexOfFirst { it.id == currentEpId }
        val next = (if (idx >= 0) d.episodes.getOrNull(idx + 1) else null) ?: return
        val k = "${provider.id}:$titleId:${next.id}"
        if (streamCache[k]?.let { now - it.atMs < TTL_MS } == true) return
        val lock = locks.getOrPut(k) { Mutex() }
        if (!lock.tryLock()) return   // already prefetching this one
        try {
            val sources = runCatching { provider.streams(titleId, next) }.getOrDefault(emptyList())
            if (sources.isNotEmpty()) streamCache[k] = CachedStreams(sources, now)
        } finally {
            lock.unlock()
        }
    }

    /** Consume prefetched streams for an episode, or null if not ready. */
    fun takeStreams(providerId: String, titleId: String, episodeId: String): List<StreamSource>? {
        val e = streamCache.remove("$providerId:$titleId:$episodeId") ?: return null
        return if (now - e.atMs < TTL_MS && e.sources.isNotEmpty()) e.sources else null
    }
}
