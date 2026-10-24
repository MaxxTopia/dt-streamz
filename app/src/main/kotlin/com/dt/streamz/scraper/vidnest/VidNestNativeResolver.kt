package com.dt.streamz.scraper.vidnest

import android.net.Uri
import android.util.Log
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Native movie/TV playback using the existing APKBox/VidNest contract.
 *
 * The provider page is never opened in a WebView. The resolver asks the
 * first-party route for a title-bound HLS source, sends it through the same
 * allow-listed relay as the web app, and admits it only after a playlist and
 * a real media segment are reachable. A provider outage therefore falls
 * through cleanly instead of turning an ad/verification page into playback.
 */
object VidNestNativeResolver {
    private const val TAG = "VidNestNative"
    private const val API_BASE = "https://view.maxxtopia.com/api/vidnest"
    private const val RELAY_ENDPOINT = "$API_BASE/stream"
    private const val PROVIDER_BUDGET_MS = 15_000L
    private const val PROVIDER_TIMEOUT_MS = 8_500L
    private const val MAX_PLAYLIST_DEPTH = 2
    private const val SEGMENT_RANGE = "bytes=0-2047"

    // Keep this order aligned with the web APKBox's healthy movie/TV route.
    // Each entry is still admitted only after the relay can serve media.
    private val PROVIDERS = listOf(
        "catflix",
        "filxer",
        "delta",
        "videasy",
        "moviebox",
        "prime",
        "zeta",
        "lamda",
    )

    /** The relay policy's exact hosts plus its intentionally narrow suffixes. */
    private val EXACT_MEDIA_HOSTS = setOf(
        "tiktoks.animanga.fun",
        "pwcloud.animanga.fun",
        "akcloud.animanga.fun",
        "authenticbrandbuilders.site",
        "scalableimpactgroup.site",
        "innovationdrivenstudio.site",
        "conversionfocusedstudio.site",
        "streamvaultsrc.click",
        "cdn6.streamraiwind.stream",
        "s1.streamflixserver.site",
        "s8-t25.97bf1.com",
        "embed.vidrift.net",
        "i-arch-400.rasta428jem.com",
        "megacloud.animanga.fun",
        "upcloud.animanga.fun",
    )

    // The resolver has a shorter client budget than ordinary catalog calls so
    // a dead upstream cannot hold the Android-TV details screen indefinitely.
    private val resolverClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolveMovie(tmdbId: String): StreamSource? =
        resolve("movie", tmdbId, null, null)

    suspend fun resolveTv(tmdbId: String, season: String, episode: String): StreamSource? =
        resolve("tv", tmdbId, season, episode)

    suspend fun resolveAnimeDub(anilistId: String, episode: Int = 1): StreamSource? = withContext(Dispatchers.IO) {
        if (!anilistId.matches(Regex("\\d+"))) return@withContext null
        val endpoint = "$API_BASE/anime/$anilistId/$episode/dub?server=gogoanime"
        val payload = getJson(endpoint) ?: return@withContext null
        val candidates = candidates(payload)
        for (candidate in candidates) {
            if (!isAllowedSource(candidate.url)) continue
            if (!isHls(candidate)) continue
            val relayUrl = relayUrl(candidate.url, candidate.headers)
            if (!probeHls(relayUrl)) continue
            return@withContext StreamSource(
                url = relayUrl,
                kind = StreamKind.Hls,
                quality = candidate.quality ?: "1080p",
                headers = emptyMap(),
                captionsDefaultOn = false,
                serverLabel = "MegaVid · English Dub",
            )
        }
        null
    }

