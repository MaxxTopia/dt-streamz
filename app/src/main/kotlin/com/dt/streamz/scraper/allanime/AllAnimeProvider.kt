package com.dt.streamz.scraper.allanime

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

/**
 * Anime via the AllAnime API — the same backend `ani-cli` uses. Replaces
 * the animekai/anikai WebView resolver, which the box's logs showed could
 * never clear AllAnime/animekai's Cloudflare-JS challenge (render returned
 * null). AllAnime is a plain JSON/GraphQL API: no browser, no challenge,
 * and it hands back direct m3u8/mp4 links — so anime plays in ExoPlayer
 * with proper D-pad controls instead of a webview embed.
 *
 * Flow (all plain OkHttp):
 *   search   -> shows{ _id, name, availableEpisodes }
 *   details  -> show.availableEpisodesDetail.sub  (episode-number strings)
 *   streams  -> episode.sourceUrls[] -> decode the "--<hex>" clock url
 *               (XOR 0x38) -> GET allanime.day/<path>.json -> links[].link
 *
 * Requires Referer+Origin = youtu-chan.com and a desktop Firefox UA, same
 * as ani-cli. If AllAnime later Cloudflare-gates the box's IP too, every
 * call returns empty/null and the Anime tab degrades gracefully — the
 * DebugLog lines below will say exactly where it stopped.
 */
