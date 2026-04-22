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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * gogoanime.by scraper.
 *
 * Search still server-rendered (`<article class="bs">`).
 *
 * Episode list moved client-side in 2026 — series pages no longer contain
 * `.eplister`. WP REST is still open, so we list episodes by looking up the
 * series category (slug → id via `/wp-json/wp/v2/categories`) and paging
 * `/wp-json/wp/v2/posts?categories=<id>&per_page=100`.
 *
 * Stream extraction also changed: `data-plain-url` was replaced with
 * `data-type` + `data-encrypted-url1/2/3` (base64 of an AES blob) plus a
 * sibling player router at 9animetv.be that decrypts server-side. We
 * reconstruct the exact URL the site's jQuery builds and hand it to the
 * existing DirectEmbed WebView path.
 */
class GogoAnimeByProvider : Provider {

    override val id = "gogoanimeby"
    override val displayName = "gogoanime.by"
    override val supportsAnime = true

    private val cache = mutableMapOf<String, CachedSearchResult>()

    override suspend fun browse(): List<SearchResult> = withContext(Dispatchers.IO) {
        val body = fetch("$SITE/series/") ?: return@withContext emptyList()
        parseCards(body).take(24)
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val body = fetch("$SITE/?s=${query.encode()}") ?: return@withContext emptyList()
        parseCards(body)
    }

