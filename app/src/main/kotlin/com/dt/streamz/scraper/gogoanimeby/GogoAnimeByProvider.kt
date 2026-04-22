package com.dt.streamz.scraper.gogoanimeby

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
import okhttp3.Request

/**
 * gogoanime.by scraper. WordPress-based anime site with server-rendered
 * HTML and zero CAPTCHA / fingerprint walls as of 2026-04-23.
 *
 * - Search:   GET /?s=<kw>                   -> parse <article class="bs">
 * - Details:  GET /series/<slug>/            -> parse episode list + metadata
 * - Streams:  GET /<slug>-episode-N-english-subbed/
 *             -> pull data-plain-url from <li class="player-type-link">
 *
 * Sub/dub marker on cards drives a label suffix in SearchResult.title.
 */
class GogoAnimeByProvider : Provider {

    override val id = "gogoanimeby"
    override val displayName = "gogoanime.by"
    override val supportsAnime = true

    private val cache = mutableMapOf<String, CachedSearchResult>()

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val body = fetch("$SITE/?s=${query.encode()}") ?: return@withContext emptyList()
        CARD.findAll(body).mapNotNull { m ->
            val href = m.groupValues[1]
            val title = m.groupValues[2].decodeEntities()
            if (!href.contains("/series/")) return@mapNotNull null
            val slug = href.substringAfter("/series/").trimEnd('/')
            val block = m.groupValues[0]
            val poster = IMG_SRC.find(block)?.groupValues?.get(1)
            val typez = TYPEZ.find(block)?.groupValues?.get(1)?.trim()
            val hasDub = DUB_BADGE.containsMatchIn(block)
            val kind = when {
                typez.equals("Movie", true) -> MediaKind.Movie
                else -> MediaKind.Anime
            }
            val result = SearchResult(
                providerId = id,
                id = slug,
                title = if (hasDub) "$title (sub+dub)" else title,
                poster = poster,
                year = null,
                kind = kind,
            )
            cache[slug] = CachedSearchResult(result, baseSlug = slug)
            result
        }.toList()
    }

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        val url = "$SITE/series/$titleId/"
        val body = fetch(url) ?: error("gogoanime.by: $titleId not reachable")

        val title = H1_TITLE.find(body)?.groupValues?.get(1)?.decodeEntities()?.trim()
            ?: cache[titleId]?.result?.title
            ?: titleId
        val poster = OG_IMAGE.find(body)?.groupValues?.get(1)
            ?: cache[titleId]?.result?.poster
        val synopsis = SYNOPSIS.find(body)?.groupValues?.get(1)
            ?.let { stripTags(it) }
            ?.trim()
            ?.takeIf { it.length > 20 }

        val episodeRe = Regex(
            """href=["'](https?://gogoanime\.by/${Regex.escape(titleId)}-episode-(\d+)-english-subbed/?)["']""",
            RegexOption.IGNORE_CASE,
        )
        val episodes = episodeRe.findAll(body)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .distinctBy { it.second }
            .sortedBy { it.second }
            .map { (epUrl, number) ->
                Episode(id = epUrl, number = number, title = null)
            }
            .toList()

        TitleDetails(
            providerId = id,
            id = titleId,
            title = title,
            poster = poster,
            backdrop = poster,
            synopsis = synopsis,
            year = null,
            kind = cache[titleId]?.result?.kind ?: MediaKind.Anime,
            episodes = episodes,
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val epUrl = if (episode.id.startsWith("http"))
                episode.id
            else
                "$SITE/$titleId-episode-${episode.number}-english-subbed/"

            val body = fetch(epUrl) ?: return@withContext emptyList()
            val sources = mutableListOf<StreamSource>()

            PLAYER_LI.findAll(body).forEach { li ->
                val block = li.value
                val label = li.groupValues.getOrNull(1)?.trim().orEmpty()
                val plain = DATA_PLAIN_URL.find(block)?.groupValues?.get(1)
                if (!plain.isNullOrBlank() && plain.startsWith("http")) {
                    val serverLabel = listOfNotNull(
                        label.ifBlank { null },
                        plain.substringAfter("//").substringBefore("/"),
                    ).joinToString(" · ")
                    sources.add(
                        StreamSource(
                            url = plain,
                            kind = StreamKind.DirectEmbed,
                            serverLabel = serverLabel,
                        ),
                    )
                }
            }
            sources
        }

    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun String.decodeEntities(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun stripTags(s: String): String = Regex("<[^>]+>").replace(s, " ")

    private data class CachedSearchResult(
        val result: SearchResult,
        val baseSlug: String,
    )

    companion object {
        private const val TAG = "GogoAnimeByProvider"
        private const val SITE = "https://gogoanime.by"

        private val CARD = Regex(
            """<article[^>]*class=["']bs["'][^>]*>[\s\S]*?<a[^>]*href=["']([^"']+)["'][^>]*title=["']([^"']+)["'][\s\S]*?</article>""",
            RegexOption.IGNORE_CASE,
        )
        private val IMG_SRC = Regex("""<img[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TYPEZ = Regex("""<div[^>]*class=["']typez\s+([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DUB_BADGE = Regex("""class=["']sb\s+Dub["']""", RegexOption.IGNORE_CASE)
        private val H1_TITLE = Regex(
            """<h1[^>]*itemprop=["']name["'][^>]*>([^<]+)</h1>""",
            RegexOption.IGNORE_CASE,
        )
        private val OG_IMAGE = Regex(
            """<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val SYNOPSIS = Regex(
            """<div[^>]*itemprop=["']description["'][^>]*>([\s\S]*?)</div>""",
            RegexOption.IGNORE_CASE,
        )
        private val PLAYER_LI = Regex(
            """<li[^>]*class=["']player-type-link[^"']*["'][\s\S]*?>\s*([^<]+?)\s*</li>""",
            RegexOption.IGNORE_CASE,
        )
        private val DATA_PLAIN_URL = Regex(
            """data-plain-url=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
    }
}
