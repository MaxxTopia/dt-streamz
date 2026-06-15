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
        val data = graphql(BROWSE_Q, "{}") ?: return@withContext emptyList()
        val media = data.path("data", "Page")?.get("media") as? JsonArray ?: return@withContext emptyList()
        val out = media.mapNotNull { (it as? JsonObject)?.toResult() }
        DebugLog.i(TAG, "browse() -> ${out.size} trending anime")
        out
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val data = graphql(SEARCH_Q, """{"s":${query.q()}}""") ?: run {
            DebugLog.w(TAG, "search($query) null (AniList unreachable)")
            return@withContext emptyList()
        }
        val media = data.path("data", "Page")?.get("media") as? JsonArray ?: return@withContext emptyList()
        val out = media.mapNotNull { (it as? JsonObject)?.toResult() }
        DebugLog.i(TAG, "search($query) -> ${out.size} anime")
        out
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val cached = cache[titleId]
        val data = graphql(DETAILS_Q, """{"id":${titleId.toIntOrNull() ?: 0}}""")
        val media = data?.path("data", "Media")
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
        DebugLog.i(TAG, "details($titleId) -> $n episodes")
        TitleDetails(
            providerId = id, id = titleId, title = title, poster = poster, backdrop = banner,
            synopsis = desc, year = media?.get("seasonYear").int(),
            kind = MediaKind.Anime,
            episodes = (1..n).map { Episode(id = "ep:$it", number = it, title = "Episode $it") },
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> {
        val ep = episode.id.removePrefix("ep:").toIntOrNull() ?: episode.number
        // vidnest anime embed (AniList id). Offer Sub + Dub; the picker's
        // remembered audio preference auto-picks after the first time.
        fun src(label: String, type: String) = StreamSource(
            url = "https://vidnest.fun/anime/$titleId/$ep/$type",
            kind = StreamKind.DirectEmbed,
            serverLabel = label,
            headers = mapOf("Referer" to "https://vidnest.fun/"),
        )
        DebugLog.i(TAG, "streams($titleId ep=$ep) -> vidnest sub+dub")
        return listOf(src("anime · Sub", "sub"), src("anime · Dub", "dub"))
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

        private const val MEDIA_FIELDS =
            "id title{romaji english} episodes coverImage{large} seasonYear format"
        private const val BROWSE_Q =
            "query{ Page(perPage:24){ media(type:ANIME, sort:TRENDING_DESC, isAdult:false){ $MEDIA_FIELDS } } }"
        private const val SEARCH_Q =
            "query(\$s:String){ Page(perPage:24){ media(search:\$s, type:ANIME, sort:SEARCH_MATCH, isAdult:false){ $MEDIA_FIELDS } } }"
        private const val DETAILS_Q =
            "query(\$id:Int){ Media(id:\$id, type:ANIME){ $MEDIA_FIELDS bannerImage description(asHtml:false) nextAiringEpisode{episode} } }"
    }
}
