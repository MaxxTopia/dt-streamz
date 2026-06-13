package com.dt.streamz.scraper.youtube

import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

/**
 * HTTP client for the Piped backend (https://github.com/TeamPiped/Piped),
 * the same JSON API every Piped frontend / NewPipe-Lite-style app talks
 * to. Lets us pull YouTube trending / search / stream URLs without
 * running NewPipeExtractor's bundled-JS interpreter on the box, which
 * tends to break each time YouTube ships a new player JS.
 *
 * Round-robins a fail-fast list of public instances (the ecosystem is
 * thin — most third-party Piped servers come and go on a quarterly
 * basis, so the chain is the resilience). The first instance that
 * returns a non-empty body wins for the call. After a successful call
 * we pin that instance for the rest of the session so we don't repeat
 * the cold probe on every browse / search.
 *
 * Exposes three high-level calls:
 *   - [trending]:  GET /trending?region=US   -> list of videos
 *   - [search]:    GET /search?q=&filter=videos -> list of videos
 *   - [streams]:   GET /streams/{videoId}    -> video + audio streams
 *
 * On any total failure the call returns null so the caller can fall
 * through to a secondary backend (NewPipeExtractor) without surfacing
 * an error.
 */
internal class PipedClient {

    @Volatile
    private var pinned: String? = null

    suspend fun trending(region: String = "US"): List<PipedVideo>? = withContext(Dispatchers.IO) {
        val raw = call("/trending?region=$region") ?: return@withContext null
        val arr = runCatching { Http.json.parseToJsonElement(raw) as JsonArray }.getOrNull()
            ?: return@withContext null
        arr.mapNotNull { it.asPipedVideo() }.takeIf { it.isNotEmpty() }
    }

