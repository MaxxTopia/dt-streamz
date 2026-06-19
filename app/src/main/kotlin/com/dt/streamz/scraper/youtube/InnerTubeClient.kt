package com.dt.streamz.scraper.youtube

import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Direct YouTube InnerTube (youtubei/v1) client for METADATA only —
 * search + per-channel newest uploads. This replaces Piped/NewPipe for
 * the parts that were broken on the box:
 *
 *   - Piped's surviving instances are EU-hosted and hand back
 *     German/Spanish-localized titles; InnerTube with hl=en&gl=US returns
 *     real English titles.
 *   - NewPipeExtractor's search crashes on the box's Android (<13) because
 *     the bundled lib calls URLEncoder.encode(String, Charset) (API 33+).
 *
 * We deliberately do NOT use InnerTube for stream URLs: YouTube now gates
 * the player endpoint behind attestation tokens (FAILED_PRECONDITION) and
 * signature-ciphers the formats, so deciphering belongs to Piped/NewPipe
 * (server-side / JS). Playback keeps using those; only discovery moved here.
 *
 * The WEB client key is YouTube's own public web-player key — no account,
 * no secret. Search needs no session; channel uploads come from the public
 * RSS feed (no auth, always original-language titles, newest-first).
 */
internal class InnerTubeClient {

    suspend fun search(query: String): YtSearch? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        val payload = """
            {"context":{"client":{"clientName":"WEB","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},
             "query":${query.jsonString()}}
        """.trimIndent()
        val root = post("search", payload) ?: return@withContext null

