package com.dt.streamz.scraper.anikai

import android.util.Log
import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
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
 * anikai.to (formerly animekai.to) provider.
 *
 * Only the suggestion endpoint `/ajax/anime/search` is reliably scrapeable —
 * it returns `{status:"ok", result:{html: "..."}}` with a clean snippet of
 * `<a class="aitem" href="/watch/SLUG">...` entries that include title,
 * poster, year, and sub/dub episode counts.
 *
 * Episode lists and stream sources are loaded via heavily obfuscated client-side
 * JS that we haven't reversed yet — details() returns a shell with an empty
 * episode list, and streams() is a no-op. Search alone is still useful when
 * anicrush is down (listings visible, user can dig up the slug).
 */
class AnikaiProvider : Provider {
    override val id = "anikai"
    override val displayName = "anikai.to"
    override val supportsAnime = true

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

    override suspend fun details(titleId: String): TitleDetails = withContext(Dispatchers.IO) {
        // Episode list lives behind obfuscated JS. Return the search-derived
        // shell so the UI can at least show the title; tell the user that
        // full details need browser rendering.
        val stub = searchCache[titleId]
        TitleDetails(
            providerId = id,
            id = titleId,
            title = stub?.title ?: titleId,
            poster = stub?.poster,
            backdrop = stub?.poster,
            synopsis = "anikai.to episode list requires in-app WebView rendering — not wired yet.",
            year = stub?.year,
            kind = MediaKind.Anime,
            episodes = emptyList(),
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> = emptyList()

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

        private val searchCache = mutableMapOf<String, SearchResult>()
    }
}