    private suspend fun resolve(
        kind: String,
        tmdbId: String,
        season: String?,
        episode: String?,
    ): StreamSource? = withContext(Dispatchers.IO) {
        if (!tmdbId.matches(Regex("\\d+"))) return@withContext null
        val deadline = System.currentTimeMillis() + PROVIDER_BUDGET_MS
        for (provider in PROVIDERS) {
            if (System.currentTimeMillis() >= deadline) break
            val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                resolveProvider(kind, tmdbId, season, episode, provider)
            }
            if (result != null) {
                Log.i(TAG, "admitted native $kind/$tmdbId via $provider")
                return@withContext result
            }
            Log.w(TAG, "native provider rejected kind=$kind id=$tmdbId provider=$provider")
        }
        null
    }

    private fun resolveProvider(
        kind: String,
        tmdbId: String,
        season: String?,
        episode: String?,
        provider: String,
    ): StreamSource? {
        val path = if (kind == "movie") {
            "movie/$tmdbId"
        } else {
            val safeSeason = season?.takeIf { it.matches(Regex("\\d+")) } ?: return null
            val safeEpisode = episode?.takeIf { it.matches(Regex("\\d+")) } ?: return null
            "tv/$tmdbId/$safeSeason/$safeEpisode"
        }
        val endpoint = "$API_BASE/$path?server=$provider"
        val payload = getJson(endpoint) ?: return null
        val candidates = candidates(payload)
        for (candidate in candidates) {
            if (!isAllowedLanguage(candidate.language)) continue
            if (!isAllowedSource(candidate.url)) continue
            if (!isHls(candidate)) continue
            val relayUrl = relayUrl(candidate.url, candidate.headers)
            if (!probeHls(relayUrl)) continue
            return StreamSource(
                url = relayUrl,
                kind = StreamKind.Hls,
                quality = candidate.quality ?: "Native HLS",
                // Provider headers belong in the relay query. Keeping them out
                // of Media3 prevents an upstream Referer from leaking to a
                // different host after the playlist is rewritten.
                headers = emptyMap(),
                captionsDefaultOn = true,
                serverLabel = "VidNest Native · ${provider.capitalizeForLabel()}",
            )
        }
        return null
    }

    private data class Candidate(
        val url: String,
        val headers: Map<String, String>,
        val language: String,
        val quality: String?,
        val type: String,
    )

    private fun candidates(root: JsonObject): List<Candidate> {
        val rootHeaders = root["headers"].headersOrEmpty()
        val out = mutableListOf<Candidate>()
        val sources = root["sources"] as? JsonArray
        sources.orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            candidateFrom(obj, rootHeaders)?.let(out::add)
        }
        val streams = root["streams"] as? JsonArray
        streams.orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            candidateFrom(obj, rootHeaders)?.let(out::add)
        }
        when (val urls = root["url"]) {
            is JsonArray -> urls.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                candidateFrom(obj, rootHeaders)?.let(out::add)
            }
            else -> {
                val rootUrl = urls.textOrNull()
                if (rootUrl != null) {
                    out += Candidate(
                        url = rootUrl,
                        headers = rootHeaders,
                        language = "",
                        quality = root["quality"].textOrNull(),
                        type = root["type"].textOrNull() ?: "hls",
                    )
                }
            }
        }
        val allUrls = root["all_urls"] as? JsonArray
        allUrls.orEmpty().forEach { element ->
            val url = element.textOrNull() ?: return@forEach
            out += Candidate(url, rootHeaders, "", null, "hls")
        }
        return out.distinctBy { it.url }
    }

    private fun candidateFrom(obj: JsonObject, rootHeaders: Map<String, String>): Candidate? {
        val url = obj["url"].textOrNull()
            ?: obj["file"].textOrNull()
            ?: obj["link"].textOrNull()
            ?: return null
        val headers = rootHeaders + obj["headers"].headersOrEmpty()
        val language = obj["language"].textOrNull()
            ?: obj["lang"].textOrNull()
            ?: ""
        return Candidate(
            url = url,
            headers = headers,
            language = language,
            quality = obj["quality"].textOrNull()
                ?: obj["label"].textOrNull()
                ?: obj["resolution"].textOrNull(),
            type = obj["type"].textOrNull() ?: "hls",
        )
    }

    private fun getJson(url: String): JsonObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", Http.DESKTOP_UA)
            .build()
        resolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "resolver HTTP ${response.code}")
                return@use null
            }
            val body = response.body?.string().orEmpty()
            Http.json.parseToJsonElement(body) as? JsonObject
        }
    }.onFailure { Log.w(TAG, "resolver request failed", it) }.getOrNull()

    private fun relayUrl(source: String, headers: Map<String, String>): String =
        Uri.parse(RELAY_ENDPOINT).buildUpon()
            .appendQueryParameter("source", source)
            .appendQueryParameter("headers", headers.toJson())
            .build()
            .toString()

    private fun probeHls(relayUrl: String): Boolean {
        var playlistUrl = relayUrl
        repeat(MAX_PLAYLIST_DEPTH) {
            val playlist = fetchText(playlistUrl) ?: return false
            if (!playlist.trimStart().startsWith("#EXTM3U")) return false
            val next = firstPlaylistReference(playlist) ?: return false
            val nextUrl = resolveUrl(playlistUrl, next) ?: return false
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                playlistUrl = nextUrl
            } else {
                return probeSegment(nextUrl)
            }
        }
        return false
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
            .header("User-Agent", Http.DESKTOP_UA)
            .build()
        resolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            String(bytes, StandardCharsets.UTF_8)
        }
    }.getOrNull()

    private fun probeSegment(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Range", SEGMENT_RANGE)
            .header("User-Agent", Http.DESKTOP_UA)
            .build()
        resolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val bytes = response.body?.bytes() ?: byteArrayOf()
            if (bytes.size < 8) return@use false
            val ts = (bytes[0].toInt() and 0xff) == 0x47 && bytes.size >= 188
            val marker = String(bytes.copyOfRange(4, 8), StandardCharsets.US_ASCII)
            ts || marker == "ftyp" || marker == "styp" || marker == "moof" || marker == "sidx"
        }
    }.getOrDefault(false)

    private fun firstPlaylistReference(playlist: String): String? {
        val lineReference = playlist.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (lineReference != null) return lineReference
        return Regex("URI=\"([^\"]+)\"")
            .find(playlist)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun resolveUrl(base: String, reference: String): String? =
        runCatching { URI(base).resolve(reference).toString() }.getOrNull()

    private fun isHls(candidate: Candidate): Boolean {
        val lowerUrl = candidate.url.lowercase()
        return candidate.type.equals("hls", ignoreCase = true)
            || lowerUrl.contains(".m3u8")
            || lowerUrl.contains("/hls/")
    }

    private fun isAllowedSource(url: String): Boolean {
        val source = runCatching { URI(url) }.getOrNull() ?: return false
        if (source.scheme != "https") return false
        val host = source.host?.lowercase() ?: return false
        val path = source.path?.lowercase().orEmpty()
        val hostAllowed = host in EXACT_MEDIA_HOSTS
            || host.endsWith(".site")
            || host.endsWith(".individualbranding.cyou")
            || host.endsWith(".tiktokcdn.com")
            || host.endsWith(".qixno442run.com")
            || host.endsWith(".rasta428jem.com")
            || host.endsWith(".workers.dev")
        if (!hostAllowed) return false
        return path.endsWith(".m3u8")
            || path.contains("/hls/")
            || host == "megacloud.animanga.fun"
            || host == "upcloud.animanga.fun"
    }

    private fun isAllowedLanguage(language: String): Boolean {
        val normalized = language.trim().lowercase()
        return normalized.isBlank()
            || normalized == "english"
            || normalized == "original"
            || normalized == "main"
            || normalized.contains("english")
            || normalized.contains("original")
    }

    private fun JsonElement?.textOrNull(): String? = runCatching {
        this?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }.getOrNull()

    private fun JsonElement?.headersOrEmpty(): Map<String, String> {
        val obj = this as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            value.textOrNull()?.let { key to it }
        }.toMap()
    }

    private fun Map<String, String>.toJson(): String = buildJsonObject {
        forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }.toString()

    private fun String.capitalizeForLabel(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
