package com.dt.streamz.scraper.youtube

import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube provider — two backends with auto-fallback:
 *
 *   Tier 1 — Piped JSON API. Cheap HTTP requests against a chain of
 *            public Piped instances. Reliable even on slow boxes
 *            because there's no JS interpreter running locally; the
 *            instance does the signature dance for us. Same model the
 *            Twitch provider uses (lightweight HTTP -> playable URL).
 *   Tier 2 — NewPipeExtractor. Local cipher-script interpreter, no
 *            external dependency. Slower on cold start and breaks each
 *            time YouTube ships a new bundle, but doesn't depend on
 *            third-party infrastructure.
 *
 * Each public method tries Tier 1 first; if Tier 1 returns null/empty
 * we fall back to Tier 2. The fallback path is the same code as the
 * pre-Piped implementation, kept verbatim so we have a known-good
 * reference if Tier 1 starts misbehaving.
 *
 * `titleId` is the YouTube videoId (the eleven-character watch ID).
 * Older code keyed `titleId` to the full watch URL; we still accept
 * that shape and extract the videoId on the fly so existing
 * continue-watching entries don't break.
 */
class YouTubeProvider : Provider {

    override val id = "youtube"
    override val displayName = "YouTube"
    override val supportsYouTube = true

    private val piped = PipedClient()
    private val service = ServiceList.YouTube
    private val cache = mutableMapOf<String, CachedItem>()

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        // Tier 1: Piped trending.
        val piped = runCatching { piped.trending() }.getOrNull()
        if (!piped.isNullOrEmpty()) {
            return@withContext piped.take(24).map { it.toSearchResult() }
        }
        DebugLog.i(TAG, "Piped trending empty/null — falling back to NewPipeExtractor")

