package com.dt.streamz.scraper.megacloud

import android.util.Log
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.SubtitleTrack
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Resolves a MegaCloud-family embed URL (used by anicrush, anikai/hianime,
 * and similar) into a concrete HLS playlist URL by hitting `getSources`
 * and AES-CBC-decrypting the response with a community-maintained key.
 *
 * Input:  https://<host>/embed-2/v2/e-<n>/<sourceId>?k=1
 * Output: List<StreamSource> with StreamKind.Hls (or Mp4) plus subtitles.
 *
 * The decryption key rotates whenever MegaCloud redeploys. We pull it
 * from [KEY_SOURCES] (first hit wins). If every mirror is dead, the
 * caller gets an empty list and logs a warning — the extractor doesn't
 * throw so a failed resolve falls through to the existing "Phase 3b"
 * toast without crashing the app.
 */
class MegaCloudExtractor(
    private val json: Json = Http.json,
    private val keySources: List<String> = KEY_SOURCES,
) {

    suspend fun resolve(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
        val parsed = parseEmbed(embedUrl) ?: run {
            Log.w(TAG, "unrecognized embed URL: $embedUrl")
            return@withContext emptyList()
        }

        val sourcesEndpoint = "${parsed.host}/embed-2/ajax/v2/embed-${parsed.variant}/getSources"
            .toHttpUrl().newBuilder()
            .addQueryParameter("id", parsed.sourceId)
            .build()
            .toString()

        val body = fetch(sourcesEndpoint, referer = embedUrl) ?: return@withContext emptyList()
        val response = runCatching {
            json.decodeFromString(MegaSourcesResponse.serializer(), body)
        }.getOrElse {
            Log.w(TAG, "getSources parse failed", it)
            return@withContext emptyList()
        }

        val files = decodeSources(response) ?: return@withContext emptyList()
        val subtitles = response.tracks
            .filter { it.kind == "captions" || it.kind == "subtitles" }
            .map { SubtitleTrack(url = it.file, language = it.label ?: "und", label = it.label ?: "Subtitle") }

        files.map { file ->
            StreamSource(
                url = file.file,
                kind = if (file.type.equals("hls", ignoreCase = true)) StreamKind.Hls else StreamKind.Mp4,
                subtitles = subtitles,
                serverLabel = "MegaCloud",
            )
        }
    }

    private fun decodeSources(resp: MegaSourcesResponse): List<MegaSourceFile>? {
        val sources = resp.sources ?: return null
        return if (resp.encrypted) {
            val cipherText = (sources as? JsonPrimitive)?.contentOrNull ?: return null
            val key = fetchKey() ?: run {
                Log.w(TAG, "no MegaCloud key available from any mirror")
                return null
            }
            val plain = runCatching { MegaCloudCrypto.decryptOpenSsl(cipherText, key) }.getOrElse {
                Log.w(TAG, "MegaCloud decryption failed", it)
                return null
            }
            runCatching {
                val element = json.parseToJsonElement(plain)
                parseSourcesArray(element.jsonArray)
            }.getOrElse {
                Log.w(TAG, "decrypted sources parse failed", it)
                null
            }
        } else {
            runCatching {
                when (sources) {
                    is JsonArray -> parseSourcesArray(sources)
                    else -> null
                }
            }.getOrNull()
        }
    }

    private fun parseSourcesArray(arr: JsonArray): List<MegaSourceFile> =
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val file = obj["file"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            MegaSourceFile(file = file, type = type)
        }

    private fun fetchKey(): String? {
        for (url in keySources) {
            val body = fetch(url, referer = null) ?: continue
            // Mirror may return raw key text OR JSON like {"mega":"..."} / {"key":"..."}.
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: continue
                val candidate = obj["mega"]?.jsonPrimitive?.contentOrNull
                    ?: obj["key"]?.jsonPrimitive?.contentOrNull
                    ?: obj["megacloud"]?.jsonPrimitive?.contentOrNull
                if (!candidate.isNullOrBlank()) return candidate
            } else {
                // Strip surrounding quotes or whitespace.
                val cleaned = trimmed.trim('"', '\'', '\n', '\r', ' ')
                if (cleaned.isNotBlank() && cleaned.length in 4..128) return cleaned
            }
        }
        return null
    }

    private fun fetch(url: String, referer: String?): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("x-requested-with", "XMLHttpRequest")
        if (referer != null) builder.header("Referer", referer)
        Http.client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()

    private data class ParsedEmbed(val host: String, val variant: String, val sourceId: String)

    private fun parseEmbed(url: String): ParsedEmbed? {
        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        // Path shape: /embed-2/v2/e-<n>/<sourceId>
        val segs = httpUrl.pathSegments.filter { it.isNotBlank() }
        val eSegIdx = segs.indexOfFirst { it.startsWith("e-") }
        if (eSegIdx < 0 || eSegIdx >= segs.size - 1) return null
        val variant = segs[eSegIdx].removePrefix("e-")
        val sourceId = segs[eSegIdx + 1]
        val host = "${httpUrl.scheme}://${httpUrl.host}"
        return ParsedEmbed(host = host, variant = variant, sourceId = sourceId)
    }

    companion object {
        private const val TAG = "MegaCloudExtractor"

        /**
         * Public key mirrors (first-hit wins). These rotate as MegaCloud
         * re-encrypts; swap the list if every entry starts returning a
         * stale key. Users can override via ScraperConfig later.
         */
        val KEY_SOURCES: List<String> = listOf(
            "https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/main/keys.json",
            "https://raw.githubusercontent.com/itzzzme/megacloud-keys/main/key.txt",
            "https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt",
        )
    }
}
