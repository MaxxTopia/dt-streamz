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
class YouTubeProvider : Provider {

    override val id = "youtube"
    override val displayName = "YouTube"
    override val supportsYouTube = true

    private val piped = PipedClient()
    private val innertube = InnerTubeClient()
    private val service = ServiceList.YouTube
    private val cache = mutableMapOf<String, CachedItem>()

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        // The home feed is a "recommended" page — it should look like normal
        // YouTube recommendations, NOT a wall of live broadcasts in random
        // languages. So we drop live streams and non-English titles before
        // taking the first 24. (Live channels you actually want are still
        // reachable via search, where a searched creator's live stream is
        // floated to the top.)
        val piped = runCatching { piped.trending() }.getOrNull()
        if (!piped.isNullOrEmpty()) {
            return@withContext piped
                .filterNot { it.isLive }
                .filter { isLikelyEnglish(it.title) }
                .take(24)
                .map { it.toSearchResult() }
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
                .filterNot { it.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM }
                .filter { isLikelyEnglish(it.name ?: "") }
                .take(24)
                .map { it.toSearchResult() }
        }.onFailure { DebugLog.w(TAG, "NewPipe browse() failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // Tier 0: InnerTube (direct YouTube, hl=en/gl=US). English titles,
        // and it returns CHANNELS too. If a channel matches what you typed,
        // we lead with that creator's newest uploads (newest -> oldest, from
        // the public RSS feed) so "search a creator -> see their latest" just
        // works, then append the general video results.
        val itResult = runCatching { innertube.search(query) }.getOrNull()
        if (itResult != null && (itResult.videos.isNotEmpty() || itResult.channels.isNotEmpty())) {
            val out = mutableListOf<SearchResult>()
            val seen = mutableSetOf<String>()
            val matched = itResult.channels.firstOrNull { channelMatchesQuery(query, it.name) }
            if (matched != null) {
                val newest = runCatching { innertube.channelNewest(matched.channelId) }
                    .getOrDefault(emptyList())
                for (v in newest) if (seen.add(v.videoId)) out.add(v.toSearchResult())
            }
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

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val videoId = videoIdOf(titleId)

            // Play YouTube through its OWN embedded player inside the WebView,
            // with the in-app adblock stripping ad domains. This is the robust
            // path: YouTube handles its own signature cipher + bot tokens, so
            // we no longer depend on
            //   - Piped (its one surviving instance returns junk LBRY-proxied
            //     URLs, not real YouTube streams), or
            //   - NewPipeExtractor (its extract path calls a 33+ URLEncoder
            //     overload that crashes on this box's Android).
            // Single source -> one tap, no picker.
            listOf(
                StreamSource(
                    url = "https://www.youtube.com/embed/$videoId" +
                        "?autoplay=1&playsinline=1&rel=0&modestbranding=1&fs=1",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "YouTube",
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
