package com.dt.streamz.scraper.vidsrc

import android.util.Log
import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.Http
import com.dt.streamz.scraper.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Movie/TV provider backed by two public, unauthenticated endpoints:
 *
 *   1. IMDB's realtime suggestion API for search + metadata
 *      (v3.sg.media-imdb.com/suggestion/x/<query>.json)
 *   2. vidsrc.to as the universal embed aggregator for playback
 *      (+ 2embed.cc as a secondary embed for multi-source)
 *
 * fmovies — the plan's original Phase 4 target — is parked / dead; vidsrc
 * is a better fit because one provider covers all movies AND TV shows
 * without needing a per-site scraper. Episode lists for TV are
 * synthesized (50 episodes, season 1) because IMDB's suggestion API
 * doesn't give episode counts; we accept that the user may occasionally
 * pick an episode that doesn't exist on the embed — the WebView shows
 * an error rather than crashing the app.
 */
class VidSrcProvider(
    private val json: Json = Http.json,
) : Provider {

    override val id = "vidsrc"
    override val displayName = "VidSrc (movies + TV)"
    override val supportsMovies = true
    override val supportsAnime = false

    private val cache = mutableMapOf<String, CachedResult>()

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim().replace(Regex("[^A-Za-z0-9 ]"), "").replace(' ', '_')
        if (q.isBlank()) return@withContext emptyList()
        val url = "$IMDB_SUGGEST/${q.first().lowercaseChar()}/${q}.json?includeVideos=0"
        val body = fetch(url) ?: return@withContext emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext emptyList()
        val items = root["d"] as? JsonArray ?: return@withContext emptyList()

        items.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val imdbId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val qType = obj["q"]?.jsonPrimitive?.contentOrNull
            val kind = when (qType) {
                "feature", "video" -> MediaKind.Movie
                "TV series", "TV mini-series", "TV short", "TV episode" -> MediaKind.Series
                else -> return@mapNotNull null
            }
            val title = obj["l"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val year = (obj["y"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toIntOrNull()
            val poster = (obj["i"] as? JsonObject)?.get("imageUrl")?.jsonPrimitive?.contentOrNull
            val result = SearchResult(
                providerId = id,
                id = imdbId,
                title = title,
                poster = poster,
                year = year,
                kind = kind,
            )
            cache[imdbId] = CachedResult(result)
            result
        }
    }

    override suspend fun details(titleId: String): TitleDetails {
        val cached = cache[titleId]?.result
        val title = cached?.title ?: titleId
        val poster = cached?.poster
        val year = cached?.year
        val kind = cached?.kind ?: MediaKind.Movie

        val episodes = when (kind) {
            MediaKind.Movie -> listOf(
                Episode(id = "movie", number = 1, title = "Watch"),
            )
            MediaKind.Series, MediaKind.Anime -> (1..SYNTHETIC_EPISODE_COUNT).map { n ->
                Episode(id = "s1e$n", number = n, title = null)
            }
        }

        return TitleDetails(
            providerId = id,
            id = titleId,
            title = title,
            poster = poster,
            backdrop = poster,
            synopsis = if (kind != MediaKind.Movie) {
                "Season 1 episode grid is synthesized — pick any episode and VidSrc/2embed will try to resolve it."
            } else null,
            year = year,
            kind = kind,
            episodes = episodes,
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> {
        val kind = cache[titleId]?.result?.kind ?: MediaKind.Movie
        val paths = when (kind) {
            MediaKind.Movie -> listOf("movie/$titleId")
            else -> {
                val ep = episode.id.takeIf { it.matches(Regex("s\\d+e\\d+")) }
                    ?: "s1e${episode.number}"
                val season = ep.substringAfter('s').substringBefore('e').toInt()
                val number = ep.substringAfter('e').toInt()
                listOf("tv/$titleId/$season/$number")
            }
        }
        return paths.flatMap { path ->
            listOf(
                StreamSource(
                    url = "https://vidsrc.to/embed/$path",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "VidSrc",
                ),
                StreamSource(
                    url = "https://www.2embed.cc/embed/$path",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "2embed",
                ),
            )
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()

    private data class CachedResult(val result: SearchResult)

    companion object {
        private const val TAG = "VidSrcProvider"
        private const val IMDB_SUGGEST = "https://v3.sg.media-imdb.com/suggestion/x"
        private const val SYNTHETIC_EPISODE_COUNT = 50
    }
}