        // Results aren't flat: YouTube buries most of them inside shelves
        // (shelfRenderer -> vertical/horizontalListRenderer) as videoRenderer
        // AND gridVideoRenderer, e.g. "Latest from X", "<query> edits". A
        // flat top-level scan finds only 1-2. So walk the whole response tree
        // and collect every video/channel renderer wherever it sits, deduped
        // in document (relevance) order.
        val channels = mutableListOf<YtChannel>()
        val videos = mutableListOf<YtVideo>()
        val seen = mutableSetOf<String>()
        collectRenderers(root, videos, channels, seen)
        YtSearch(channels = channels, videos = videos)
            .takeIf { it.channels.isNotEmpty() || it.videos.isNotEmpty() }
    }

    /**
     * Related / up-next video IDs for [videoId] via the youtubei `next`
     * endpoint — the same "watch next" column YouTube shows beside a video,
     * which is its relatedness signal. Returns IDs in YouTube's own order
     * (most-relevant first). Best-effort: any failure yields an empty list.
     */
    suspend fun related(videoId: String): List<String> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        val payload = """
            {"context":{"client":{"clientName":"WEB","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},
             "videoId":${videoId.jsonString()}}
        """.trimIndent()
        val root = post("next", payload) ?: return@withContext emptyList()
        // The watch-next column nests results as compactVideoRenderer; walk
        // the whole tree and collect their videoIds in document order.
        val out = mutableListOf<String>()
        val seen = mutableSetOf(videoId)
        collectCompact(root, out, seen)
        out
    }

    /**
     * Same watch-next column as [related], but parsed into full [YtVideo]s
     * (title + thumbnail + uploader) so the player can show a navigable
     * "Up next" rail instead of just cycling opaque IDs.
     */
    suspend fun relatedVideos(videoId: String): List<YtVideo> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        val payload = """
            {"context":{"client":{"clientName":"WEB","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},
             "videoId":${videoId.jsonString()}}
        """.trimIndent()
        val root = post("next", payload) ?: return@withContext emptyList()
        val out = mutableListOf<YtVideo>()
        val seen = mutableSetOf(videoId)
        collectCompactVideos(root, out, seen)
        out
    }

    /** Depth-first collect of compactVideoRenderer videoIds (watch-next list). */
    private fun collectCompact(el: JsonElement, out: MutableList<String>, seen: MutableSet<String>) {
        when (el) {
            is JsonObject -> {
                el["compactVideoRenderer"]?.jsonObjectOrNull
                    ?.get("videoId")?.jsonPrimitive?.contentOrNull
                    ?.let { if (seen.add(it)) out.add(it) }
                for ((k, v) in el) {
                    if (k == "compactVideoRenderer") continue
                    collectCompact(v, out, seen)
                }
            }
            is JsonArray -> el.forEach { collectCompact(it, out, seen) }
            else -> {}
        }
    }

    /**
     * Is [videoId] live RIGHT NOW? Hits the `next` (watch) endpoint and looks
     * for the live "watching now" view-count marker. An ended/VOD stream comes
     * back without it, so this reliably distinguishes "still live" from
     * "was live, now ended" (the case a stale search badge can't). Best-effort:
     * any failure returns false (treated as not-currently-live).
     */
    suspend fun isLiveNow(videoId: String): Boolean = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext false
        val payload = """
            {"context":{"client":{"clientName":"WEB","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},
             "videoId":${videoId.jsonString()}}
        """.trimIndent()
        val root = post("next", payload) ?: return@withContext false
        findLiveNow(root)
    }

    /** True if the tree carries a live "watching now" view-count marker. */
    private fun findLiveNow(el: JsonElement): Boolean = when (el) {
        is JsonObject -> {
            val liveHere = el["videoViewCountRenderer"]?.jsonObjectOrNull
                ?.get("isLive")?.jsonPrimitive?.booleanOrNull == true
            liveHere || el.values.any { findLiveNow(it) }
        }
        is JsonArray -> el.any { findLiveNow(it) }
        else -> false
    }

    /** Depth-first collect of compactVideoRenderer into full YtVideos. */
    private fun collectCompactVideos(
        el: JsonElement,
        out: MutableList<YtVideo>,
        seen: MutableSet<String>,
    ) {
        when (el) {
            is JsonObject -> {
                el["compactVideoRenderer"]?.jsonObjectOrNull
                    ?.let { parseCompact(it)?.let { v -> if (seen.add(v.videoId)) out.add(v) } }
                for ((k, v) in el) {
                    if (k == "compactVideoRenderer") continue
                    collectCompactVideos(v, out, seen)
                }
            }
            is JsonArray -> el.forEach { collectCompactVideos(it, out, seen) }
            else -> {}
        }
    }

    private fun parseCompact(v: JsonObject): YtVideo? {
        val videoId = v["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        // Drop premieres / scheduled-upcoming videos — they aren't playable yet
        // (open to a "Premieres in N days" countdown), so they have no business
        // in the up-next rail.
        if (isUpcoming(v)) return null
        // compactVideoRenderer title is usually simpleText; fall back to runs.
        val title = v["title"]?.textOrNull() ?: return null
        val uploader = v["longBylineText"]?.runsText() ?: v["shortBylineText"]?.runsText()
        val live = v["thumbnailOverlays"]?.jsonArrayOrNull?.any { ov ->
            ov.jsonObjectOrNull?.get("thumbnailOverlayTimeStatusRenderer")?.jsonObjectOrNull
                ?.get("style")?.jsonPrimitive?.contentOrNull == "LIVE"
        } == true
        return YtVideo(videoId, title, uploader, null, thumbOf(videoId), live, published = "")
    }

    /**
     * True for a premiere / scheduled-upcoming video. YouTube marks these with
     * `upcomingEventData` (the scheduled start) and/or an "UPCOMING" thumbnail
     * time-status overlay. They can't be played until they air — opening one
     * dead-ends on a countdown — so we drop them from every feed.
     */
    private fun isUpcoming(v: JsonObject): Boolean {
        if (v.containsKey("upcomingEventData")) return true
        return v["thumbnailOverlays"]?.jsonArrayOrNull?.any { ov ->
            ov.jsonObjectOrNull?.get("thumbnailOverlayTimeStatusRenderer")?.jsonObjectOrNull
                ?.get("style")?.jsonPrimitive?.contentOrNull == "UPCOMING"
        } == true
    }

    /** simpleText or concatenated runs, whichever the node carries. */
    private fun JsonElement.textOrNull(): String? =
        jsonObjectOrNull?.get("simpleText")?.jsonPrimitive?.contentOrNull ?: runsText()

    /** Depth-first collect of videoRenderer/gridVideoRenderer/channelRenderer. */
    private fun collectRenderers(
        el: JsonElement,
        videos: MutableList<YtVideo>,
        channels: MutableList<YtChannel>,
        seen: MutableSet<String>,
    ) {
        when (el) {
            is JsonObject -> {
                (el["videoRenderer"]?.jsonObjectOrNull ?: el["gridVideoRenderer"]?.jsonObjectOrNull)
                    ?.let { parseVideo(it)?.let { v -> if (seen.add(v.videoId)) videos.add(v) } }
                el["channelRenderer"]?.jsonObjectOrNull
                    ?.let { parseChannel(it)?.let { c -> if (seen.add("ch:" + c.channelId)) channels.add(c) } }
                for ((k, v) in el) {
                    if (k == "videoRenderer" || k == "gridVideoRenderer" || k == "channelRenderer") continue
                    collectRenderers(v, videos, channels, seen)
                }
            }
            is JsonArray -> el.forEach { collectRenderers(it, videos, channels, seen) }
            else -> {}
        }
    }

    /**
     * A channel's newest uploads via the public RSS feed (no auth, no
     * tokens, original English titles, newest-first). The feed returns the
     * 15 most-recent uploads — exactly the "open the creator and see their
     * latest videos" flow.
     */
    suspend fun channelNewest(channelId: String): List<YtVideo> = withContext(Dispatchers.IO) {
        if (channelId.isBlank()) return@withContext emptyList()
        val xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            ?: return@withContext emptyList()
        // Each <entry> carries <yt:videoId>, <title>, <name> (uploader),
        // <published>. Parse entry-by-entry so fields stay aligned, then sort
        // by published descending (the feed is already newest-first, but the
        // pinned/featured video can jump the order).
        ENTRY.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val vid = TAG_VIDEOID.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = TAG_TITLE.find(block)?.groupValues?.get(1)?.let(::unescapeXml) ?: return@mapNotNull null
            val uploader = TAG_NAME.find(block)?.groupValues?.get(1)?.let(::unescapeXml)
            val published = TAG_PUBLISHED.find(block)?.groupValues?.get(1).orEmpty()
            YtVideo(
                videoId = vid,
                title = title,
                uploader = uploader,
                channelId = channelId,
                thumbnail = thumbOf(vid),
                isLive = false,
                published = published,
            )
        }.sortedByDescending { it.published }.toList()
    }

    // --- parsing ---

    private fun parseVideo(v: JsonObject): YtVideo? {
        val videoId = v["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        // Drop premieres / scheduled-upcoming videos from search + trending —
        // they aren't playable until they air (open to a countdown), so they
        // should never surface as something to watch now. See [isUpcoming].
        if (isUpcoming(v)) return null
        // gridVideoRenderer uses headline+shortBylineText; videoRenderer uses
        // title+ownerText. Accept either so shelf results aren't dropped.
        val title = v["title"]?.runsText() ?: v["headline"]?.runsText() ?: return null
        val uploader = v["ownerText"]?.runsText()
            ?: v["longBylineText"]?.runsText()
            ?: v["shortBylineText"]?.runsText()
        val channelId = v["ownerText"]?.jsonObjectOrNull
            ?.get("runs")?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull
            ?.get("navigationEndpoint")?.jsonObjectOrNull
            ?.get("browseEndpoint")?.jsonObjectOrNull
            ?.get("browseId")?.jsonPrimitive?.contentOrNull
        // LIVE has no lengthText AND carries a LIVE badge / style. Use the
        // badge as the reliable signal.
        val live = v["badges"]?.jsonArrayOrNull?.any { badge ->
            badge.jsonObjectOrNull?.get("metadataBadgeRenderer")?.jsonObjectOrNull
                ?.get("style")?.jsonPrimitive?.contentOrNull == "BADGE_STYLE_TYPE_LIVE_NOW"
        } == true || v["thumbnailOverlays"]?.jsonArrayOrNull?.any { ov ->
            ov.jsonObjectOrNull?.get("thumbnailOverlayTimeStatusRenderer")?.jsonObjectOrNull
                ?.get("style")?.jsonPrimitive?.contentOrNull == "LIVE"
        } == true
        return YtVideo(videoId, title, uploader, channelId, thumbOf(videoId), live, published = "")
    }

    private fun parseChannel(c: JsonObject): YtChannel? {
        val channelId = c["channelId"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = c["title"]?.jsonObjectOrNull?.get("simpleText")?.jsonPrimitive?.contentOrNull
            ?: c["title"]?.runsText() ?: return null
        val thumb = c["thumbnail"]?.jsonObjectOrNull
            ?.get("thumbnails")?.jsonArrayOrNull?.lastOrNull()?.jsonObjectOrNull
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        return YtChannel(channelId, name, thumb)
    }

    private fun JsonElement.runsText(): String? {
        val runs = jsonObjectOrNull?.get("runs")?.jsonArrayOrNull ?: return null
        val sb = StringBuilder()
        for (r in runs) r.jsonObjectOrNull?.get("text")?.jsonPrimitive?.contentOrNull?.let(sb::append)
        return sb.toString().takeIf { it.isNotBlank() }
    }

    // --- HTTP ---

    private fun post(endpoint: String, jsonBody: String): JsonObject? = runCatching {
        val req = Request.Builder()
            .url("$BASE/$endpoint?key=$KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w(TAG, "POST $endpoint -> HTTP ${resp.code} (search will fall back)")
                return@use null
            }
            val body = resp.body?.string() ?: return@use null
            val obj = Http.json.parseToJsonElement(body).jsonObject
            // Diagnostic: did we actually get search content back? If the box
            // gets a 200 but no 'contents' (consent wall / bot page), this
            // tells us; from a healthy IP 'contents' is present.
            DebugLog.i(TAG, "POST $endpoint ok ${body.length}B contents=${obj.containsKey("contents")}")
            obj
        }
    }.onFailure { DebugLog.w(TAG, "POST $endpoint failed: ${it.message}") }.getOrNull()

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }.onFailure { DebugLog.d(TAG, "GET failed: ${it.message}") }.getOrNull()

    // --- small helpers ---

    private val JsonElement.jsonObjectOrNull: JsonObject? get() = this as? JsonObject
    private val JsonElement.jsonArrayOrNull: JsonArray? get() = this as? JsonArray

    /** JSON-encode a string for safe interpolation into the request body. */
    private fun String.jsonString(): String =
        buildString {
            append('"')
            for (ch in this@jsonString) when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
            append('"')
        }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    companion object {
        private const val TAG = "InnerTube"
        private const val BASE = "https://www.youtube.com/youtubei/v1"
        // YouTube's public WEB player API key (ships in youtube.com HTML).
        private const val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val CLIENT_VERSION = "2.20240101.00.00"
        private val JSON_MEDIA = "application/json".toMediaType()

        private fun thumbOf(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        private val ENTRY = Regex("""<entry>([\s\S]*?)</entry>""")
        private val TAG_VIDEOID = Regex("""<yt:videoId>([^<]+)</yt:videoId>""")
        private val TAG_TITLE = Regex("""<title>([^<]+)</title>""")
        private val TAG_NAME = Regex("""<name>([^<]+)</name>""")
        private val TAG_PUBLISHED = Regex("""<published>([^<]+)</published>""")
    }
}

internal data class YtVideo(
    val videoId: String,
    val title: String,
    val uploader: String?,
    val channelId: String?,
    val thumbnail: String?,
    val isLive: Boolean,
    val published: String,
)

internal data class YtChannel(
    val channelId: String,
    val name: String,
    val thumbnail: String?,
)

internal data class YtSearch(
    val channels: List<YtChannel>,
    val videos: List<YtVideo>,
)
