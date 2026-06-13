package com.dt.streamz.scraper.youtube

import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

        val sections = root["contents"]?.jsonObject
            ?.get("twoColumnSearchResultsRenderer")?.jsonObject
            ?.get("primaryContents")?.jsonObject
            ?.get("sectionListRenderer")?.jsonObject
            ?.get("contents")?.jsonArrayOrNull
            ?: return@withContext null

        val channels = mutableListOf<YtChannel>()
        val videos = mutableListOf<YtVideo>()
        for (section in sections) {
            val items = section.jsonObjectOrNull
                ?.get("itemSectionRenderer")?.jsonObject
                ?.get("contents")?.jsonArrayOrNull ?: continue
            for (item in items) {
                val obj = item.jsonObjectOrNull ?: continue
                obj["videoRenderer"]?.jsonObjectOrNull?.let { parseVideo(it)?.let(videos::add) }
                obj["channelRenderer"]?.jsonObjectOrNull?.let { parseChannel(it)?.let(channels::add) }
            }
        }
        YtSearch(channels = channels, videos = videos)
            .takeIf { it.channels.isNotEmpty() || it.videos.isNotEmpty() }
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
        val title = v["title"]?.runsText() ?: return null
        val uploader = v["ownerText"]?.runsText() ?: v["longBylineText"]?.runsText()
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
            .header("X-Goog-Visitor-Id", "")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.d(TAG, "POST $endpoint -> HTTP ${resp.code}")
                return@use null
            }
            val body = resp.body?.string() ?: return@use null
            Http.json.parseToJsonElement(body).jsonObject
        }
    }.onFailure { DebugLog.d(TAG, "POST $endpoint failed: ${it.message}") }.getOrNull()

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
