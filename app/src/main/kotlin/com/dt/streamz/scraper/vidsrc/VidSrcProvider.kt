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

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        // Curated list of IMDB IDs for the Movies tab home row. No network —
        // IMDB poster URLs are built from the ID so the tab can paint
        // instantly. search()/details() will cache-populate when the user
        // opens one, so nothing breaks if these IDs later change kind.
        CURATED.map { entry ->
            val result = SearchResult(
                providerId = id,
                id = entry.imdbId,
                title = entry.title,
                poster = entry.poster,
                year = entry.year,
                kind = entry.kind,
            )
            cache[entry.imdbId] = CachedResult(result)
            result
        }
    }

    /** Genre tags for the Genres tab — keyed by IMDB id. */
    fun genresFor(imdbId: String): List<String> =
        CURATED.firstOrNull { it.imdbId == imdbId }?.genres.orEmpty()

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

    /**
     * Type-ahead title suggestions for the unified Search bar — powered by
     * IMDB's suggestion API (same source as [search]), so the dropdown shows
     * real movie/show/anime titles as you type, the way YouTube's search bar
     * does. Movies + series only (drops people/companies). Best-effort: any
     * failure yields an empty list.
     */
    override suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim().replace(Regex("[^A-Za-z0-9 ]"), "").replace(' ', '_')
        if (q.length < 2) return@withContext emptyList()
        val url = "$IMDB_SUGGEST/${q.first().lowercaseChar()}/${q}.json?includeVideos=0"
        val body = fetch(url) ?: return@withContext emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext emptyList()
        val items = root["d"] as? JsonArray ?: return@withContext emptyList()
        items.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val qType = obj["q"]?.jsonPrimitive?.contentOrNull
            if (qType !in TITLE_TYPES) return@mapNotNull null
            obj["l"]?.jsonPrimitive?.contentOrNull
        }.distinct().take(8)
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val cached = cache[titleId]?.result
        val title = cached?.title ?: titleId
        val poster = cached?.poster
        val year = cached?.year
        val kind = cached?.kind ?: MediaKind.Movie

        val episodes = when (kind) {
            MediaKind.Movie -> listOf(
                Episode(id = "movie", number = 1, title = "Watch"),
            )
            MediaKind.Series, MediaKind.Anime -> fetchTvEpisodes(title, titleId)
                ?: (1..SYNTHETIC_EPISODE_COUNT).map { n ->
                    Episode(id = "s1e$n", number = n, title = null)
                }
        }

        val syntheticFallback = kind != MediaKind.Movie &&
            episodes.size == SYNTHETIC_EPISODE_COUNT &&
            episodes.first().id == "s1e1" &&
            episodes.first().title == null

        val qualityNote = if (kind == MediaKind.Movie) camWarningFor(titleId, year) else null

        TitleDetails(
            providerId = id,
            id = titleId,
            title = title,
            poster = poster,
            backdrop = poster,
            synopsis = when {
                kind == MediaKind.Movie -> null
                syntheticFallback ->
                    "TVMaze had no episode data — picking any episode and VidSrc/2embed will try to resolve it."
                else -> null
            },
            year = year,
            kind = kind,
            episodes = episodes,
            qualityNote = qualityNote,
        )
    }

    /**
     * Best-effort CAM/TS warning. Ideally we'd fetch IMDB's title page
     * and parse JSON-LD `datePublished` for 15-day precision, but IMDB
     * fronts AWS WAF that blocks OkHttp requests. Fall back to a coarse
     * year-based heuristic: movies released in the current calendar year
     * are likely to have CAM rips only until HD/digital releases later.
     * Wire TMDb later for real 15-day precision.
     */
    private fun camWarningFor(imdbId: String, year: Int?): String? {
        if (year == null) return null
        val currentYear = java.time.LocalDate.now().year
        if (year < currentYear) return null
        return "⚠ recent release (${year}) — embed may be CAM/TS quality"
    }

    /**
     * Look up real seasons + episode counts from TVMaze by title name,
     * filtered to the show whose externals.imdb matches our IMDB titleId.
     * Returns null on any miss so the caller falls back to the synthetic
     * 50-episode grid (same behavior as before this method existed).
     */
    private suspend fun fetchTvEpisodes(title: String, imdbId: String): List<Episode>? {
        val searchUrl = "$TVMAZE/search/shows?q=${title.urlEncoded()}"
        val searchBody = fetch(searchUrl) ?: return null
        val hits = runCatching { json.parseToJsonElement(searchBody) as JsonArray }.getOrNull()
            ?: return null

        val showId = hits
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it["show"] as? JsonObject }
            .firstOrNull { show ->
                val ext = show["externals"] as? JsonObject ?: return@firstOrNull false
                ext["imdb"]?.jsonPrimitive?.contentOrNull == imdbId
            }
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        val epsBody = fetch("$TVMAZE/shows/$showId/episodes") ?: return null
        val epArr = runCatching { json.parseToJsonElement(epsBody) as JsonArray }.getOrNull()
            ?: return null

        return epArr
            .mapNotNull { it as? JsonObject }
            .mapNotNull { ep ->
                val season = ep["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                val number = ep["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                val epTitle = ep["name"]?.jsonPrimitive?.contentOrNull
                val runtime = ep["runtime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                Episode(
                    id = "s${season}e$number",
                    number = absoluteEpisodeNumber(season, number, epArr),
                    title = epTitle,
                    runtimeSeconds = runtime?.times(60),
                )
            }
            .sortedBy { it.number }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Episode.number on our side is a flat 1..N index — VidSrc's `streams()`
     * reconstructs season/ep from the `s<S>e<N>` id string, so the number
     * field just needs to sort stably. Use sequential position in the
     * season-ordered list.
     */
    private fun absoluteEpisodeNumber(
        season: Int,
        number: Int,
        all: JsonArray,
    ): Int {
        var idx = 0
        for (el in all) {
            val ep = el as? JsonObject ?: continue
            val s = ep["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            val n = ep["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            idx++
            if (s == season && n == number) return idx
        }
        return number
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

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
        // Order matters — the WebPlayer walks top-to-bottom on transport
        // failures or no-media-traffic timeouts.
        //
        // 2026-06-13 mirror refresh: the previous list was dominated by
        // hosts that have since gone fully dead (DNS NXDOMAIN, not just
        // network-blocked) — embed.su, autoembed.cc, moviesapi.club,
        // vidsrc.net, vidsrc.xyz, vidsrc.icu, vidsrc.mov. The box's debug
        // log showed EVERY mirror failing (ERR_NAME_NOT_RESOLVED / SSL /
        // timeout) which is why nothing would play. Rebuilt from hosts
        // verified reachable on 2026-06-13, front-loading the clean modern
        // players (vidlink) and keeping infra diverse so a single CDN ban
        // doesn't take them all down together.
        return paths.flatMap { path ->
            val pathKindIsTv = path.startsWith("tv/")
            val (imdb, season, ep) = if (pathKindIsTv) {
                val parts = path.removePrefix("tv/").split("/")
                Triple(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
            } else {
                Triple(path.removePrefix("movie/"), "", "")
            }

            listOf(
                // vidlink.pro — modern, single-iframe player, minimal popups;
                // best UX inside the box's WebView. Accepts IMDB ids directly.
                StreamSource(
                    url = if (pathKindIsTv) "https://vidlink.pro/tv/$imdb/$season/$ep"
                          else "https://vidlink.pro/movie/$imdb",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "vidlink",
                    headers = mapOf("Referer" to "https://vidlink.pro/"),
                ),
                // vidsrc.to — long-lived, reliable, IMDB path shape.
                StreamSource(
                    url = "https://vidsrc.to/embed/$path",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "VidSrc · .to",
                    headers = mapOf("Referer" to "https://vidsrc.to/"),
                ),
                // 2embed — independent infra. NOTE: TV uses /embedtv/<id>&s=&e=,
                // NOT /embed/tv/... (the old code malformed this and it always
                // 404'd). Movie uses /embed/<id>.
                StreamSource(
                    url = if (pathKindIsTv) "https://www.2embed.cc/embedtv/$imdb&s=$season&e=$ep"
                          else "https://www.2embed.cc/embed/$imdb",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "2embed",
                    headers = mapOf("Referer" to "https://www.2embed.cc/"),
                ),
                // vidsrc.cc v2 — lowercase movie/tv (the old capital-M "Movie"
                // shape is dead). Different player chain than vidsrc.to.
                StreamSource(
                    url = if (pathKindIsTv) "https://vidsrc.cc/v2/embed/tv/$imdb/$season/$ep"
                          else "https://vidsrc.cc/v2/embed/movie/$imdb",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "vidsrc.cc",
                    headers = mapOf("Referer" to "https://vidsrc.cc/"),
                ),
                // vidsrc.in / vidsrc.pm — vidsrc.xyz-family aliases that are
                // currently resolving (the .xyz/.net apex are not). Same
                // /embed/movie|tv path shape.
                StreamSource(
                    url = if (pathKindIsTv) "https://vidsrc.in/embed/tv/$imdb/$season/$ep"
                          else "https://vidsrc.in/embed/movie/$imdb",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "VidSrc · .in",
                    headers = mapOf("Referer" to "https://vidsrc.in/"),
                ),
                StreamSource(
                    url = if (pathKindIsTv) "https://vidsrc.pm/embed/tv/$imdb/$season/$ep"
                          else "https://vidsrc.pm/embed/movie/$imdb",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "VidSrc · .pm",
                    headers = mapOf("Referer" to "https://vidsrc.pm/"),
                ),
                // multiembed — last resort. Reachable but the box has shown
                // intermittent SSL handshake trouble against it; keep it as a
                // tail mirror rather than dropping a working-elsewhere source.
                StreamSource(
                    url = if (pathKindIsTv) "https://multiembed.mov/?video_id=$imdb&tmdb=0&s=$season&e=$ep"
                          else "https://multiembed.mov/?video_id=$imdb&tmdb=0",
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "multiembed",
                    headers = mapOf("Referer" to "https://multiembed.mov/"),
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
        private const val TVMAZE = "https://api.tvmaze.com"
        private const val SYNTHETIC_EPISODE_COUNT = 50
        // IMDB suggestion `q` values that are watchable titles (vs people /
        // companies) — used to filter type-ahead suggestions.
        private val TITLE_TYPES = setOf(
            "feature", "video", "TV series", "TV mini-series", "TV short", "TV movie",
        )

        // Hand-curated for the Movies tab home row. Kind + poster URL are
        // static so browse() stays network-free; IMDB's CDN serves these
        // straight from the m.media-amazon.com slug. Swap entries here when
        // popular picks change.
        private val CURATED: List<CuratedEntry> = listOf(
            CuratedEntry("tt15398776", "Oppenheimer", 2023, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BMDBmYTZjNjUtN2M1MS00MTQ2LTk2ODgtNzc2M2QyZGE5NTVjXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Thriller")),
            CuratedEntry("tt6710474", "Everything Everywhere All at Once", 2022, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BYTdiOTIyZTQtNmQ1OS00NjZlLWIyMTgtYzk5Y2M3ZDVmMDk1XkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Comedy", "Sci-Fi")),
            CuratedEntry("tt1517268", "Barbie", 2023, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BNjU3N2QxNzYtMjk1NC00MTc4LTk1NTQtMmUxNTljM2I0NDA5XkEyXkFqcGc@._V1_.jpg",
                listOf("Comedy", "Drama")),
            CuratedEntry("tt9114286", "Black Panther: Wakanda Forever", 2022, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BNTM4NjIxNmEtYWE5NS00NDczLTkyNWQtYThhNmQyZGQzMjM0XkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Sci-Fi")),
            CuratedEntry("tt10366206", "John Wick: Chapter 4", 2023, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BMDExZGMyOTMtMDgyYi00NGIwLWJhMTEtOTdkZGFjNmZiMTEwXkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Thriller")),
            CuratedEntry("tt15239678", "Dune: Part Two", 2024, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BNjkyMjAyY2QtMzY0Ny00ZWUzLWI3YmQtNmIyMmU2OTc3NjYyXkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Sci-Fi")),
            CuratedEntry("tt9362722", "Spider-Man: Across the Spider-Verse", 2023, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BMzI0NmVkMjEtYmY4MS00ZDMxLTlkZmEtMzU4MDQxYTMzMjU2XkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Sci-Fi")),
            CuratedEntry("tt1160419", "Dune", 2021, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BN2FjNmEyNWMtYzM0ZS00NjIyLTg5YzYtYThlMGVjNzE1OGViXkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Sci-Fi")),
            CuratedEntry("tt0468569", "The Dark Knight", 2008, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BMTMxNTMwODM0NF5BMl5BanBnXkFtZTcwODAyMTk2Mw@@._V1_.jpg",
                listOf("Action", "Thriller", "Drama")),
            CuratedEntry("tt0111161", "The Shawshank Redemption", 1994, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BMDAyY2FhYjctNDc5OS00MDNlLThiMGUtY2UxYWVkNGY2ZjljXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama")),
            CuratedEntry("tt0109830", "Forrest Gump", 1994, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BNDYwNzVjMTItZmU5YS00YjQ5LTljYjgtMjY2NDVmYWMyNWFmXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Comedy")),
            CuratedEntry("tt0816692", "Interstellar", 2014, MediaKind.Movie,
                "https://m.media-amazon.com/images/M/MV5BYzdjMDAxZGItMjI2My00ODA1LTlkNzItOWFjMDU5ZDJlYWY3XkEyXkFqcGc@._V1_.jpg",
                listOf("Sci-Fi", "Drama")),
            CuratedEntry("tt0903747", "Breaking Bad", 2008, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BMzU5ZGYzNmQtMTdhYy00OGRiLTg0NmQtYjVjNzliZTg1ZGE4XkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Thriller")),
            CuratedEntry("tt6439752", "Snowfall", 2017, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BNmM2YTgzOWUtNjZmNy00NjA3LThkZjMtMWMxMmUyMTVkNjFkXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Thriller")),
            CuratedEntry("tt4574334", "Stranger Things", 2016, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BMjg2NmM0MTEtYWY2Yy00NmFlLTllNTMtMjVkZjEwMGVlNzdjXkEyXkFqcGc@._V1_.jpg",
                listOf("Sci-Fi", "Horror", "Drama")),
            CuratedEntry("tt0944947", "Game of Thrones", 2011, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BMTNhMDJmNmYtNDQ5OS00ODdlLWE0ZDAtZTgyYTIwNDY3OTU3XkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Action", "Sci-Fi")),
            CuratedEntry("tt5180504", "The Witcher", 2019, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BN2FiOWU4YzYtMzZiOS00MzcyLTlkOGEtOTgwZmEwMzAxMzA3XkEyXkFqcGc@._V1_.jpg",
                listOf("Action", "Sci-Fi")),
            CuratedEntry("tt7366338", "Chernobyl", 2019, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BZGQ2YmMxZmEtYjI5OS00NzlkLTlkNTEtYWMyMzkyMzc1ZmRlXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Thriller")),
            CuratedEntry("tt11198330", "House of the Dragon", 2022, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BOTc5ZWFjMTYtMGQyYS00NzFiLWE4NTctYzY5NjdjNjVmMzZkXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Action")),
            CuratedEntry("tt14452776", "The Last of Us", 2023, MediaKind.Series,
                "https://m.media-amazon.com/images/M/MV5BZGQ2YzY3YzctZWY1Ni00ZTgwLTk3MjMtMWI2MWQyYTM5YmZhXkEyXkFqcGc@._V1_.jpg",
                listOf("Drama", "Horror", "Sci-Fi")),
        )
    }

    private data class CuratedEntry(
        val imdbId: String,
        val title: String,
        val year: Int,
        val kind: MediaKind,
        val poster: String,
        val genres: List<String>,
    )
}