        // Tier 2: NewPipeExtractor's default kiosk.
        runCatching {
            val kioskList = service.kioskList
            val kioskId = kioskList.defaultKioskId
            val extractor = kioskList.getExtractorById(kioskId, null)
            extractor.fetchPage()
            val items = extractor.initialPage.items.orEmpty()
            items.filterIsInstance<StreamInfoItem>()
                .take(24)
                .map { it.toSearchResult() }
        }.onFailure { DebugLog.w(TAG, "NewPipe browse() failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // Tier 1: Piped search.
        val piped = runCatching { piped.search(query) }.getOrNull()
        if (!piped.isNullOrEmpty()) {
            return@withContext piped.map { it.toSearchResult() }
        }
        DebugLog.i(TAG, "Piped search($query) empty/null — falling back to NewPipeExtractor")

        // Tier 2: NewPipeExtractor search.
        runCatching {
            val extractor = service.getSearchExtractor(query, listOf("videos"), "")
            extractor.fetchPage()
            val items = extractor.initialPage.items.orEmpty()
            items.filterIsInstance<StreamInfoItem>().map { it.toSearchResult() }
        }.onFailure { DebugLog.w(TAG, "NewPipe search($query) failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val videoId = videoIdOf(titleId)
        val cached = cache[videoId]

        // Tier 1: Piped streams() returns title + thumbnail + description.
        val piped = runCatching { piped.streams(videoId) }.getOrNull()
        if (piped != null) {
            return@withContext TitleDetails(
                providerId = id,
                id = videoId,
                title = piped.title ?: cached?.title ?: videoId,
                poster = piped.thumbnailUrl ?: cached?.poster,
                backdrop = piped.thumbnailUrl ?: cached?.poster,
                synopsis = piped.description,
                year = null,
                kind = MediaKind.Movie,
                episodes = listOf(Episode(id = "watch", number = 1, title = "Watch")),
            )
        }

        // Tier 2: NewPipeExtractor stream extractor.
        runCatching {
            val ext = service.getStreamExtractor(watchUrl(videoId))
            ext.fetchPage()
            TitleDetails(
                providerId = id,
                id = videoId,
                title = ext.name ?: cached?.title ?: videoId,
                poster = ext.thumbnails.firstOrNull()?.url ?: cached?.poster,
                backdrop = ext.thumbnails.lastOrNull()?.url ?: cached?.poster,
                synopsis = ext.description?.content,
                year = null,
                kind = MediaKind.Movie,
                episodes = listOf(Episode(id = "watch", number = 1, title = "Watch")),
            )
        }.getOrElse {
            DebugLog.w(TAG, "details($videoId) failed across both backends", it)
            TitleDetails(
                providerId = id,
                id = videoId,
                title = cached?.title ?: videoId,
                poster = cached?.poster,
                backdrop = cached?.poster,
                synopsis = "Failed to fetch — backend may be unreachable.",
                year = null,
                kind = MediaKind.Movie,
                episodes = listOf(Episode(id = "watch", number = 1, title = "Watch")),
            )
        }
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val videoId = videoIdOf(titleId)

            // Tier 1: Piped streams.
            val piped = runCatching { piped.streams(videoId) }.getOrNull()
            if (piped != null) {
                val tier1 = piped.toStreamSources()
                if (tier1.isNotEmpty()) return@withContext tier1
                DebugLog.i(TAG, "Piped returned no playable streams for $videoId — trying NewPipe")
            }

            // Tier 2: NewPipeExtractor.
            runCatching {
                val ext = service.getStreamExtractor(watchUrl(videoId))
                ext.fetchPage()
                val out = mutableListOf<StreamSource>()

                // Progressive (audio + video muxed) — simplest for ExoPlayer,
                // no MergingMediaSource needed. YT only exposes these up to
                // 720p (itag 18 = 360p, itag 22 = 720p).
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
                val hls = runCatching { ext.hlsUrl }.getOrNull()
                if (!hls.isNullOrBlank()) {
                    out += StreamSource(
                        url = hls,
                        kind = StreamKind.Hls,
                        serverLabel = "YouTube HLS",
                    )
                }
                val dash = runCatching { ext.dashMpdUrl }.getOrNull()
                if (!dash.isNullOrBlank()) {
                    out += StreamSource(
                        url = dash,
                        kind = StreamKind.Dash,
                        serverLabel = "YouTube DASH manifest",
                    )
                }
                out
            }.onFailure { DebugLog.w(TAG, "streams($videoId) NewPipe failed", it) }
                .getOrDefault(emptyList())
        }

    /**
     * Build [StreamSource]s from a Piped streams response. Order: HLS for
     * livestreams, then progressive muxed MP4 (highest quality first),
     * then DASH manifest. We deliberately skip videoOnly streams because
     * those need MergingMediaSource (sync'd separate audio track) which
     * the player path doesn't currently set up — surfacing a videoOnly
     * URL would play silent footage.
     */
    private fun PipedStreams.toStreamSources(): List<StreamSource> {
        val out = mutableListOf<StreamSource>()

        if (livestream && !hls.isNullOrBlank()) {
            out += StreamSource(
                url = hls,
                kind = StreamKind.Hls,
                serverLabel = "YouTube HLS (live)",
            )
        }

        // Highest-quality muxed (progressive) MP4 first. Piped's quality
        // strings are like "720p" / "360p"; sort numerically descending.
        val muxed = videoStreams
            .filter { !it.videoOnly && !it.url.isBlank() }
            .sortedByDescending { it.quality?.removeSuffix("p")?.toIntOrNull() ?: 0 }
        muxed.forEach { stream ->
            out += StreamSource(
                url = stream.url,
                kind = StreamKind.Mp4,
                quality = stream.quality,
                serverLabel = "YouTube progressive · ${stream.quality ?: "auto"}",
            )
        }

        if (!dash.isNullOrBlank()) {
            out += StreamSource(
                url = dash,
                kind = StreamKind.Dash,
                serverLabel = "YouTube DASH",
            )
        }
        if (!hls.isNullOrBlank() && !livestream) {
            out += StreamSource(
                url = hls,
                kind = StreamKind.Hls,
                serverLabel = "YouTube HLS",
            )
        }
        return out
    }

    private fun PipedVideo.toSearchResult(): SearchResult {
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = title,
            poster = thumbnail,
            year = null,
            kind = MediaKind.Movie,
        )
        cache[videoId] = CachedItem(title, thumbnail)
        return r
    }

    private fun StreamInfoItem.toSearchResult(): SearchResult {
        val poster = thumbnails.firstOrNull()?.url
        val videoId = videoIdOf(url)
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = name ?: videoId,
            poster = poster,
            year = null,
            kind = MediaKind.Movie,
        )
        cache[videoId] = CachedItem(name ?: videoId, poster)
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

        /**
         * Accepts either a bare videoId, a `/watch?v=...` path, or a
         * full `https://www.youtube.com/watch?v=...` URL and returns
         * the eleven-char videoId. Older continue-watching entries
         * pre-Piped were keyed by full URL — keep that path working.
         */
        internal fun videoIdOf(input: String): String {
            if (input.length == 11 && input.none { it == '/' || it == '?' || it == '=' }) {
                return input
            }
            // Try ?v= or &v= extraction.
            val q = input.substringAfter("?", "")
            if (q.isNotEmpty()) {
                for (kv in q.split("&")) {
                    val eq = kv.indexOf('=')
                    if (eq <= 0) continue
                    if (kv.substring(0, eq) == "v") {
                        val v = kv.substring(eq + 1)
                        if (v.isNotBlank()) return v
                    }
                }
            }
            // youtu.be/<id>
            val short = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""").find(input)?.groupValues?.get(1)
            if (short != null) return short
            return input
        }

        internal fun watchUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=$videoId"
    }
}
