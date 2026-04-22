package com.dt.streamz.scraper.anicrush

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class AnicrushProvider(
    private val json: Json = Http.json,
) : Provider {

    override val id = "anicrush"
    override val displayName = "anicrush.to"
    override val supportsAnime = true

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "$API/shared/v2/movie/list".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("limit", "24")
            .addQueryParameter("page", "1")
            .build()

        val body = getJson(url.toString()) ?: return@withContext emptyList()
        val result = body.envelopeResult() ?: return@withContext emptyList()
        val search = runCatching {
            json.decodeFromJsonElement(SearchEnvelope.serializer(), result)
        }.getOrElse {
            Log.w(TAG, "search parse failed", it)
            return@withContext emptyList()
        }
        search.movies.map { it.toSearchResult() }
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val movieBody = getJson("$API/shared/v2/movie/getById/$titleId")
            ?: error("anicrush: title $titleId not found")
        val movieElement = movieBody.envelopeResult() ?: error("anicrush: empty details")
        val movie = json.decodeFromJsonElement(MovieDto.serializer(), movieElement)

        val episodeBody = getJson(
            "$API/shared/v2/movie/getListEpisodeOfMovie".toHttpUrl().newBuilder()
                .addQueryParameter("movieId", titleId)
                .build()
                .toString()
        )
        val episodes = episodeBody?.envelopeResult()?.let { el ->
            runCatching { json.decodeFromJsonElement(EpisodeListDto.serializer(), el).episodes }
                .getOrElse {
                    Log.w(TAG, "episode list parse failed", it)
                    emptyList()
                }
        } ?: emptyList()

        TitleDetails(
            providerId = id,
            id = titleId,
            title = movie.nameEnglish?.takeIf { it.isNotBlank() } ?: movie.name,
            poster = absoluteImage(movie.posterPath),
            backdrop = absoluteImage(movie.backdropPath),
            synopsis = movie.overview,
            year = movie.year ?: movie.airedFrom?.take(4)?.toIntOrNull(),
            kind = MediaKind.Anime,
            episodes = episodes.map {
                Episode(
                    id = it.id.ifBlank { it.number.toString() },
                    number = it.number,
                    title = it.name,
                    thumbnail = absoluteImage(it.stillPath),
                    runtimeSeconds = it.runtime?.times(60),
                )
            },
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            // Phase 3b will decrypt the MegaCloud embed that `link` points to
            // and return a real HLS URL. For now surface the embed so the data
            // flow is testable end-to-end.
            val url = "$API/shared/v2/episode/sources".toHttpUrl().newBuilder()
                .addQueryParameter("_movieId", titleId)
                .addQueryParameter("ep", episode.number.toString())
                .addQueryParameter("sv", "4")
                .addQueryParameter("sc", "sub")
                .build()
                .toString()
            val body = getJson(url) ?: return@withContext emptyList()
            val element = body.envelopeResult() ?: return@withContext emptyList()
            val src = runCatching { json.decodeFromJsonElement(SourcesDto.serializer(), element) }
                .getOrNull() ?: return@withContext emptyList()
            val link = src.link ?: return@withContext emptyList()
            listOf(
                StreamSource(
                    url = link,
                    kind = StreamKind.DirectEmbed,
                    serverLabel = "MegaCloud sub",
                ),
            )
        }

    private fun getJson(url: String): JsonElement? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("x-site", "anicrush")
            .header("Referer", SITE)
            .header("Origin", SITE.trimEnd('/'))
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return runCatching {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                    return@use null
                }
                val bodyString = resp.body?.string() ?: return@use null
                json.parseToJsonElement(bodyString)
            }
        }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()
    }

    private fun JsonElement.envelopeResult(): JsonElement? {
        val obj = runCatching { jsonObject }.getOrNull() ?: return null
        val ok = obj["status"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) return null
        return obj["result"]
    }

    private fun MovieDto.toSearchResult(): SearchResult {
        val movie = this
        return SearchResult(
            providerId = this@AnicrushProvider.id,
            id = movie.id,
            title = movie.nameEnglish?.takeIf { it.isNotBlank() } ?: movie.name,
            poster = absoluteImage(movie.posterPath),
            year = movie.year ?: movie.airedFrom?.take(4)?.toIntOrNull(),
            kind = MediaKind.Anime,
        )
    }

    private fun absoluteImage(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val clean = path.trimStart('/')
        return "$IMG$clean"
    }

    companion object {
        private const val TAG = "AnicrushProvider"
        private const val SITE = "https://anicrush.to/"
        private const val API = "https://api.anicrush.to"
        private const val IMG = "https://static.anicrush.to/"
    }
}
