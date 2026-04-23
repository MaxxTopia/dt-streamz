package com.dt.streamz.scraper.anikai

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * anikai.to / animekai — primary anime source since 2026 after 9animetv
 * was nuked and gogoanime.by's embed backend died.
 *
 * - search: HTTP GET /ajax/anime/search?keyword= → {status:"ok", result.html}
 * - browse: seeded-search fallback (homepage is fully JS-rendered, no HTML
 *   scrape possible without the resolver).
 * - details: hidden-WebView renders /watch/<slug>, regex-extracts episode
 *   ?ep=<N> links from the settled DOM.
 * - streams: hidden-WebView loads /watch/<slug>?ep=<N> and captures the
 *   first .m3u8 / megacloud embed request via shouldInterceptRequest.
 *
 * Cloudflare Turnstile runs on the root domain; the WebView JS engine
 * clears it transparently (non-interactive mode). If the site swaps to
 * an interactive challenge we're dead until a user tap is available.
 */
class AnikaiProvider(
    private val resolver: AnikaiResolver? = null,
) : Provider {
    override val id = "anikai"
    override val displayName = "anikai.to"
    override val supportsAnime = true

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        // Home-page of anikai is entirely JS-rendered; no server HTML to
        // scrape. Use the search endpoint with a rotating set of popular
        // anime keywords and merge the results as a "Latest" feed. Not
        // truly "latest" but gives the tab something to show without
        // spinning up the hidden WebView on every tab open.
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<SearchResult>()
        for (seed in BROWSE_SEEDS) {
            val hits = runCatching { search(seed) }.getOrDefault(emptyList())
            for (h in hits) {
                if (seen.add(h.id)) merged.add(h)
                if (merged.size >= 24) return@withContext merged
            }
        }
        merged
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "$SITE/ajax/anime/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .build()

        val body = getJson(url.toString()) ?: return@withContext emptyList()
        val obj = runCatching { body.jsonObject }.getOrNull() ?: return@withContext emptyList()
        val ok = obj["status"]?.jsonPrimitive?.content == "ok"
        if (!ok) return@withContext emptyList()
        val html = obj["result"]?.jsonObject?.get("html")?.jsonPrimitive?.content
            ?: return@withContext emptyList()
        parseSearchHtml(html)
    }

    override suspend fun details(titleId: String): TitleDetails {
        val stub = searchCache[titleId]
        val shell = TitleDetails(
            providerId = id,
            id = titleId,
            title = stub?.title ?: titleId,
            poster = stub?.poster,
            backdrop = stub?.poster,
            synopsis = null,
            year = stub?.year,
            kind = MediaKind.Anime,
            episodes = emptyList(),
        )
        val r = resolver ?: return shell.copy(
            synopsis = "anikai.to resolver not wired — episodes unavailable.",
        )
        val watchUrl = "$SITE/watch/$titleId"
        val html = r.renderHtml(watchUrl)
            ?: return shell.copy(synopsis = "anikai.to render timed out — try again.")
        val episodes = EP_LINK.findAll(html)
            .mapNotNull { m ->
                val number = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Episode(
                    id = m.groupValues[1],
                    number = number,
                    title = null,
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }
            .toList()
        return shell.copy(
            episodes = episodes,
            synopsis = if (episodes.isEmpty())
                "No episode list found in the rendered page — the site's JS may have changed."
            else null,
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> {
        val r = resolver ?: return emptyList()
        // If the episode id is already a relative path like /watch/<slug>?ep=N,
        // load it directly; otherwise build a URL from the titleId + episode number.
        val watchUrl = when {
            episode.id.startsWith("http") -> episode.id
            episode.id.startsWith("/") -> "$SITE${episode.id}"
            else -> "$SITE/watch/$titleId?ep=${episode.number}"
        }
        val streamUrl = r.captureStreamUrl(watchUrl) ?: return emptyList()
        val kind = if (streamUrl.contains(".m3u8", ignoreCase = true)) StreamKind.Hls
        else StreamKind.DirectEmbed
        return listOf(
            StreamSource(
                url = streamUrl,
                kind = kind,
                serverLabel = "anikai",
            ),
        )
    }

    private fun getJson(url: String) = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("x-requested-with", "XMLHttpRequest")
            .header("Referer", "$SITE/browser")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                return@use null
            }
            val body = resp.body?.string() ?: return@use null
            Json.parseToJsonElement(body)
        }
    }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()

    private fun parseSearchHtml(html: String): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val entries = AITEM_BLOCK.findAll(html)
        for (m in entries) {
            val href = m.groupValues[1]
            val slug = href.removePrefix("/watch/").substringBefore('?')
            val block = m.groupValues[0]
            val poster = IMG_SRC.find(block)?.groupValues?.get(1)
            val title = H_TITLE.find(block)?.groupValues?.get(1)?.let(::decodeEntities) ?: continue
            val year = YEAR.findAll(block).map { it.value.toIntOrNull() }.firstOrNull { it != null && it in 1900..2099 }
            val hasDub = DUB_SPAN.containsMatchIn(block)
            val result = SearchResult(
                providerId = id,
                id = slug,
                title = if (hasDub) "$title (sub+dub)" else title,
                poster = poster,
                year = year,
                kind = MediaKind.Anime,
            )
            searchCache[slug] = result
            out.add(result)
        }
        return out
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")

    companion object {
        private const val TAG = "AnikaiProvider"
        private const val SITE = "https://anikai.to"

        private val BROWSE_SEEDS = listOf(
            "naruto", "one piece", "attack on titan", "demon slayer",
            "jujutsu kaisen", "chainsaw man", "frieren", "solo leveling",
        )

        private val AITEM_BLOCK = Regex(
            pattern = """<a class="aitem"\s+href="(/watch/[^"]+)">([\s\S]*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val IMG_SRC = Regex("""<img[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val H_TITLE = Regex(
            """<h\d+\s+class="title"[^>]*>([^<]+)</h\d+>""",
            RegexOption.IGNORE_CASE,
        )
        private val DUB_SPAN = Regex("""class="dub"""", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("""(?<![0-9])(19|20)\d{2}(?![0-9])""")
        private val EP_LINK = Regex(
            """href=["'](/watch/[^"'?]+\?ep=(\d+))["']""",
            RegexOption.IGNORE_CASE,
        )

        private val searchCache = mutableMapOf<String, SearchResult>()
    }
}
