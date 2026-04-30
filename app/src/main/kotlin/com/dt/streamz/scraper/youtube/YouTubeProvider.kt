package com.dt.streamz.scraper.youtube

import android.util.Log
import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube provider backed by NewPipeExtractor — no API key, no auth, no
 * ads. Each lookup runs the upstream YouTube watch/search/trending pages
 * through the extractor's signature-cipher and cipher-script logic, which
 * mirrors what NewPipe / Tubular do.
 *
 * The extractor breaks periodically when YouTube ships a new bundle. If
 * search/streams start returning empty, bump the NewPipeExtractor version
 * in `libs.versions.toml`.
 */
class YouTubeProvider : Provider {

    override val id = "youtube"
    override val displayName = "YouTube"
    override val supportsYouTube = true

    private val service = ServiceList.YouTube
    private val cache = mutableMapOf<String, CachedItem>()

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val kioskList = service.kioskList
            val kioskId = kioskList.defaultKioskId
            val extractor = kioskList.getExtractorById(kioskId, null)
            extractor.fetchPage()
            val items = extractor.initialPage.items.orEmpty()
            items.filterIsInstance<StreamInfoItem>()
                .take(24)
                .map { it.toSearchResult() }
        }.onFailure { Log.w(TAG, "browse() failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val extractor = service.getSearchExtractor(query, listOf("videos"), "")
            extractor.fetchPage()
            val items = extractor.initialPage.items.orEmpty()
            items.filterIsInstance<StreamInfoItem>().map { it.toSearchResult() }
        }.onFailure { Log.w(TAG, "search($query) failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val cached = cache[titleId]
        // titleId is the watch URL (NewPipeExtractor takes URLs as IDs).
        runCatching {
            val ext = service.getStreamExtractor(titleId)
            ext.fetchPage()
            TitleDetails(
                providerId = id,
                id = titleId,
                title = ext.name ?: cached?.title ?: titleId,
                poster = ext.thumbnails.firstOrNull()?.url ?: cached?.poster,
                backdrop = ext.thumbnails.lastOrNull()?.url ?: cached?.poster,
                synopsis = ext.description?.content,
                year = null,
                kind = MediaKind.Movie,
                episodes = listOf(Episode(id = "watch", number = 1, title = "Watch")),
            )
        }.getOrElse {
            Log.w(TAG, "details($titleId) failed", it)
            TitleDetails(
                providerId = id,
                id = titleId,
                title = cached?.title ?: titleId,
                poster = cached?.poster,
                backdrop = cached?.poster,
                synopsis = "Failed to fetch — extractor may be stale (try updating).",
                year = null,
                kind = MediaKind.Movie,
                episodes = listOf(Episode(id = "watch", number = 1, title = "Watch")),
            )
        }
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ext = service.getStreamExtractor(titleId)
                ext.fetchPage()
                val out = mutableListOf<StreamSource>()

                // Tier 1: progressive (audio + video muxed) — simplest for
                // ExoPlayer, no MergingMediaSource needed. YT only exposes
                // these up to 720p (itag 18 = 360p, itag 22 = 720p).
                ext.videoStreams.orEmpty().forEach { vs ->
                    val url = vs.content ?: return@forEach
                    if (url.isBlank()) return@forEach
                    out += StreamSource(
                        url = url,
                        kind = StreamKind.Mp4,
                        quality = vs.resolution,
                        serverLabel = "YouTube progressive · ${vs.resolution}",
                    )
                }

                // Tier 2: HLS — present on livestreams + some VODs.
                val hls = runCatching { ext.hlsUrl }.getOrNull()
                if (!hls.isNullOrBlank()) {
                    out += StreamSource(
                        url = hls,
                        kind = StreamKind.Hls,
                        serverLabel = "YouTube HLS",
                    )
                }

                // Tier 3: DASH manifest URL if YouTube exposes one (rare —
                // most modern videos require manifest synthesis from the
                // separate video+audio streams). DASH muxing path lives
                // in PlayerScreen.
                val dash = runCatching { ext.dashMpdUrl }.getOrNull()
                if (!dash.isNullOrBlank()) {
                    out += StreamSource(
                        url = dash,
                        kind = StreamKind.Dash,
                        serverLabel = "YouTube DASH manifest",
                    )
                }

                out
            }.onFailure { Log.w(TAG, "streams($titleId) failed", it) }.getOrDefault(emptyList())
        }

    private fun StreamInfoItem.toSearchResult(): SearchResult {
        val poster = thumbnails.firstOrNull()?.url
        val r = SearchResult(
            providerId = id,
            id = url,
            title = name ?: url,
            poster = poster,
            year = null,
            kind = MediaKind.Movie,
        )
        cache[url] = CachedItem(name ?: url, poster)
        return r
    }

    private data class CachedItem(val title: String, val poster: String?)

    companion object {
        private const val TAG = "YouTubeProvider"

        /** One-shot extractor init. Call from [com.dt.streamz.DtApplication]. */
        fun initOnce() {
            if (initialized) return
            initialized = true
            NewPipe.init(NewPipeOkHttpDownloader())
        }

        @Volatile
        private var initialized: Boolean = false
    }
}
