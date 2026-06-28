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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

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

    override suspend fun browse(): List<SearchResult> = kotlinx.coroutines.coroutineScope {
        // Genuinely personalised "Recommended" grid — no login needed.
        //
        // YouTube's real home/trending browse is login- and session-token-gated,
        // Piped trending comes back empty, and NewPipe's kiosk crashes on this
        // box's Android. The login-free way to get TRUE personalisation is to
        // tap YouTube's own watch-next graph: for each video you actually
        // watched, `relatedVideos` returns what YouTube recommends after it
        // (collaborative-filtered by YouTube, not by us). The grid is driven
        // entirely by WHAT YOU WATCH on YouTube; we only fall back to generic
        // popular seeds when there's nothing watched yet (cold start / off).
        //
        // The watch signal is YOUTUBE-ONLY (see [recentWatchIds]) — movies and
        // shows can't drift this grid.
        //
        // Tiers drained in priority order: related-from-watches first (your
        // "for you"), then generic filler only to top up / cold-start.
        val watchIds = runCatching { recentWatchIds() }.getOrNull().orEmpty().distinct().take(5)

        // Tier 1: YouTube's own recommendations for what you watched.
        val relatedTier = watchIds.map { id ->
            async(Dispatchers.IO) { runCatching { innertube.relatedVideos(id) }.getOrNull().orEmpty() }
        }.awaitAll()
        // Tier 2: generic popular filler (also the entire grid at cold start).
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
                    if (v.isLive || v.videoId in watched || !isLikelyEnglish(v.title)) continue
                    if (seen.add(v.videoId)) out.add(v.toSearchResult())
                    if (out.size >= 24) return true
                }
            }
            return false
        }
        if (drain(relatedTier)) return@coroutineScope out
        drain(fillerTier)
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
            // English-only: drop any non-English title (the box should never
            // surface a foreign-language video). See [isLikelyEnglish].
            for (v in itResult.videos) {
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

            // PRIMARY: native stream extraction via NewPipeExtractor, played by
            // ExoPlayer. The IFrame embed is a 2026 dead end (YouTube ramping
            // ads into embeds, tiny non-TV UI, unfixable embed-disabled errors),
            // so we extract real stream URLs and play them in our native player:
            // genuinely ad-free, full D-pad transport, higher quality. NewPipe
            // v0.26.3 hops to the ANDROID_VR/iOS clients to dodge SABR/PoToken
            // enforcement, so no login or attestation token is needed. (The old
            // API-33 URLEncoder crash on this box is now handled by the `_nio`
            // core-library desugaring enabled in build.gradle.kts.)
            val native = runCatching { extractNative(videoId) }
                .onFailure { DebugLog.w(TAG, "native extract($videoId) failed", it) }
                .getOrNull()
            if (!native.isNullOrEmpty()) return@withContext native

            // FALLBACK: the hosted IFrame embed + watch page, used only when
            // extraction yields nothing playable (a video YouTube has fully
            // locked down). WebPlayerScreen expands `ytembed://` into a
            // full-screen IFrame-API player; the watch page plays the rest.
            DebugLog.i(TAG, "native extract empty for $videoId — falling back to embed")
            listOf(
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
            return listOf(
                StreamSource(
                    url = video.content,
                    audioUrl = audio.content,
                    audioTracks = audioOptions(info.audioStreams, defaultUrl = audio.content),
                    subtitles = englishSubtitles(info.subtitles),
                    kind = StreamKind.Mp4,
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