class AllAnimeProvider : Provider {
    override val id = "allanime"
    override val displayName = "AllAnime"
    override val supportsAnime = true

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResult>()
        for (seed in BROWSE_SEEDS) {
            for (r in search(seed)) {
                if (seen.add(r.id)) out.add(r)
                if (out.size >= 24) return@withContext out
            }
        }
        out
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val variables = """{"search":{"allowAdult":false,"allowUnknown":false,"query":${query.q()}},""" +
            """"limit":26,"page":1,"translationType":"$MODE","countryOrigin":"ALL"}"""
        val body = apiGet(variables, SEARCH_GQL) ?: run {
            DebugLog.w(TAG, "search($query) null (CF challenge / network / dead)")
            return@withContext emptyList()
        }
        val edges = body.path("data", "shows")?.get("edges") as? JsonArray ?: return@withContext emptyList()
        val results = edges.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val sid = o["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            titleCache[sid] = name
            SearchResult(
                providerId = id, id = sid, title = name,
                poster = null, year = null, kind = MediaKind.Anime,
            )
        }
        DebugLog.i(TAG, "search($query) -> ${results.size} shows")
        results
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val name = titleCache[titleId] ?: titleId
        val body = apiGet("""{"showId":${titleId.q()}}""", EPISODES_GQL)
        val detail = body?.path("data", "show")?.get("availableEpisodesDetail") as? JsonObject
        val subs = (detail?.get(MODE) as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            // episode strings can be "1","2","12.5"; sort numerically.
            ?.sortedBy { it.toDoubleOrNull() ?: 0.0 }
            .orEmpty()
        DebugLog.i(TAG, "details($titleId) -> ${subs.size} episodes")
        val episodes = subs.mapIndexed { idx, epStr ->
            Episode(id = "ep:$epStr", number = idx + 1, title = "Episode $epStr")
        }
        TitleDetails(
            providerId = id, id = titleId, title = name, poster = null, backdrop = null,
            synopsis = if (episodes.isEmpty()) "No episodes found (AllAnime may be blocking this network)." else null,
            year = null, kind = MediaKind.Anime, episodes = episodes,
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> = withContext(Dispatchers.IO) {
        val epStr = episode.id.removePrefix("ep:").ifBlank { episode.number.toString() }
        val variables = """{"showId":${titleId.q()},"translationType":"$MODE","episodeString":${epStr.q()}}"""
        DebugLog.i(TAG, "streams($titleId ep=$epStr) fetching sourceUrls")
        val body = apiGet(variables, SOURCES_GQL, hash = SOURCES_HASH) ?: run {
            DebugLog.w(TAG, "streams: sourceUrls null (CF / network)")
            return@withContext emptyList()
        }
        val sourceUrls = body.path("data", "episode")?.get("sourceUrls") as? JsonArray ?: return@withContext emptyList()
        val out = mutableListOf<StreamSource>()
        for (el in sourceUrls) {
            val o = el as? JsonObject ?: continue
            val raw = o["sourceUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = o["sourceName"]?.jsonPrimitive?.contentOrNull ?: "src"
            val clockPath = decodeClock(raw) ?: continue
            val links = fetchClockLinks(clockPath)
            for ((url, q) in links) {
                out.add(
                    StreamSource(
                        url = url,
                        kind = if (url.contains(".m3u8", true)) StreamKind.Hls else StreamKind.Mp4,
                        quality = q,
                        serverLabel = "AllAnime · $name${q?.let { " $it" } ?: ""}",
                    ),
                )
            }
            if (out.size >= 6) break
        }
        DebugLog.i(TAG, "streams($titleId ep=$epStr) -> ${out.size} playable links")
        if (out.isEmpty()) DebugLog.w(TAG, "streams: 0 links (clock decode / provider markup changed)")
        out
    }

    // --- clock decode + resolve ---

    /**
     * AllAnime internal sourceUrls look like `--7a7b08...` (hex). Strip the
     * `--`, XOR each byte with 0x38 (verified against ani-cli's table) to get
     * a path like `/apivtwo/clock?id=...`, then point it at the `.json` API.
     * Non-`--` urls (already-direct embeds) are skipped.
     */
    private fun decodeClock(raw: String): String? {
        if (!raw.startsWith("--")) return null
        val hex = raw.removePrefix("--")
        if (hex.length % 2 != 0) return null
        val sb = StringBuilder(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: return null
            sb.append((b xor 0x38).toChar())
            i += 2
        }
        val path = sb.toString()
        if (!path.contains("/clock")) return null
        return path.replace("/clock?", "/clock.json?")
    }

    /** GET the decoded clock.json and pull out playable links. */
    private fun fetchClockLinks(path: String): List<Pair<String, String?>> {
        val url = "https://$CLOCK_HOST$path"
        val body = rawGet(url) ?: return emptyList()
        val obj = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val links = obj["links"] as? JsonArray ?: return emptyList()
        return links.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val link = o["link"]?.jsonPrimitive?.contentOrNull
                ?: o["hls"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val q = o["resolutionStr"]?.jsonPrimitive?.contentOrNull
            link to q
        }
    }

    // --- HTTP ---

    /** API call: GET with urlencoded variables + (inline query OR persisted hash). */
    private fun apiGet(variables: String, gql: String?, hash: String? = null): JsonObject? {
        val sb = StringBuilder("$API?variables=").append(variables.enc())
        if (hash != null) {
            sb.append("&extensions=")
                .append("""{"persistedQuery":{"version":1,"sha256Hash":"$hash"}}""".enc())
        } else if (gql != null) {
            sb.append("&query=").append(gql.enc())
        }
        val body = rawGet(sb.toString()) ?: return null
        return runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private fun rawGet(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", AGENT)
            .header("Referer", REFERER)
            .header("Origin", REFERER)
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.d(TAG, "${url.take(70)} -> HTTP ${resp.code}")
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure { DebugLog.d(TAG, "GET failed: ${it.message}") }.getOrNull()

    private fun String.q(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")
    private fun JsonObject.path(vararg keys: String): JsonObject? {
        var cur: JsonObject? = this
        for (k in keys) cur = cur?.get(k) as? JsonObject
        return cur
    }

    companion object {
        private const val TAG = "AllAnime"
        private const val API = "https://api.allanime.day/api"
        private const val CLOCK_HOST = "allanime.day"
        private const val REFERER = "https://youtu-chan.com"
        private const val AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"
        // "sub" = subtitled (most coverage). Dub support can be added later.
        private const val MODE = "sub"
        private const val SOURCES_HASH =
            "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"

        private val BROWSE_SEEDS = listOf(
            "one piece", "naruto", "demon slayer", "jujutsu kaisen",
            "attack on titan", "solo leveling",
        )

        private const val SEARCH_GQL =
            "query( \$search: SearchInput \$limit: Int \$page: Int \$translationType: VaildTranslationTypeEnumType \$countryOrigin: VaildCountryOriginEnumType ) { shows( search: \$search limit: \$limit page: \$page translationType: \$translationType countryOrigin: \$countryOrigin ) { edges { _id name availableEpisodes __typename } }}"
        private const val EPISODES_GQL =
            "query (\$showId: String!) { show( _id: \$showId ) { _id availableEpisodesDetail }}"
        private const val SOURCES_GQL =
            "query (\$showId: String!, \$translationType: VaildTranslationTypeEnumType!, \$episodeString: String!) { episode( showId: \$showId translationType: \$translationType episodeString: \$episodeString ) { episodeString sourceUrls }}"

        private val titleCache = mutableMapOf<String, String>()
    }
}
