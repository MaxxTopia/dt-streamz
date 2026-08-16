package com.dt.streamz.scraper.megaplay

import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.SubtitleTrack
import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Resolves the native MegaPlay HLS source for the one canonical anime title
 * whose old VidNest MegaPlay lookup currently returns 404.
 *
 * MegaPlay's public AniList route is stale for this catalog entry. The live
 * Anikoto episode catalog still supplies the episode embed id, and MegaPlay's
 * AJAX source endpoint accepts that id directly. The returned HLS URL is then
 * played by Media3 with MegaPlay's referer so the CDN does not reject the
 * manifest and segments.
 *
 * Keep this resolver narrow and fail closed: if either upstream response is
 * unavailable, the caller retains the existing VidNest Dub source.
 */
object MegaPlayResolver {
    private const val TAG = "MegaPlay"
    private const val ANIKOTO_API = "https://anikotoapi.site"
    private const val ANIKOTO_NARUTO_ID = "1498"
    private const val MEGAPLAY_BASE = "https://megaplay.buzz"
    private const val NARUTO_ANILIST_ID = "1735"

    private val episodeCacheLock = Any()
    @Volatile
    private var narutoEpisodeIds: Map<Int, String>? = null

    /** Resolve a native English-Dub HLS source for Naruto: Shippuden only. */
    suspend fun resolveNarutoDub(titleId: String, episodeNumber: Int): StreamSource? =
        withContext(Dispatchers.IO) {
            if (titleId != NARUTO_ANILIST_ID || episodeNumber !in 1..500) return@withContext null
            runCatching {
                val embedId = episodeIds()[episodeNumber] ?: return@runCatching null
                fetchSource(embedId)
            }.onFailure {
                DebugLog.w(TAG, "Naruto Dub MegaPlay resolve failed: ${it.message}")
            }.getOrNull()
        }

    private fun episodeIds(): Map<Int, String> {
        narutoEpisodeIds?.let { return it }
        synchronized(episodeCacheLock) {
            narutoEpisodeIds?.let { return it }
            val loaded = runCatching {
                val request = Request.Builder()
                    .url("$ANIKOTO_API/series/$ANIKOTO_NARUTO_ID")
                    .header("Accept", "application/json")
                    .header("User-Agent", Http.DESKTOP_UA)
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        DebugLog.w(TAG, "Anikoto episode catalog HTTP ${response.code}")
                        return@use emptyMap()
                    }
                    val body = response.body?.string().orEmpty()
                    val root = Http.json.parseToJsonElement(body).jsonObject
                    val episodes = root["data"]?.jsonObject?.get("episodes") as? JsonArray
                    episodes.orEmpty().mapNotNull { raw ->
                        val episode = raw as? JsonObject ?: return@mapNotNull null
                        val number = episode["number"]?.jsonPrimitive?.intOrNull
                        val embedId = episode["episode_embed_id"]?.textOrNull()
                        if (number != null && !embedId.isNullOrBlank()) number to embedId else null
                    }.toMap()
                }
            }.onFailure {
                DebugLog.w(TAG, "Anikoto episode catalog failed: ${it.message}")
            }.getOrDefault(emptyMap())
            if (loaded.isNotEmpty()) narutoEpisodeIds = loaded
            return narutoEpisodeIds ?: loaded
        }
    }

    private fun fetchSource(embedId: String): StreamSource? {
        val playerUrl = "$MEGAPLAY_BASE/stream/s-2/$embedId/dub"
        val request = Request.Builder()
            .url("$MEGAPLAY_BASE/stream/getSources?id=$embedId&type=dub")
            .header("Accept", "application/json")
            .header("Referer", playerUrl)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                DebugLog.w(TAG, "MegaPlay source HTTP ${response.code} for episode $embedId")
                return@use null
            }
            val root = Http.json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val hls = root["sources"]?.jsonObject?.get("file")?.textOrNull()
                ?.takeIf { it.startsWith("https://") }
                ?: return@use null
            val subtitles = (root["tracks"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val track = raw as? JsonObject ?: return@mapNotNull null
                val file = track["file"]?.textOrNull()?.takeIf { it.startsWith("https://") }
                    ?: return@mapNotNull null
                val label = track["label"]?.textOrNull()?.ifBlank { "English" } ?: "English"
                SubtitleTrack(
                    url = file,
                    language = if (label.contains("english", ignoreCase = true)) "en" else "und",
                    label = label,
                    mimeOverride = "text/vtt",
                )
            }
            StreamSource(
                url = hls,
                // The CDN checks the embed origin. These headers are carried
                // into Media3's manifest, playlist, and segment requests.
                headers = mapOf(
                    "Referer" to "$MEGAPLAY_BASE/",
                    "Origin" to MEGAPLAY_BASE,
                ),
                kind = StreamKind.Hls,
                quality = "Native HLS",
                subtitles = subtitles,
                captionsDefaultOn = false,
                serverLabel = "MegaPlay - English Dub",
            ).also {
                DebugLog.i(TAG, "resolved Naruto Dub episode $embedId -> MegaPlay HLS")
            }
        }
    }

    private fun JsonElement.textOrNull(): String? =
        jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
