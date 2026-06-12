package com.dt.streamz.scraper.tmdb

import android.util.Log
import com.dt.streamz.BuildConfig
import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.Http
import com.dt.streamz.scraper.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * "Must Watch" feed backed by TMDb — the trending + now-playing titles
 * people are actually talking about this week. Unlike VidSrcProvider's
 * hand-curated static list, this is live data, so genuinely viral/new
 * releases (e.g. a fresh "Obsession") surface on their own.
 *
 * Playback reuses the same DirectEmbed → WebPlayer chain as VidSrc; the
 * embed aggregators all accept TMDb ids natively, so no IMDB mapping is
 * needed. TV episode lists come from TMDb season endpoints.
 *
 * Key handling: [BuildConfig.TMDB_API_KEY] is injected from local.properties
 * (gitignored). With no key the provider reports [isEnabled] = false and
 * every method returns empty, so the Must-Watch row simply doesn't render.
 *
 * Title ids are composite: "movie/<tmdbId>" or "tv/<tmdbId>", so details()
 * and streams() know the media type without another lookup.
 */
class TmdbProvider(
    private val apiKey: String = BuildConfig.TMDB_API_KEY,
) : Provider {

    override val id = "tmdb"
    override val displayName = "Must Watch"
    // Intentionally NOT a generic anime/movie provider: it feeds only the
    // dedicated Must-Watch row, so it stays out of the per-provider browse
    // loops and the merged search.
    override val supportsAnime = false
    override val supportsMovies = false

    fun isEnabled(): Boolean = apiKey.isNotBlank()

    /** Trending-this-week ∪ now-playing, deduped — the viral/new feed. */
    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResult>()
        for (path in listOf("trending/all/week", "movie/now_playing", "tv/on_the_air")) {
            val results = fetchResults(path)
            for (r in results) {
                if (seen.add(r.id)) out.add(r)
                if (out.size >= MAX_ROW) return@withContext out
            }
        }
        out
    }

    // Not a search source — IMDB search via VidSrc already covers that, and
    // mixing TMDb into search would duplicate every result.
    override suspend fun search(query: String): List<SearchResult> = emptyList()

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val (mediaType, tmdbId) = parseId(titleId)
        val obj = getJson("$API/$mediaType/$tmdbId?api_key=$apiKey&language=en-US")
            ?: return@withContext stub(titleId, "TMDb lookup failed — try again.")

        val isMovie = mediaType == "movie"
        val title = (if (isMovie) obj.str("title") else obj.str("name")) ?: titleId
        val poster = obj.str("poster_path")?.let { POSTER + it }
        val backdrop = obj.str("backdrop_path")?.let { BACKDROP + it }
        val overview = obj.str("overview")
        val year = (if (isMovie) obj.str("release_date") else obj.str("first_air_date"))
            ?.take(4)?.toIntOrNull()

        val episodes = if (isMovie) {
            listOf(Episode(id = "movie", number = 1, title = "Watch"))
        } else {
            fetchTvEpisodes(tmdbId, obj)
        }

        TitleDetails(
            providerId = id,
            id = titleId,
            title = title,
            poster = poster,
            backdrop = backdrop,
            synopsis = overview,
            year = year,
            kind = if (isMovie) MediaKind.Movie else MediaKind.Series,
            episodes = episodes,
            qualityNote = if (isMovie && year != null && year >= java.time.LocalDate.now().year)
                "⚠ recent release ($year) — embed may be CAM/TS quality"
            else null,
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> {
        val (mediaType, tmdbId) = parseId(titleId)
        val isMovie = mediaType == "movie"
        val (season, ep) = if (isMovie) "" to "" else {
            val m = Regex("s(\\d+)e(\\d+)").find(episode.id)
            (m?.groupValues?.get(1) ?: "1") to (m?.groupValues?.get(2) ?: episode.number.toString())
        }
        // Same embed families as VidSrc — they all take TMDb ids. Order by
        // independent infrastructure first; WebPlayer walks the list on
        // failure. Diversity > redundancy.
        fun src(label: String, url: String, referer: String) =
            StreamSource(url = url, kind = StreamKind.DirectEmbed, serverLabel = label,
                headers = mapOf("Referer" to referer))
        return if (isMovie) listOf(
            src("vidsrc.cc", "https://vidsrc.cc/v2/embed/movie/$tmdbId", "https://vidsrc.cc/"),
            src("embed.su", "https://embed.su/embed/movie/$tmdbId", "https://embed.su/"),
            src("autoembed", "https://player.autoembed.cc/embed/movie/$tmdbId", "https://player.autoembed.cc/"),
            src("multiembed", "https://multiembed.mov/?video_id=$tmdbId&tmdb=1", "https://multiembed.mov/"),
            src("vidsrc.to", "https://vidsrc.to/embed/movie/$tmdbId", "https://vidsrc.to/"),
        ) else listOf(
            src("vidsrc.cc", "https://vidsrc.cc/v2/embed/tv/$tmdbId/$season/$ep", "https://vidsrc.cc/"),
            src("embed.su", "https://embed.su/embed/tv/$tmdbId/$season/$ep", "https://embed.su/"),
            src("autoembed", "https://player.autoembed.cc/embed/tv/$tmdbId/$season/$ep", "https://player.autoembed.cc/"),
            src("multiembed", "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$ep", "https://multiembed.mov/"),
            src("vidsrc.to", "https://vidsrc.to/embed/tv/$tmdbId/$season/$ep", "https://vidsrc.to/"),
        )
    }

    // --- internals ---

    private fun fetchResults(path: String): List<SearchResult> {
        val sep = if (path.contains("?")) "&" else "?"
        val obj = getJson("$API/$path$sep" + "api_key=$apiKey&language=en-US&page=1") ?: return emptyList()
        val arr = obj["results"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // trending/all returns a media_type per item; the movie/tv list
            // endpoints don't, so infer from the path.
            val mediaType = o.str("media_type") ?: when {
                path.startsWith("movie") -> "movie"
                path.startsWith("tv") -> "tv"
                else -> return@mapNotNull null
            }
            if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
            val tmdbId = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = (if (mediaType == "movie") o.str("title") else o.str("name"))
                ?: return@mapNotNull null
            val poster = o.str("poster_path")?.let { POSTER + it }
            val year = (if (mediaType == "movie") o.str("release_date") else o.str("first_air_date"))
                ?.take(4)?.toIntOrNull()
            SearchResult(
                providerId = id,
                id = "$mediaType/$tmdbId",
                title = title,
                poster = poster,
                year = year,
                kind = if (mediaType == "movie") MediaKind.Movie else MediaKind.Series,
            )
        }
    }

    /** Fetch every regular season's episodes in parallel, flat-numbered. */
    private suspend fun fetchTvEpisodes(tmdbId: String, show: JsonObject): List<Episode> = coroutineScope {
        val seasons = (show["seasons"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("season_number")?.jsonPrimitive?.intOrNull }
            ?.filter { it >= 1 }   // skip season 0 (specials)
            ?.sorted()
            ?: return@coroutineScope listOf(Episode(id = "s1e1", number = 1, title = null))
        val perSeason = seasons.map { s ->
            async(Dispatchers.IO) {
                val obj = getJson("$API/tv/$tmdbId/season/$s?api_key=$apiKey&language=en-US")
                val eps = (obj?.get("episodes") as? JsonArray).orEmptyArray()
                eps.mapNotNull { el ->
                    val e = el as? JsonObject ?: return@mapNotNull null
                    val num = e["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    Triple(s, num, e.str("name"))
                }
            }
        }.awaitAll().flatten()
        if (perSeason.isEmpty()) return@coroutineScope listOf(Episode(id = "s1e1", number = 1, title = null))
        perSeason.mapIndexed { idx, (s, n, name) ->
            Episode(id = "s${s}e$n", number = idx + 1, title = name)
        }
    }

    private fun parseId(titleId: String): Pair<String, String> {
        val parts = titleId.split("/", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "movie" to titleId
    }

    private fun stub(titleId: String, note: String): TitleDetails {
        val (mediaType, _) = parseId(titleId)
        return TitleDetails(
            providerId = id, id = titleId, title = titleId, poster = null, backdrop = null,
            synopsis = note, year = null,
            kind = if (mediaType == "tv") MediaKind.Series else MediaKind.Movie,
            episodes = emptyList(),
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

    private fun getJson(url: String): JsonObject? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET ${url.substringBefore("?api_key")} -> HTTP ${resp.code}")
                return@use null
            }
            val body = resp.body?.string() ?: return@use null
            Http.json.parseToJsonElement(body).jsonObject
        }
    }.onFailure { Log.w(TAG, "TMDb request failed", it) }.getOrNull()

    companion object {
        private const val TAG = "TmdbProvider"
        private const val API = "https://api.themoviedb.org/3"
        private const val POSTER = "https://image.tmdb.org/t/p/w342"
        private const val BACKDROP = "https://image.tmdb.org/t/p/w780"
        private const val MAX_ROW = 30
    }
}
