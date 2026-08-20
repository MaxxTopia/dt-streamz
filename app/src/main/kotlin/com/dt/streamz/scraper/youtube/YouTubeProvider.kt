package com.dt.streamz.scraper.youtube

import com.dt.streamz.data.AudioOption
import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.SubtitleTrack
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    // YouTube videos the user actually watched (newest first), as 11-char IDs.
    // The recommended grid pulls YouTube's OWN related-video graph for these —
    // the real, login-free personalisation. YouTube-only signal; movies/shows
    // never reach it. Defaults to none so the provider works standalone. See
    // [browse].
    private val recentWatchIds: suspend () -> List<String> = { emptyList() },
    // Explicit YouTube searches are a second durable intent signal. They let
    // the grid recover a useful profile even when YouTube returns an empty
    // related-video shelf for a watched ID.
    private val recentSearchTerms: suspend () -> List<String> = { emptyList() },
    // Titles are persisted alongside watched IDs so a cold process can still
    // search the user's actual viewing context when the related shelf is thin.
    private val recentWatchTitles: suspend () -> List<String> = { emptyList() },
    // Max video height the native extractor will pick, read live from the
    // user's quality preference. Defaults to 1080 so the provider still works
    // standalone (tests, cold start). See [pickVideo].
    private val qualityCap: () -> Int = { 1080 },
) : Provider {

    override val id = "youtube"
    override val displayName = "YouTube"
    override val supportsYouTube = true

    private val piped = PipedClient()
    private val innertube = InnerTubeClient()
    private val service = ServiceList.YouTube
    private val cache = mutableMapOf<String, CachedItem>()
    private val streamCache = ConcurrentHashMap<String, CachedStreams>()

    override suspend fun browse(): List<SearchResult> = kotlinx.coroutines.coroutineScope {
        // Genuinely personalised "Recommended" grid — no login needed.
        //
        // YouTube's real home/trending browse is login- and session-token-gated,
        // Piped trending comes back empty, and NewPipe's kiosk crashes on this
        // box's Android. The login-free way to get TRUE personalisation is to
        // tap YouTube's own watch-next graph: for each video you actually
        // watched, `relatedVideos` returns what YouTube recommends after it
        // (collaborative-filtered by YouTube, not by us). The grid is driven
        // entirely by WHAT YOU WATCH on YouTube, with explicit searches as a
        // second signal; generic popular seeds only top up a sparse profile.
        //
        // The watch signal is YOUTUBE-ONLY (see [recentWatchIds]) — movies and
        // shows can't drift this grid.
        //
        // Tiers drained in priority order: related-from-watches first, then
        // explicit search interests, then generic filler only to top up.
        val watchIds = runCatching { recentWatchIds() }.getOrNull().orEmpty().distinct().take(5)
        val searchTerms = runCatching { recentSearchTerms() }.getOrNull().orEmpty()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
            .take(4)
        val watchedTitleTerms = runCatching { recentWatchTitles() }.getOrNull().orEmpty()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .take(3)

        // Tier 1: YouTube's own recommendations for what you watched.
        val relatedTier = watchIds.map { id ->
            async(Dispatchers.IO) { runCatching { innertube.relatedVideos(id) }.getOrNull().orEmpty() }
        }.awaitAll()
        // Tier 2: explicit searches from the YouTube tab. This keeps the feed
        // useful even when a watched video's related shelf is empty or blocked.
        val interestTier = (watchedTitleTerms + searchTerms)
            .distinctBy { it.lowercase() }
            .take(6)
            .map { term ->
            async(Dispatchers.IO) { runCatching { innertube.search(term) }.getOrNull()?.videos.orEmpty() }
            }.awaitAll()
        // Tier 3: generic popular filler (also the entire grid at cold start).
        val fillerTier = RECOMMEND_SEEDS.map { seed ->
            async(Dispatchers.IO) { runCatching { innertube.search(seed) }.getOrNull()?.videos.orEmpty() }
        }.awaitAll()

        val out = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        val watched = watchIds.toSet()
        // Round-robin within a tier (so the grid is varied, not 24 results from
        // one seed) and drain higher tiers first. Drops your already-watched
        // videos, live, and non-English. Returns once we've filled the grid.
        fun drain(tier: List<List<YtVideo>>): Boolean {
            val maxLen = tier.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxLen) {
                for (videos in tier) {
                    val v = videos.getOrNull(i) ?: continue
                    if (v.isLive || isLiveTitle(v.title) || v.videoId in watched ||
                        !isRecommendationCandidate(v.title)
                    ) continue
                    if (seen.add(v.videoId)) out.add(v.toSearchResult())
                    if (out.size >= 24) return true
                }
            }
            return false
        }
        if (drain(relatedTier)) return@coroutineScope out
        if (drain(interestTier)) return@coroutineScope out
        drain(fillerTier)
        if (out.isNotEmpty()) return@coroutineScope out

        // Last-ditch fallback: Piped trending (rarely up now).
        val piped = runCatching { piped.trending() }.getOrNull()
        piped.orEmpty()
            .filterNot { it.isLive || isLiveTitle(it.title) }
            .filter { isRecommendationCandidate(it.title) }
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
            val videos = markCurrentLive(itResult.videos)
            // English-only: drop any non-English title (the box should never
            // surface a foreign-language video). See [isLikelyEnglish].
            for (v in videos) {
                if (!isLikelyEnglish(v.title)) continue
                if (seen.add(v.videoId)) out.add(v.toSearchResult())
            }
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
        // First: catch Latin-script foreign languages (Spanish/French/German/…)
        // that the script test below can't see. High-confidence signals only,
        // so legit English titles with the odd café/naïve accent survive.
        if (looksForeignLatin(title)) return false

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

    /** Keep the personalized shelf from being filled by obvious placeholder
     * or malformed titles returned by public YouTube backends. This is a
     * narrow quality gate, not a topic filter: news, music, gaming, and long
     * titles remain eligible when they match the user's history. */
    private fun isRecommendationCandidate(title: String): Boolean {
        val clean = title.trim()
        if (clean.length !in 3..180 || !isLikelyEnglish(clean)) return false
        val letters = clean.filter { it.isLetterOrDigit() }
        if (letters.length >= 6 && letters.toSet().size <= 2) return false
        if (clean.count { it == '#' } > 5) return false
        return true
    }

    /**
     * Detects Latin-script *foreign* titles (the ones [isLikelyEnglish]'s
     * script test can't catch because Spanish/French/German/… all use the
     * Latin alphabet). Tuned for high precision — every signal here is one
     * that essentially never appears in a genuine English title:
     *   - Spanish-only punctuation (¿ ¡) or letters (ñ), or German ß.
     *   - Two or more *distinct* diacritic'd letters (English tops out at one
     *     loanword accent like café / naïve; "vídeo completo" has several).
     *   - A whole-word match against [FOREIGN_WORDS] (dub/language tags and
     *     function words with no English collision — ASCII-folded so
     *     "película"/"français" match).
     */
    private fun looksForeignLatin(title: String): Boolean {
        if (title.isBlank()) return false
        val lower = title.lowercase()
        if (lower.any { it == 'ñ' || it == '¿' || it == '¡' || it == 'ß' }) return true
        val accents = "áàâãäéèêëíìîïóòôõöúùûüçœæ"
        if (lower.filter { it in accents }.toSet().size >= 2) return true
        // Strip diacritics so "película" -> "pelicula", "français" -> "francais".
        val folded = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val tokens = folded.split(Regex("[^a-z]+")).filterTo(HashSet()) { it.isNotEmpty() }
        return tokens.any { it in FOREIGN_WORDS }
    }

    private fun channelMatchesQuery(query: String, uploader: String?): Boolean {
        if (uploader.isNullOrBlank()) return false
        val q = query.lowercase().filter { it.isLetterOrDigit() }
        val u = uploader.lowercase().filter { it.isLetterOrDigit() }
        if (q.length < 2 || u.isEmpty()) return false
        return u.contains(q) || q.contains(u)
    }

    private fun isLiveTitle(title: String): Boolean =
        Regex("\\blive\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)

    /** Confirm likely live search hits instead of trusting a stale badge alone. */
    private suspend fun markCurrentLive(videos: List<YtVideo>): List<YtVideo> =
        coroutineScope {
            videos.map { video ->
                if (!video.isLive && !isLiveTitle(video.title)) {
                    async { video }
                } else {
                    async {
                        val confirmed = runCatching { innertube.isLiveNow(video.videoId) }
                            .getOrNull()
                        video.copy(isLive = confirmed ?: video.isLive)
                    }
                }
            }.awaitAll()
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
        // Autoplay-next must also stay English-only. Derive from the richer
        // relatedVideos() (which carries titles) so we can drop foreign and
        // live entries, then map to IDs. We deliberately do NOT fall back to
        // the bare-id `related()` here: an ID with no title can't be language-
        // checked, and we'd rather stop autoplay (caller goes to Tabs) than
        // auto-play a foreign-language video.
        runCatching { innertube.relatedVideos(videoId) }.getOrNull().orEmpty()
            .filterNot { it.isLive }
            .filter { isLikelyEnglish(it.title) }
            .map { it.videoId }
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
            val now = System.currentTimeMillis()
            streamCache[videoId]?.takeIf { now - it.createdAtMs < STREAM_CACHE_TTL_MS }?.let {
                DebugLog.d(TAG, "stream cache hit for $videoId")
                return@withContext it.sources
            }

            // Resolve both native paths at once. The old sequential flow made a
            // healthy NewPipe result wait behind every dead Piped instance (or
            // made a healthy Piped result wait behind a cold NewPipe extractor).
            // Whichever backend returns a non-empty playable list first wins;
            // an empty result keeps the other backend in the race.
            val direct = coroutineScope {
                val results = Channel<List<StreamSource>>(capacity = 2)
                val pipedJob = async {
                    val resolved = runCatching {
                        withTimeoutOrNull(PIPED_STREAM_TIMEOUT_MS) { piped.streams(videoId) }
                            ?.let(::pipedSources)
                            ?.takeIf { it.isNotEmpty() }
                    }.onFailure { DebugLog.w(TAG, "Piped streams($videoId) failed", it) }
                        .getOrNull()
                    results.send(resolved.orEmpty())
                }
                val nativeJob = async {
                    val resolved = runCatching {
                        withTimeoutOrNull(NATIVE_STREAM_TIMEOUT_MS) { extractNative(videoId) }
                            ?.takeIf { it.isNotEmpty() }
                    }.onFailure { DebugLog.w(TAG, "native extract($videoId) failed", it) }
                        .getOrNull()
                    results.send(resolved.orEmpty())
                }
                val first = results.receive()
                val winner = if (first.isNotEmpty()) first else results.receive()
                pipedJob.cancel()
                nativeJob.cancel()
                results.close()
                winner
            }

            val sources = if (!direct.isNullOrEmpty()) {
                DebugLog.i(TAG, "native YouTube source resolved for $videoId (${direct.size} track(s))")
                direct + youtubeEmbedSources(videoId)
            } else {
                // FALLBACK: the hosted IFrame embed + watch page, used only when
                // extraction yields nothing playable (a video YouTube has fully
                // locked down). WebPlayerScreen bounds this path too, so a bot
                // wall cannot leave the box in an infinite loading state.
                DebugLog.i(TAG, "native extract empty for $videoId — falling back to embed")
                youtubeEmbedSources(videoId)
            }
            streamCache[videoId] = CachedStreams(sources, now)
            sources
        }

    private fun youtubeEmbedSources(videoId: String): List<StreamSource> = listOf(
        StreamSource(
            url = "ytembed://$videoId",
            kind = StreamKind.DirectEmbed,
            serverLabel = "YouTube (embed)",
            headers = emptyMap(),
        ),
        StreamSource(
            url = "https://www.youtube.com/watch?v=$videoId",
            kind = StreamKind.DirectEmbed,
            serverLabel = "YouTube (page)",
            headers = mapOf("Referer" to "https://www.youtube.com/"),
        ),
    )

    /** Convert Piped's adaptive tracks into the same native source model used
     * by NewPipe. Keep the box-friendly AVC/AAC preference and honor the app's
     * quality cap; use Piped's MPD/HLS only as a last resort. */
    private fun pipedSources(streams: PipedStreams): List<StreamSource> {
        if (streams.livestream && !streams.hls.isNullOrBlank()) {
            return listOf(
                StreamSource(
                    url = streams.hls,
                    kind = StreamKind.Hls,
                    serverLabel = "YouTube · Piped Live",
                    isLive = true,
                ),
            )
        }

        val cap = qualityCap().coerceAtLeast(360)
        val videos = streams.videoStreams
            .filter { it.videoOnly && pipedResolution(it) in 1..cap }
            .sortedWith(compareBy<PipedStream>({ pipedCodecRank(it) }, { -pipedResolution(it) }))
        val audio = streams.audioStreams
            .sortedWith(compareBy<PipedStream>({ pipedAudioCodecRank(it) }, { -pipedBitrate(it) }))
            .firstOrNull()
        val adaptiveVideo = videos.firstOrNull()
        if (adaptiveVideo != null && audio != null) {
            val height = pipedResolution(adaptiveVideo)
            return listOf(
                StreamSource(
                    url = adaptiveVideo.url,
                    audioUrl = audio.url,
                    kind = StreamKind.Mp4,
                    serverLabel = "YouTube · Piped ${height}p",
                ),
            )
        }

        val muxed = streams.videoStreams
            .filter { !it.videoOnly && pipedResolution(it) in 1..cap }
            .maxByOrNull { pipedResolution(it) }
        if (muxed != null) {
            val height = pipedResolution(muxed)
            return listOf(
                StreamSource(
                    url = muxed.url,
                    kind = StreamKind.Mp4,
                    serverLabel = "YouTube · Piped ${height}p",
                ),
            )
        }

        // Some instances omit the parsed track arrays but still expose a
        // valid manifest. StreamKind.Dash treats [dash] as a remote MPD URL.
        if (!streams.dash.isNullOrBlank()) {
            return listOf(
                StreamSource(
                    url = streams.dash,
                    kind = StreamKind.Dash,
                    serverLabel = "YouTube · Piped adaptive",
                ),
            )
        }
        return emptyList()
    }

    private fun pipedResolution(stream: PipedStream): Int =
        Regex("""(\d{3,4})p""").find(stream.quality.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun pipedBitrate(stream: PipedStream): Int =
        Regex("""(\d{2,4})""").find(stream.quality.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun pipedCodecRank(stream: PipedStream): Int {
        val codec = (stream.format.orEmpty() + " " + stream.mimeType.orEmpty()).lowercase()
        return when {
            "avc" in codec || "h264" in codec -> 0
            "vp9" in codec || "vp09" in codec -> 1
            "av01" in codec || "av1" in codec -> 3
            else -> 2
        }
    }

    private fun pipedAudioCodecRank(stream: PipedStream): Int {
        val codec = (stream.format.orEmpty() + " " + stream.mimeType.orEmpty()).lowercase()
        return if ("mp4a" in codec || "aac" in codec || "m4a" in codec) 0 else 1
    }

    /**
     * Extract playable stream URLs for [videoId] via NewPipeExtractor.
     *  - Live -> the HLS master playlist (separated audio+video) for HlsMediaSource.
     *  - VOD  -> best video-only track (<=1080p, codec ranked for box HW decode)
     *            paired with the best audio-only track, merged at playback time.
     *  - Last resort -> a muxed progressive stream (audio+video in one URL, <=360p).
     * Returns empty if nothing usable came back (caller then tries the embed).
     */
    private fun extractNative(videoId: String): List<StreamSource> {
        val info = StreamInfo.getInfo(service, watchUrl(videoId))

        if (info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        ) {
            val hls = info.hlsUrl
            if (!hls.isNullOrBlank()) {
                return listOf(
                    StreamSource(url = hls, kind = StreamKind.Hls, serverLabel = "YouTube Live", isLive = true),
                )
            }
        }

        val video = pickVideo(info.videoOnlyStreams)
        val audio = pickAudio(info.audioStreams)
        if (video != null && audio != null) {
            // Wrap each adaptive itag URL in a single-segment DASH manifest so
            // ExoPlayer makes RANGED segment requests instead of one open-ended
            // GET — googlevideo throttles the latter below playback bitrate and
            // the stream buffers forever until you seek (the seek opens a fresh
            // ranged request that bursts). This is exactly what NewPipe does for
            // its own playback. If manifest generation fails we leave the fields
            // null and fall back to the old progressive path (kind Mp4).
            val durationSec = info.duration
            val videoManifest = progressiveDashManifest(video, durationSec)
            val audioManifest = progressiveDashManifest(audio, durationSec)
            return listOf(
                StreamSource(
                    url = video.content,
                    audioUrl = audio.content,
                    dashManifest = videoManifest,
                    audioDashManifest = audioManifest,
                    audioTracks = audioOptions(info.audioStreams, defaultUrl = audio.content),
                    subtitles = englishSubtitles(info.subtitles),
                    kind = if (videoManifest != null) StreamKind.Dash else StreamKind.Mp4,
                    serverLabel = "YouTube ${video.resolution}",
                ),
            )
        }

        // Muxed fallback (capped ~360p, but a single self-contained URL).
        val muxed = info.videoStreams
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .maxByOrNull { resolutionValue(it.resolution) }
        if (muxed != null) {
            return listOf(
                StreamSource(
                    url = muxed.content, kind = StreamKind.Mp4,
                    serverLabel = "YouTube ${muxed.resolution}",
                ),
            )
        }
        return emptyList()
    }

    /**
     * Build a single-segment DASH manifest (XML string) for a progressive
     * adaptive itag [stream] so ExoPlayer plays it via DashMediaSource (ranged
     * segment GETs) instead of a throttle-prone open-ended progressive GET.
     * Only PROGRESSIVE_HTTP URL streams are wrappable here; OTF/other delivery
     * (rare on YouTube VOD) returns null so the caller keeps the progressive
     * fallback. Any creator failure also returns null (graceful degrade).
     */
    private fun progressiveDashManifest(
        stream: org.schabi.newpipe.extractor.stream.Stream,
        durationSec: Long,
    ): String? {
        if (stream.deliveryMethod !=
            org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP
        ) {
            return null
        }
        val itag = stream.itagItem ?: return null
        val content = stream.content
        if (content.isNullOrBlank()) return null
        return runCatching {
            org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators
                .YoutubeProgressiveDashManifestCreator
                .fromProgressiveStreamingUrl(content, itag, durationSec)
        }.onFailure {
            DebugLog.w(TAG, "DASH manifest gen failed (itag=${itag.id}) — progressive fallback", it)
        }.getOrNull()
    }

    /**
     * Best video-only track: cap at the user's quality preference ([qualityCap],
     * default ≤1080p — the box struggles above it) and rank codecs by
     * hardware-decode friendliness — AVC/H264 first, then VP9, then anything
     * else, with AV1 last (most TV boxes lack AV1 HW decode and stutter on it).
     */
    private fun pickVideo(streams: List<VideoStream>): VideoStream? {
        val cap = qualityCap().coerceAtLeast(360)
        val usable = streams.filter {
            it.isUrl && !it.content.isNullOrBlank() && resolutionValue(it.resolution) in 1..cap
        }
        if (usable.isEmpty()) return null
        fun codecRank(s: VideoStream): Int {
            val c = (s.codec ?: "").lowercase()
            return when {
                c.startsWith("avc") || c.contains("h264") -> 0
                c.startsWith("vp9") || c.startsWith("vp09") -> 1
                c.startsWith("av01") || c.contains("av1") -> 3
                else -> 2
            }
        }
        val pick = usable.sortedWith(
            compareBy({ codecRank(it) }, { -resolutionValue(it.resolution) }),
        ).firstOrNull()
        DebugLog.i(
            TAG,
            "video pick: res=${pick?.resolution} codec=${pick?.codec} " +
                "bitrate=${pick?.bitrate} cap=${cap}p (of ${usable.size} usable)",
        )
        return pick
    }

    /**
     * Best audio-only track, **language-aware**. YouTube now ships
     * multi-language auto-dubbed audio on many videos, so picking purely by
     * bitrate (the old behaviour) could grab a Hindi/Spanish/etc. dub and the
     * video would "play in a different language". We rank tracks so an English
     * — or, failing that, the original — track always wins, then break ties by
     * bitrate:
     *   tier 0: English locale (the creator's native English OR an English dub)
     *   tier 1: the ORIGINAL track (undubbed) when no English track exists
     *   tier 2: anything else (foreign-only video, or no track metadata at all)
     * Descriptive (audio-description) tracks are pushed to the back so they're
     * only ever used as a last resort.
     */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { it.isUrl && !it.content.isNullOrBlank() }
        if (usable.isEmpty()) return null

        fun isEnglish(s: AudioStream): Boolean =
            s.audioLocale?.language?.equals("en", ignoreCase = true) == true

        fun langTier(s: AudioStream): Int = when {
            isEnglish(s) -> 0
            s.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL -> 1
            else -> 2
        }
        fun descriptivePenalty(s: AudioStream): Int =
            if (s.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.DESCRIPTIVE) 1 else 0

        val pick = usable.sortedWith(
            compareBy({ langTier(it) }, { descriptivePenalty(it) }, { -it.averageBitrate }),
        ).first()
        DebugLog.i(
            TAG,
            "audio pick: locale=${pick.audioLocale?.language} type=${pick.audioTrackType} " +
                "bitrate=${pick.averageBitrate} (of ${usable.size} tracks)",
        )
        return pick
    }

    /**
     * One selectable audio option PER language (best-bitrate track of each),
     * for the in-player audio-language switch. The default ([defaultUrl], the
     * English/original pick) is floated to the front so the picker starts on
     * what's already playing. Returns empty when there's only one language —
     * nothing to choose, so the player hides the switch.
     */
    private fun audioOptions(streams: List<AudioStream>, defaultUrl: String): List<AudioOption> {
        val usable = streams.filter { it.isUrl && !it.content.isNullOrBlank() }
        val bestPerLang = usable
            .groupBy { (it.audioLocale?.language ?: "und").lowercase() }
            .mapNotNull { (lang, group) -> group.maxByOrNull { it.averageBitrate }?.let { lang to it } }
        if (bestPerLang.size < 2) return emptyList()
        val opts = bestPerLang.map { (lang, s) ->
            AudioOption(url = s.content, language = lang, label = languageLabel(lang, s.audioTrackType))
        }
        // Default (currently-playing) track first, rest alphabetical by label.
        return opts.sortedWith(
            compareByDescending<AudioOption> { it.url == defaultUrl }.thenBy { it.label },
        )
    }

    /** "English", "Spanish (dubbed)", or the raw tag if Locale can't name it. */
    private fun languageLabel(
        lang: String,
        type: org.schabi.newpipe.extractor.stream.AudioTrackType?,
    ): String {
        val base = runCatching { java.util.Locale(lang).displayLanguage }
            .getOrNull()?.takeIf { it.isNotBlank() && it != lang } ?: lang.uppercase()
        return if (type == org.schabi.newpipe.extractor.stream.AudioTrackType.DUBBED) "$base (dubbed)" else base
    }

    /**
     * English caption tracks for the OPTIONAL (off-by-default) CC toggle —
     * the app is English-only, so we never surface other languages. Prefer
     * human-authored captions over auto-generated, and VTT format (best
     * ExoPlayer support). Empty when the video has no English captions.
     */
    private fun englishSubtitles(
        subs: List<org.schabi.newpipe.extractor.stream.SubtitlesStream>,
    ): List<SubtitleTrack> {
        val english = subs.filter {
            it.isUrl && !it.content.isNullOrBlank() &&
                (it.locale?.language?.equals("en", ignoreCase = true) == true ||
                    it.languageTag?.startsWith("en", ignoreCase = true) == true)
        }
        if (english.isEmpty()) return emptyList()
        // Prefer VTT + non-auto-generated; fall back to whatever's there.
        val vtt = english.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.VTT }
        val pool = vtt.ifEmpty { english }
        val chosen = pool.minByOrNull { if (it.isAutoGenerated) 1 else 0 } ?: return emptyList()
        val label = if (chosen.isAutoGenerated) "English (auto)" else "English"
        return listOf(
            SubtitleTrack(
                url = chosen.content,
                language = "en",
                label = label,
                mimeOverride = chosen.format?.mimeType,
            ),
        )
    }

    /** "1080p60" / "720p" -> numeric height (0 if unparseable). */
    private fun resolutionValue(res: String?): Int {
        if (res.isNullOrBlank()) return 0
        return res.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun YtVideo.toSearchResult(): SearchResult {
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = title,
            poster = thumbnail ?: youtubeThumbnail(videoId),
            year = null,
            kind = MediaKind.Movie,
            isLive = isLive,
            subtitle = uploader,
            publishedLabel = publishedLabel ?: relativeAgeLabel(published),
        )
        cache[videoId] = CachedItem(title, thumbnail ?: youtubeThumbnail(videoId))
        return r
    }

    private fun PipedVideo.toSearchResult(): SearchResult {
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = title,
            poster = thumbnail ?: youtubeThumbnail(videoId),
            year = null,
            kind = MediaKind.Movie,
            isLive = isLive,
            subtitle = uploaderName,
            publishedLabel = publishedLabel ?: relativeAgeLabel(published),
        )
        cache[videoId] = CachedItem(title, thumbnail ?: youtubeThumbnail(videoId))
        return r
    }

    private fun StreamInfoItem.toSearchResult(): SearchResult {
        val poster = thumbnails.firstOrNull()?.url
        val videoId = videoIdOf(url)
        val r = SearchResult(
            providerId = id,
            id = videoId,
            title = name ?: videoId,
            poster = poster ?: youtubeThumbnail(videoId),
            year = null,
            kind = MediaKind.Movie,
            isLive = streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM,
            subtitle = uploaderName,
            publishedLabel = textualUploadDate?.takeIf { it.isNotBlank() },
        )
        cache[videoId] = CachedItem(name ?: videoId, poster ?: youtubeThumbnail(videoId))
        return r
    }

    private data class CachedItem(val title: String, val poster: String?)
    private data class CachedStreams(val sources: List<StreamSource>, val createdAtMs: Long)

    private fun youtubeThumbnail(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    /** Preserve provider wording when available, otherwise derive a compact age. */
    private fun relativeAgeLabel(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val lower = value.lowercase()
        if (lower.contains("ago") || lower.contains("streamed") ||
            lower == "today" || lower == "yesterday" || lower == "just now"
        ) return value
        val numeric = value.toLongOrNull()
        val publishedAt = if (numeric != null) {
            if (numeric > 100_000_000_000L) numeric else numeric * 1000L
        } else runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        if (publishedAt == null) return value.takeIf { it.length <= 32 }
        val seconds = Duration.between(Instant.ofEpochMilli(publishedAt), Instant.now())
            .seconds.coerceAtLeast(0L)
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} minute${if (seconds / 60 == 1L) "" else "s"} ago"
            seconds < 86_400 -> "${seconds / 3_600} hour${if (seconds / 3_600 == 1L) "" else "s"} ago"
            seconds < 604_800 -> "${seconds / 86_400} day${if (seconds / 86_400 == 1L) "" else "s"} ago"
            seconds < 2_592_000 -> "${seconds / 604_800} week${if (seconds / 604_800 == 1L) "" else "s"} ago"
            seconds < 31_536_000 -> "${seconds / 2_592_000} month${if (seconds / 2_592_000 == 1L) "" else "s"} ago"
            else -> "${seconds / 31_536_000} year${if (seconds / 31_536_000 == 1L) "" else "s"} ago"
        }
    }

    companion object {
        private const val TAG = "YouTubeProvider"
        private const val PIPED_STREAM_TIMEOUT_MS = 9_000L
        private const val NATIVE_STREAM_TIMEOUT_MS = 14_000L
        private const val STREAM_CACHE_TTL_MS = 120_000L

        // Whole-word foreign-language markers (ASCII-folded, lowercase) used by
        // [looksForeignLatin]. Curated to never collide with English words:
        // language/dub tags + function words distinct from English. Note we
        // deliberately omit ambiguous tokens ("el", "los", "die", "per",
        // "con", "episode") that appear in English titles.
        private val FOREIGN_WORDS = setOf(
            // Spanish
            "pelicula", "capitulo", "temporada", "espanol", "espana", "gratis",
            "completo", "subtitulado", "subtitulos", "doblado", "doblaje",
            "castellano", "descargar", "espanola",
            // Portuguese
            "dublado", "dublada", "legendado", "voce", "nao", "portugues",
            "episodio", "completa",
            // French
            "francais", "francaise", "gratuit", "complet", "vostfr", "doublage",
            // German
            "deutsch", "deutsche", "deutscher", "untertitel", "folge", "ganze",
            "ganzer", "synchronisiert",
            // Italian
            "italiano", "sottotitolato", "doppiaggio",
            // other dub/sub tags
            "lektor", "napisy",
        )

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
            // The progressive-DASH manifest creator keeps a static LRU of
            // generated manifests; bound it so it can't grow unbounded across
            // a long session (NewPipe itself caps at 500).
            runCatching {
                org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators
                    .YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(500)
            }
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
