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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
class YouTubeProvider(
    // Learned interest terms (recent searches / watched titles) the recommended
    // grid blends with its fixed seeds. Defaults to none, so the provider still
    // works standalone (tests, cold start). See [browse].
    private val interestSeeds: suspend () -> List<String> = { emptyList() },
) : Provider {

    override val id = "youtube"
    override val displayName = "YouTube"
    override val supportsYouTube = true

    private val piped = PipedClient()
    private val innertube = InnerTubeClient()
    private val service = ServiceList.YouTube
    private val cache = mutableMapOf<String, CachedItem>()

    override suspend fun browse(): List<SearchResult> = kotlinx.coroutines.coroutineScope {
        // Recommended feed. YouTube's real trending/home browse is login- and
        // session-token-gated, Piped trending now comes back empty, and
        // NewPipe's kiosk crashes on this box's Android (java.util.stream
        // Collectors.toUnmodifiableList is API 33+). So we synthesize a
        // "popular" grid from InnerTube SEARCH (verified reliable, English)
        // across a few broad seeds, run in parallel and merged. Not
        // personalized — that's impossible without a login — but a full,
        // English, live-free grid instead of one stray video.
        // Personalized seeds (recent searches / watched titles) lead, so the
        // user's tastes surface first in the round-robin interleave; the fixed
        // seeds fill out and keep the grid varied. Cold start / personalization
        // off -> learned is empty and this is exactly the old behavior.
        val learned = runCatching { interestSeeds() }.getOrNull().orEmpty()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(3)
        val seeds = (learned + RECOMMEND_SEEDS).distinct()
        val perSeed = seeds.map { seed ->
            async(Dispatchers.IO) {
                runCatching { innertube.search(seed) }.getOrNull()?.videos.orEmpty()
            }
        }.awaitAll()

        val out = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        // Interleave seeds round-robin so the grid is varied (not 24 music
        // videos because "music" resolved first).
        val maxLen = perSeed.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (videos in perSeed) {
                val v = videos.getOrNull(i) ?: continue
                if (v.isLive || !isLikelyEnglish(v.title)) continue
                if (seen.add(v.videoId)) out.add(v.toSearchResult())
                if (out.size >= 24) return@coroutineScope out
            }
        }
        if (out.isNotEmpty()) return@coroutineScope out

        // Last-ditch fallback: Piped trending (rarely up now).
        val piped = runCatching { piped.trending() }.getOrNull()
        piped.orEmpty()
            .filterNot { it.isLive }
            .filter { isLikelyEnglish(it.title) }
            .take(24)
            .map { it.toSearchResult() }
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // Tier 0: InnerTube (direct YouTube, hl=en/gl=US) — English titles,
        // YouTube's own relevance order. We keep that order as-is: it already
        // surfaces the searched creator's videos near the top, and because a
        // creator often has several channels (main / VODs / shorts), pinning
        // just one channel's uploads (an earlier approach) actually hid the
        // others. Relevance covers them all.
        val itResult = runCatching { innertube.search(query) }.getOrNull()
        if (itResult != null && itResult.videos.isNotEmpty()) {
            val out = mutableListOf<SearchResult>()
            val seen = mutableSetOf<String>()
            for (v in itResult.videos) if (seen.add(v.videoId)) out.add(v.toSearchResult())
            if (out.isNotEmpty()) return@withContext out
        }
        DebugLog.i(TAG, "InnerTube search($query) empty/null — falling back to Piped")

        // Tier 1: Piped search. We DON'T blanket-promote every live stream —
        // that buries the relevant video under random live results. Instead we
        // only float a live broadcast to the top when its CHANNEL matches what
        // you searched (i.e. you looked up a creator and they happen to be
        // live). Everything else keeps YouTube's own relevance order.
        val piped = runCatching { piped.search(query) }.getOrNull()
        if (!piped.isNullOrEmpty()) {
            val english = piped.filter { isLikelyEnglish(it.title) }
            val (searchedLive, rest) = english.partition {
                it.isLive && channelMatchesQuery(query, it.uploaderName)
            }
            return@withContext (searchedLive + rest).map { it.toSearchResult() }
        }
        DebugLog.i(TAG, "Piped search($query) empty/null — falling back to NewPipeExtractor")

        // Tier 2: NewPipeExtractor search.
        runCatching {
            val extractor = service.getSearchExtractor(query, listOf("all"), "")
            extractor.fetchPage()
            val items = extractor.initialPage.items.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .filter { isLikelyEnglish(it.name ?: "") }
            val (searchedLive, rest) = items.partition {
                it.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM &&
                    channelMatchesQuery(query, it.uploaderName)
            }
            (searchedLive + rest).map { it.toSearchResult() }
        }.onFailure { DebugLog.w(TAG, "NewPipe search($query) failed", it) }.getOrDefault(emptyList())
    }

    /**
     * True when [uploader] looks like the thing the user typed — used to
     * decide whether a live broadcast should jump to the top of search
     * results. Strips case + non-alphanumerics and checks containment either
     * way, so "xqc" matches "xQc" and "ludwig" matches "Ludwig Ahgren".
     */
    /**
     * Heuristic "is this title English?" used to keep foreign-language
     * videos off the recommended feed and out of search results. We don't
     * try to distinguish English from other Latin-script languages
     * (Spanish/French/etc.) — that's error-prone and would wrongly drop
     * lots of legitimately-English titles. Instead we drop titles whose
     * letters are *mostly* non-Latin scripts (CJK, Cyrillic, Arabic,
     * Devanagari, Hangul, Thai, Hebrew, Greek, …), which is what actually
     * "pops up in a different language" on the box.
     *
     * A title with only digits/symbols/emoji (no letters at all) is kept —
     * better a false keep than dropping a legit clip with a stylised name.
     */
    internal fun isLikelyEnglish(title: String): Boolean {
        var latin = 0
        var nonLatin = 0
        for (ch in title) {
            if (!Character.isLetter(ch)) continue
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL,
                -> latin++
                else -> nonLatin++
            }
        }
        val total = latin + nonLatin
        if (total == 0) return true
        // Keep when non-Latin letters are a minority. 0.30 tolerates the
        // odd accented/foreign word in an otherwise-English title.
        return nonLatin.toDouble() / total < 0.30
    }

    private fun channelMatchesQuery(query: String, uploader: String?): Boolean {
        if (uploader.isNullOrBlank()) return false
        val q = query.lowercase().filter { it.isLetterOrDigit() }
        val u = uploader.lowercase().filter { it.isLetterOrDigit() }
        if (q.length < 2 || u.isEmpty()) return false
        return u.contains(q) || q.contains(u)
    }

    /**
     * Type-ahead suggestions from Google's public YouTube autocomplete
     * endpoint (the `client=firefox` shape returns clean JSON instead of
     * JSONP). Returns `["query", ["sug1", "sug2", ...]]`; we surface the
     * suggestion strings. Best-effort: any failure yields an empty list so
     * the search box just shows nothing rather than erroring.
     */
    override suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val url = "https://suggestqueries.google.com/complete/search" +
            "?client=firefox&ds=yt&q=$encoded"
        val body = runCatching {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", com.dt.streamz.scraper.Http.DESKTOP_UA)
                .build()
            com.dt.streamz.scraper.Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext emptyList()
        runCatching {
            val arr = com.dt.streamz.scraper.Http.json
                .parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
            (arr.getOrNull(1) as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .take(8)
        }.getOrDefault(emptyList())
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

    /**
     * Related video IDs for autoplay, via InnerTube's watch-next column
     * (YouTube's own relatedness). Falls back to a broad search seeded on the
     * video's own id-space only if `next` comes back empty, which is rare.
     */
    override suspend fun related(titleId: String): List<String> = withContext(Dispatchers.IO) {
        val videoId = videoIdOf(titleId)
        runCatching { innertube.related(videoId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: emptyList()
    }

    /**
     * Related videos with full metadata for the in-player "Up next" rail.
     * Same watch-next signal as [related], parsed into [SearchResult]s and
     * filtered to non-live, English titles (matching the rest of the feed).
     */
    override suspend fun relatedResults(titleId: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val videoId = videoIdOf(titleId)
        runCatching { innertube.relatedVideos(videoId) }.getOrNull().orEmpty()
            .filterNot { it.isLive }
            .filter { isLikelyEnglish(it.title) }
            .map { it.toSearchResult() }
    }

    /** Confirm a video is broadcasting live right now (drops ended streams). */
    override suspend fun isLiveNow(titleId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { innertube.isLiveNow(videoIdOf(titleId)) }.getOrDefault(false)
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val videoId = videoIdOf(titleId)

            // PRIMARY: a hosted YouTube embed player (the `ytembed://` source
            // is expanded by WebPlayerScreen into a full-screen IFrame-API
            // player we control). This is the clean, app-like fullscreen
            // player — autoplay, native YouTube controls, no desktop chrome —
            // and being our own wrapper page it's same-origin, so the
            // double-press-to-seek D-pad handling works on it.
            //
            // FALLBACK: the full WATCH page. Some uploaders disable embedding
            // (IFrame error 101/150); when that happens the wrapper signals
            // native and we walk to this mirror, which plays every public
            // video. (We still avoid Piped — junk LBRY URLs — and NewPipe —
            // API 33+ URLEncoder crash on this box.)
            listOf(
                StreamSource(
                    url = "ytembed://$videoId",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "YouTube",
                    headers = emptyMap(),
                ),
                StreamSource(
                    url = "https://www.youtube.com/watch?v=$videoId",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "YouTube (page)",
                    headers = mapOf("Referer" to "https://www.youtube.com/"),
                ),
            )
        }

    private fun YtVideo.toSearchResult(): SearchResult {
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = title,
            poster = thumbnail,
            year = null,
            kind = MediaKind.Movie,
            isLive = isLive,
        )
        cache[videoId] = CachedItem(title, thumbnail)
        return r
    }

    private fun PipedVideo.toSearchResult(): SearchResult {
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = title,
            poster = thumbnail,
            year = null,
            kind = MediaKind.Movie,
            isLive = isLive,
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
            isLive = streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM,
        )
        cache[videoId] = CachedItem(name ?: videoId, poster)
        return r
    }

    private data class CachedItem(val title: String, val poster: String?)

    companion object {
        private const val TAG = "YouTubeProvider"

        // Broad seeds for the no-login "recommended" grid (see browse()).
        // Mixed categories so the merged, round-robin grid feels varied.
        private val RECOMMEND_SEEDS = listOf(
            "official music video", "movie trailer", "highlights",
            "podcast", "gaming", "documentary",
        )

        /** One-shot extractor init. Call from [com.dt.streamz.DtApplication]. */
        fun initOnce() {
            if (initialized) return
            initialized = true
            // Pin localization + content country to US English so the
            // trending kiosk and search results come back in English
            // instead of whatever the box's system locale / instance
            // region defaults to.
            NewPipe.init(
                NewPipeOkHttpDownloader(),
                org.schabi.newpipe.extractor.localization.Localization("en", "US"),
                org.schabi.newpipe.extractor.localization.ContentCountry("US"),
            )
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