    suspend fun search(query: String): List<PipedVideo>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        // filter=all (not videos) so currently-live broadcasts come back too
        // — the `videos` filter drops them. asPipedVideo() keeps only
        // type=="stream" items, so channels/playlists are still dropped.
        // region=US matters: without it the (often EU-hosted) instance returns
        // German/Spanish-localized titles for English creators. Verified that
        // region=US flips most results back to their English titles.
        val raw = call("/search?q=$q&filter=all&region=US") ?: return@withContext null
        val obj = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return@withContext null
        val items = obj["items"] as? JsonArray ?: return@withContext null
        items.mapNotNull { it.asPipedVideo() }
    }

    suspend fun streams(videoId: String): PipedStreams? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        val raw = call("/streams/$videoId") ?: return@withContext null
        val obj = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return@withContext null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val thumb = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val live = obj["livestream"]?.jsonPrimitive?.contentOrNull == "true"
        val hls = obj["hls"]?.jsonPrimitive?.contentOrNull
        val dash = obj["dash"]?.jsonPrimitive?.contentOrNull
        val videos = (obj["videoStreams"] as? JsonArray).orEmpty().mapNotNull { it.asPipedStream() }
        val audios = (obj["audioStreams"] as? JsonArray).orEmpty().mapNotNull { it.asPipedStream() }
        PipedStreams(
            title = title,
            thumbnailUrl = thumb,
            description = description,
            livestream = live,
            hls = hls?.takeUnless { it.isBlank() },
            dash = dash?.takeUnless { it.isBlank() },
            videoStreams = videos,
            audioStreams = audios,
        )
    }

    /**
     * Fail-fast walk through INSTANCES until one returns a 2xx. Pinned
     * instance (set after the first success of the session) is tried first.
     */
    private suspend fun call(path: String): String? {
        val ordered = buildList {
            pinned?.let { add(it) }
            INSTANCES.forEach { if (it != pinned) add(it) }
        }
        for (base in ordered) {
            val body = withTimeoutOrNull(PER_INSTANCE_TIMEOUT_MS) { fetch("$base$path") }
            if (!body.isNullOrBlank()) {
                pinned = base
                return body
            }
        }
        DebugLog.w(TAG, "all ${ordered.size} instances failed for $path")
        return null
    }

    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.d(TAG, "${url.take(80)} -> HTTP ${resp.code}")
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure { DebugLog.d(TAG, "fetch ${url.take(80)} failed: ${it.message}") }.getOrNull()

    private fun JsonElement.asPipedVideo(): PipedVideo? {
        val obj = this as? JsonObject ?: return null
        // Piped returns mixed `type` results in search (channel/playlist/stream).
        // Filter to streams to keep our caller's life easy.
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type != null && type != "stream") return null
        val urlPath = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val videoId = videoIdFrom(urlPath) ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val thumb = obj["thumbnail"]?.jsonPrimitive?.contentOrNull
        val uploader = obj["uploaderName"]?.jsonPrimitive?.contentOrNull
        val duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        // Piped marks live broadcasts with duration == -1 (and some instances
        // also set an explicit `livestream`/`isShort` flag). duration < 0 is
        // the reliable cross-instance signal.
        val live = (duration != null && duration < 0) ||
            obj["livestream"]?.jsonPrimitive?.contentOrNull == "true"
        return PipedVideo(
            videoId = videoId,
            title = title,
            thumbnail = thumb,
            uploaderName = uploader,
            durationSeconds = duration,
            isLive = live,
        )
    }

    private fun JsonElement.asPipedStream(): PipedStream? {
        val obj = this as? JsonObject ?: return null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (url.isBlank()) return null
        val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull
        val format = obj["format"]?.jsonPrimitive?.contentOrNull
        val quality = obj["quality"]?.jsonPrimitive?.contentOrNull
        val videoOnly = obj["videoOnly"]?.jsonPrimitive?.contentOrNull == "true"
        return PipedStream(
            url = url,
            mimeType = mime,
            format = format,
            quality = quality,
            videoOnly = videoOnly,
        )
    }

    /** Extract `dQw4w9WgXcQ` from `/watch?v=dQw4w9WgXcQ` or full URL. */
    private fun videoIdFrom(urlOrPath: String): String? {
        val q = urlOrPath.substringAfter("?", "")
        if (q.isNotEmpty()) {
            for (kv in q.split("&")) {
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val k = kv.substring(0, eq)
                val v = kv.substring(eq + 1)
                if (k == "v" && v.isNotBlank()) return v
            }
        }
        return null
    }

    companion object {
        private const val TAG = "PipedClient"

        /** Per-instance timeout; chain length × this caps total wait. */
        private const val PER_INSTANCE_TIMEOUT_MS = 4_500L

        /**
         * Public Piped API instances. Order matters — frontmost is tried
         * first on cold start. private.coffee is currently the only one
         * tracked by piped-instances.kavin.rocks but we keep historical
         * names in the list because they tend to come back, and the
         * cost of trying a dead one is bounded by [PER_INSTANCE_TIMEOUT_MS].
         */
        // Probed 2026-06-13: api.piped.private.coffee was the only instance
        // returning a usable 200 — the rest 5xx/timeout (the Piped ecosystem
        // has thinned badly). Keep the dead ones as longer-shot fallbacks
        // since they rotate back occasionally; cost of a dead try is capped
        // by PER_INSTANCE_TIMEOUT_MS.
        private val INSTANCES = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.ducks.party",
            "https://pipedapi.leptons.xyz",
            "https://piped-api.privacy.com.de",
            "https://pipedapi.r4fo.com",
        )
    }
}

internal data class PipedVideo(
    val videoId: String,
    val title: String,
    val thumbnail: String?,
    val uploaderName: String?,
    val durationSeconds: Long?,
    val isLive: Boolean = false,
)

internal data class PipedStreams(
    val title: String?,
    val thumbnailUrl: String?,
    val description: String?,
    val livestream: Boolean,
    val hls: String?,
    val dash: String?,
    val videoStreams: List<PipedStream>,
    val audioStreams: List<PipedStream>,
)

internal data class PipedStream(
    val url: String,
    val mimeType: String?,
    val format: String?,
    val quality: String?,
    val videoOnly: Boolean,
)