    private fun parseCards(body: String): List<SearchResult> =
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
            cache[slug] = CachedSearchResult(result)
            result
        }.toList()

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

        val episodes = fetchEpisodesViaRest(titleId)

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

    /**
     * Look up the WP category whose slug matches the series slug, then page
     * `/wp-json/wp/v2/posts?categories=<id>` to collect every episode post.
     * Slug suffix `-english-subbed` vs `-english-dubbed` picks the variant;
     * sub wins when both exist for the same episode number.
     *
     * Falls back to an empty list on any miss so the caller can still show
     * the details page (user can retry, open in browser, etc.).
     */
    private suspend fun fetchEpisodesViaRest(titleId: String): List<Episode> = coroutineScope {
        val catUrl = "$SITE/wp-json/wp/v2/categories".toHttpUrl().newBuilder()
            .addQueryParameter("slug", titleId)
            .addQueryParameter("_fields", "id,count")
            .build()
            .toString()
        val catBody = fetch(catUrl) ?: return@coroutineScope emptyList()
        val catId = runCatching {
            (Http.json.parseToJsonElement(catBody) as JsonArray)
                .firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull
                ?.toIntOrNull()
        }.getOrNull() ?: return@coroutineScope emptyList()

        val (firstBody, totalPages) = fetchPostsPage(catId, 1) ?: return@coroutineScope emptyList()
        val slugs = mutableListOf<String>()
        slugs += parsePostSlugs(firstBody)

        if (totalPages > 1) {
            val rest = (2..totalPages.coerceAtMost(MAX_PAGES)).map { page ->
                async { fetchPostsPage(catId, page)?.first?.let(::parsePostSlugs).orEmpty() }
            }.awaitAll()
            rest.forEach(slugs::addAll)
        }

        val bySubbed = mutableMapOf<Int, String>()
        val byDubbed = mutableMapOf<Int, String>()
        for (slug in slugs) {
            val m = EPISODE_SLUG.find(slug) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            when (m.groupValues[2].lowercase()) {
                "subbed" -> bySubbed.putIfAbsent(n, slug)
                "dubbed" -> byDubbed.putIfAbsent(n, slug)
            }
        }

        val numbers = (bySubbed.keys + byDubbed.keys).toSortedSet()
        numbers.map { n ->
            val slug = bySubbed[n] ?: byDubbed.getValue(n)
            Episode(id = "$SITE/$slug/", number = n, title = null)
        }
    }

    private data class PageResult(val first: String, val second: Int)

    private fun fetchPostsPage(categoryId: Int, page: Int): PageResult? {
        val url = "$SITE/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("categories", categoryId.toString())
            .addQueryParameter("per_page", "100")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("_fields", "id,slug")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "REST posts page=$page -> HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                val total = resp.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
                PageResult(body, total)
            }
        }.onFailure { Log.w(TAG, "REST posts page=$page failed", it) }.getOrNull()
    }

    private fun parsePostSlugs(body: String): List<String> {
        val arr = runCatching { Http.json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull {
            (it as? JsonObject)?.get("slug")?.jsonPrimitive?.contentOrNull
        }
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val epUrl = when {
                episode.id.startsWith("http") -> episode.id
                else -> "$SITE/$titleId-episode-${episode.number}-english-subbed/"
            }

            val body = fetch(epUrl) ?: return@withContext emptyList()
            val defaults = extractPlayerDefaults(body)

            PLAYER_LI_BLOCK.findAll(body).mapNotNull { m ->
                val block = m.value
                val type = DATA_TYPE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                if (type.isBlank()) return@mapNotNull null

                val label = LABEL.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                val plain = DATA_PLAIN_URL.find(block)?.groupValues?.get(1)

                val src = if (type in DIRECT_TYPES && !plain.isNullOrBlank() && plain.startsWith("http")) {
                    plain
                } else {
                    val enc1 = DATA_ENC1.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                    val enc2 = DATA_ENC2.find(block)?.groupValues?.get(1).orEmpty()
                    val enc3 = DATA_ENC3.find(block)?.groupValues?.get(1).orEmpty()
                    val subtitle = DATA_SUBTITLE.find(block)?.groupValues?.get(1).orEmpty()
                    val key = DATA_KEY.find(block)?.groupValues?.get(1).orEmpty()
                    buildNineAnimeUrl(
                        type = type,
                        enc1 = enc1,
                        enc2 = enc2,
                        enc3 = enc3,
                        featureImage = defaults.featureImage,
                        subtitle = subtitle,
                        key = key,
                        postId = defaults.postId,
                    ) ?: return@mapNotNull null
                }

                val serverLabel = listOfNotNull(
                    label.ifBlank { null },
                    type.takeIf { it != label },
                    src.substringAfter("//").substringBefore("/"),
                ).joinToString(" · ")

                StreamSource(
                    url = src,
                    kind = StreamKind.DirectEmbed,
                    serverLabel = serverLabel,
                )
            }.toList()
        }

    private fun buildNineAnimeUrl(
        type: String,
        enc1: String,
        enc2: String,
        enc3: String,
        featureImage: String,
        subtitle: String,
        key: String,
        postId: String,
    ): String? {
        if (enc1.isBlank()) return null
        // Order matches the site's jQuery $.param() call so behavior is identical.
        val builder = PLAYER_PHP.toHttpUrl().newBuilder()
            .addQueryParameter(type, enc1)
        if (enc2.isNotBlank()) builder.addQueryParameter("url2", enc2)
        if (enc3.isNotBlank()) builder.addQueryParameter("url3", enc3)
        builder.addQueryParameter("feature_image", featureImage)
            .addQueryParameter("user_agent", PLAYER_UA)
            .addQueryParameter("ref", "gogoanime.by")
        if (subtitle.isNotBlank()) builder.addQueryParameter("subtitle", subtitle)
        if (key.isNotBlank()) builder.addQueryParameter("key", key)
        builder.addQueryParameter("postId", postId)
        return builder.build().toString()
    }

    private data class PlayerDefaults(val featureImage: String, val postId: String)

    private fun extractPlayerDefaults(body: String): PlayerDefaults = PlayerDefaults(
        featureImage = DEFAULT_FEATURE_IMAGE.find(body)?.groupValues?.get(1).orEmpty(),
        postId = DEFAULT_POST_ID.find(body)?.groupValues?.get(1).orEmpty(),
    )

    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json")
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

    private fun String.decodeEntities(): String {
        val numeric = Regex("""&#(x?[0-9a-fA-F]+);""").replace(this) { m ->
            val raw = m.groupValues[1]
            val codePoint = runCatching {
                if (raw.startsWith("x", ignoreCase = true)) raw.substring(1).toInt(16)
                else raw.toInt()
            }.getOrNull() ?: return@replace m.value
            if (codePoint in 0..0x10FFFF) String(Character.toChars(codePoint)) else m.value
        }
        return numeric
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }

    private fun stripTags(s: String): String = Regex("<[^>]+>").replace(s, " ")

    private data class CachedSearchResult(val result: SearchResult)

    companion object {
        private const val TAG = "GogoAnimeByProvider"
        private const val SITE = "https://gogoanime.by"
        private const val PLAYER_PHP =
            "https://9animetv.be/wp-content/plugins/video-player/includes/player/player.php"

        // The site's jQuery hardcodes this UA when calling player.php; the PHP
        // probably inspects it to pick a decryption path. Don't substitute our
        // own UA or the upstream player may return a blank iframe.
        private const val PLAYER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val DIRECT_TYPES = setOf("embed", "kiwi")
        private const val MAX_PAGES = 20

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

        private val PLAYER_LI_BLOCK = Regex(
            """<li[^>]*class=["']player-type-link[^"']*["'][\s\S]*?</li>""",
            RegexOption.IGNORE_CASE,
        )
        private val LABEL = Regex(""">\s*([A-Za-z0-9][^<]*?)\s*</li>""", RegexOption.IGNORE_CASE)
        private val DATA_TYPE = Regex("""data-type=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DATA_ENC1 = Regex("""data-encrypted-url1=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DATA_ENC2 = Regex("""data-encrypted-url2=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DATA_ENC3 = Regex("""data-encrypted-url3=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DATA_SUBTITLE = Regex("""data-subtitle=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        private val DATA_KEY = Regex("""data-key=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        private val DATA_PLAIN_URL = Regex("""data-plain-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        private val DEFAULT_POST_ID = Regex(
            """defaultPostId\s*=\s*["'](\d+)["']""",
        )
        private val DEFAULT_FEATURE_IMAGE = Regex(
            """defaultFeatureImage\s*=\s*["']([^"']+)["']""",
        )

        private val EPISODE_SLUG = Regex(
            """-episode-(\d+)-english-(subbed|dubbed)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
