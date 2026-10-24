package com.dt.streamz.scraper.anilist

import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.diag.DebugLog
import com.dt.streamz.scraper.Http
import com.dt.streamz.scraper.Provider
import com.dt.streamz.scraper.megaplay.MegaPlayResolver
import com.dt.streamz.scraper.vidnest.VidNestNativeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Anime via AniList (metadata) + a WebView embed player (playback) — the
 * same shape that makes movies/TV work on the box, and it sidesteps the
 * Cloudflare wall that killed the scraping providers (animekai, AllAnime
 * both returned 403 from the box's IP).
 *
 *   - AniList GraphQL (graphql.anilist.co) is a clean public API, no CF
 *     challenge: search / trending / episode counts / posters.
 *   - Playback hands the AniList id to vidnest.fun's anime embed, rendered
 *     in the existing WebPlayer (vidnest is not CF-gated and exposes a
 *     same-origin <video>, so the new D-pad controls apply).
 *
 * Title ids are the AniList numeric id (as a string). Episodes are a flat
 * 1..N list. Sub + Dub are offered as two sources so the picker's remembered
 * audio preference auto-selects after the first choice.
 */
class AniListProvider : Provider {
    override val id = "anilist"
    override val displayName = "Anime"
    override val supportsAnime = true

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        val data = graphql(BROWSE_Q, "{}")
        if (data == null) {
            // AniList down — show hardcoded fallbacks so the Anime tab isn't empty.
            DebugLog.w(TAG, "browse() AniList down — returning fallbacks")
            return@withContext listOf(NARUTO_FALLBACK_RESULT, STREET_FIGHTER_FALLBACK_RESULT)
        }
        val media = data.path("data", "Page")?.get("media") as? JsonArray ?: return@withContext emptyList()
        val out = media.mapNotNull { (it as? JsonObject)?.toResult() }.toMutableList()
        if (out.none { it.id == STREET_FIGHTER_ID }) {
            out.add(STREET_FIGHTER_FALLBACK_RESULT)
        }
        DebugLog.i(TAG, "browse() -> ${out.size} trending anime")
        out
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val normalizedQuery = query.trim()
            .replace(Regex("(?i)streetfighter"), "street fighter")
        val data = graphql(SEARCH_Q, """{"s":${normalizedQuery.q()}}""") ?: run {
            DebugLog.w(TAG, "search($query) null (AniList unreachable)")
            val q = normalizedQuery.lowercase()
            if (q.contains("naruto") || q.contains("shippuden")) {
                return@withContext listOf(NARUTO_FALLBACK_RESULT)
            }
            if (q.contains("street") || q.contains("fighter")) {
                return@withContext listOf(STREET_FIGHTER_FALLBACK_RESULT)
            }
            return@withContext emptyList()
        }
        val media = data.path("data", "Page")?.get("media") as? JsonArray ?: return@withContext emptyList()
        val out = media.mapNotNull { (it as? JsonObject)?.toResult() }.toMutableList()
        val q = normalizedQuery.lowercase()
        if (q.contains("street") && q.contains("fighter")) {
            if (out.none { it.id == STREET_FIGHTER_ID }) {
                out.add(0, STREET_FIGHTER_FALLBACK_RESULT)
            }
        }
        DebugLog.i(TAG, "search($query) -> ${out.size} anime")
        out
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val cached = cache[titleId]
        val data = graphql(DETAILS_Q, """{"id":${titleId.toIntOrNull() ?: 0}}""")
        val media = data?.path("data", "Media")
        if (media == null && cached == null) {
            // Hardcoded fallback for Naruto: Shippuden — Diggy's primary
            // watch target. The playback URLs (VidNest + MegaPlay) don't
            // need AniList at all, so a known-good metadata stub keeps the
            // show fully watchable even during extended API outages.
            if (titleId == NARUTO_FALLBACK_ID) {
                DebugLog.w(TAG, "AniList down — using hardcoded Naruto: Shippuden fallback")
                return@withContext TitleDetails(
                    providerId = id, id = titleId,
                    title = "Naruto: Shippuden",
                    poster = NARUTO_FALLBACK_POSTER,
                    backdrop = NARUTO_FALLBACK_POSTER,
                    synopsis = null, year = 2007,
                    kind = MediaKind.Anime,
                    episodes = (1..NARUTO_FALLBACK_EPISODES).map {
                        Episode(
                            id = "ep:$it", number = it,
                            title = "Episode $it",
                            isFiller = FillerCatalog.isNarutoShippudenFiller(titleId, it),
                        )
                    },
                )
            }
            if (titleId == STREET_FIGHTER_ID) {
                DebugLog.w(TAG, "AniList down — using hardcoded Street Fighter fallback")
                return@withContext TitleDetails(
                    providerId = id, id = titleId,
                    title = "Street Fighter II: The Animated Movie",
                    poster = STREET_FIGHTER_POSTER,
                    backdrop = STREET_FIGHTER_BACKDROP,
                    synopsis = "Bison, the ruthless leader of the international terrorist organization Shadowlaw, has been desperately searching for the greatest fighter on the planet for years. He finds it in Ryu, a young wanderer who never stays in one place long enough for Bison to find him.",
                    year = 1994,
                    kind = MediaKind.Anime,
                    episodes = listOf(
                        Episode(
                            id = "ep:1", number = 1,
                            title = "Street Fighter II: The Animated Movie (English Dub)",
                        ),
                    ),
                )
            }
            // For all other titles, propagate the failure so the resume
            // handler treats it as a transient error and keeps the entry.
            throw java.io.IOException("AniList API unavailable and no cached metadata for $titleId")
        }
        val title = media?.let { animeTitle(it) } ?: cached?.title ?: titleId
        val poster = media?.path("coverImage")?.get("large").str() ?: cached?.poster
        val banner = media?.get("bannerImage").str() ?: poster
        val desc = media?.get("description").str()
            ?.replace(Regex("<[^>]+>"), "")?.replace("&quot;", "\"")?.replace("&#039;", "'")
        // Episode count: explicit `episodes`, else (airing - 1) for ongoing,
        // else a sensible default so the grid isn't empty.
        val epCount = media?.get("episodes").int()
            ?: media?.path("nextAiringEpisode")?.get("episode").int()?.let { it - 1 }
            ?: 12
        val n = epCount.coerceIn(1, 2000)
        val streamingTitles = media?.get("streamingEpisodes") as? JsonArray
        val episodeTitles = fetchTvMazeEpisodeTitles(title) + mapStreamingTitles(streamingTitles, n)
        DebugLog.i(TAG, "details($titleId) -> $n episodes")
        TitleDetails(
            providerId = id, id = titleId, title = title, poster = poster, backdrop = banner,
            synopsis = desc, year = media?.get("seasonYear").int(),
            kind = MediaKind.Anime,
            episodes = (1..n).map {
                Episode(
                    id = "ep:$it",
                    number = it,
                    title = episodeTitles[it] ?: "Episode $it",
                    isFiller = FillerCatalog.isNarutoShippudenFiller(titleId, it),
                )
            },
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> {
        val ep = episode.id.removePrefix("ep:").toIntOrNull() ?: episode.number
        // VidNest anime embed (AniList id). Keep language as an explicit,
        // visible choice and put the English track first in the picker.
        fun src(label: String, type: String) = StreamSource(
            url = "https://vidnest.fun/anime/$titleId/$ep/$type",
            kind = StreamKind.DirectEmbed,
            captionsDefaultOn = !type.equals("dub", ignoreCase = true),
            serverLabel = label,
            headers = mapOf("Referer" to "https://vidnest.fun/"),
        )
        // For Naruto: Shippuden, MegaPlay provides the direct dub HLS.
        // For other anime (such as Street Fighter II), VidNestNativeResolver queries
        // the first-party gogoanime/megavid relay endpoint for direct popup-free playback.
        val nativeDub = if (titleId == NARUTO_FALLBACK_ID) {
            MegaPlayResolver.resolveNarutoDub(titleId, ep)
        } else {
            VidNestNativeResolver.resolveAnimeDub(titleId, ep)
        }
        DebugLog.i(
            TAG,
            "streams($titleId ep=$ep) -> " +
                if (nativeDub != null) "native Dub + VidNest Dub/Sub"
                else "VidNest English Dub + original audio",
        )
        return listOfNotNull(
            nativeDub,
            src("VidNest - English Dub", "dub"),
            src("Original Japanese Audio + Subtitles", "sub"),
        )
    }

    // --- helpers ---

    private fun JsonObject.toResult(): SearchResult? {
        val aniId = this["id"].int()?.toString() ?: return null
        val title = animeTitle(this)
        val poster = path("coverImage")?.get("large").str()
        val r = SearchResult(
            providerId = id, id = aniId, title = title, poster = poster,
            year = this["seasonYear"].int(), kind = MediaKind.Anime,
        )
        cache[aniId] = r
        return r
    }

    private fun animeTitle(media: JsonObject): String {
        val t = media["title"] as? JsonObject
        return t?.get("english")?.str() ?: t?.get("romaji")?.str() ?: "Anime"
    }

    /**
     * AniList exposes streaming episode names as strings such as
     * "Episode 13 - You Aren't E-Rank, Are You?". Some seasonal entries use
     * absolute episode numbers, so when the provider returns exactly the
     * local season count we map them in numeric order to local 1..N.
     */
    private fun mapStreamingTitles(streaming: JsonArray?, episodeCount: Int): Map<Int, String> {
        val parsed = streaming.orEmpty()
            .mapNotNull { item ->
                val raw = (item as? JsonObject)?.get("title").str()?.trim()
                    ?: return@mapNotNull null
                val number = Regex("(?:episode|ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val clean = raw.replace(
                    Regex("^\\s*(?:episode|ep\\.?)\\s*\\d+\\s*(?:-|:|\\|)\\s*", RegexOption.IGNORE_CASE),
                    "",
                ).trim()
                if (clean.isBlank()) null else number to clean
            }
            .sortedBy { it.first ?: 0 }

        if (parsed.size == episodeCount) {
            return parsed.mapIndexed { index, (_, title) -> index + 1 to title }.toMap()
        }
        return parsed.mapNotNull { (number, title) ->
            number?.takeIf { it in 1..episodeCount }?.let { it to title }
        }.toMap()
    }

    /** TVMaze fills the gaps for anime whose AniList stream metadata is empty. */
    private fun fetchTvMazeEpisodeTitles(title: String): Map<Int, String> = runCatching {
        val seasonHint = Regex("\\bseason\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val queryTitle = title.replace(
            Regex("\\s+season\\s+\\d+.*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        val encoded = java.net.URLEncoder.encode(queryTitle, "UTF-8").replace("+", "%20")
        val req = Request.Builder()
            .url("https://api.tvmaze.com/singlesearch/shows?q=$encoded&embed=episodes")
            .header("Accept", "application/json")
            .header("User-Agent", Http.DESKTOP_UA)
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyMap()
            val root = Http.json.parseToJsonElement(resp.body?.string() ?: return@use emptyMap()).jsonObject
            val episodes = root.path("_embedded")?.get("episodes") as? JsonArray ?: return@use emptyMap()
            episodes.mapNotNull { raw ->
                val episode = raw as? JsonObject ?: return@mapNotNull null
                val season = episode["season"].int() ?: return@mapNotNull null
                val number = episode["number"].int() ?: return@mapNotNull null
                val name = episode["name"].str()?.trim() ?: return@mapNotNull null
                if (season == seasonHint) number to name else null
            }.toMap()
        }
    }.onFailure { DebugLog.w(TAG, "TVMaze episode names unavailable: ${it.message}") }
        .getOrDefault(emptyMap())

    private fun graphql(query: String, variables: String): JsonObject? = runCatching {
        val body = """{"query":${query.q()},"variables":$variables}"""
        val req = Request.Builder()
            .url(API)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", Http.DESKTOP_UA)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w(TAG, "AniList HTTP ${resp.code}")
                return@use null
            }
            Http.json.parseToJsonElement(resp.body?.string() ?: return@use null).jsonObject
        }
    }.onFailure { DebugLog.w(TAG, "AniList request failed: ${it.message}") }.getOrNull()

    private fun JsonObject.path(vararg keys: String): JsonObject? {
        var cur: JsonObject? = this
        for (k in keys) cur = cur?.get(k) as? JsonObject
        return cur
    }
    private fun kotlinx.serialization.json.JsonElement?.str(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun kotlinx.serialization.json.JsonElement?.int(): Int? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    private fun String.q(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        private const val TAG = "AniList"
        private const val API = "https://graphql.anilist.co"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val cache = mutableMapOf<String, SearchResult>()

        // Hardcoded fallback for the canonical Naruto: Shippuden title so
        // playback survives extended AniList outages. Values sourced from
        // CatalogIdentity.kt — duplicated here to keep the fallback self-
        // contained and the CatalogIdentity constants private.
        private const val NARUTO_FALLBACK_ID = "1735"
        private const val NARUTO_FALLBACK_EPISODES = 500
        private const val NARUTO_FALLBACK_POSTER =
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx1735-kGfVm0YqCPcu.png"
        private val NARUTO_FALLBACK_RESULT = SearchResult(
            providerId = "anilist", id = NARUTO_FALLBACK_ID,
            title = "Naruto: Shippuden", poster = NARUTO_FALLBACK_POSTER,
            year = 2007, kind = MediaKind.Anime,
        )

        private const val STREET_FIGHTER_ID = "1362"
        private const val STREET_FIGHTER_POSTER =
            "https://image.tmdb.org/t/p/w500/dZ1rqmE4eIhPbUCr7sn2Pw2wwGW.jpg"
        private const val STREET_FIGHTER_BACKDROP =
            "https://image.tmdb.org/t/p/w1280/ckjqoqCGbPyf8HknxXu4hXS9N4M.jpg"
        private val STREET_FIGHTER_FALLBACK_RESULT = SearchResult(
            providerId = "anilist", id = STREET_FIGHTER_ID,
            title = "Street Fighter II: The Animated Movie", poster = STREET_FIGHTER_POSTER,
            year = 1994, kind = MediaKind.Anime,
        )

        private const val MEDIA_FIELDS =
            "id title{romaji english} episodes coverImage{large} seasonYear format"
        private const val BROWSE_Q =
            "query{ Page(perPage:24){ media(type:ANIME, sort:TRENDING_DESC, isAdult:false){ $MEDIA_FIELDS } } }"
        private const val SEARCH_Q =
            "query(\$s:String){ Page(perPage:24){ media(search:\$s, type:ANIME, sort:SEARCH_MATCH, isAdult:false){ $MEDIA_FIELDS } } }"
        private const val DETAILS_Q =
            "query(\$id:Int){ Media(id:\$id, type:ANIME){ $MEDIA_FIELDS bannerImage description(asHtml:false) nextAiringEpisode{episode} streamingEpisodes{title} } }"
    }
}
