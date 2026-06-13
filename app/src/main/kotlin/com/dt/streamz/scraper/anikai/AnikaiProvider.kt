package com.dt.streamz.scraper.anikai

import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // Preferred: the site's real "recently updated" feed. The homepage is
        // JS-rendered + Cloudflare-gated so a static GET can't read it — render
        // it in the hidden WebView and reuse the same .aitem parser the search
        // endpoint uses. BrowseCache memoizes this for 5 min, so the WebView
        // only spins up once per window, not on every tab open.
        resolver?.let { r ->
            val html = runCatching { r.renderHtml("$SITE/home") }.getOrNull()
            if (html != null) {
                val recent = parseSearchHtml(html).take(24)
                if (recent.isNotEmpty()) return@withContext recent
            }
        }
        // Fallback (no resolver, render failed, or empty): seed-search a
        // couple popular keywords. Capped tight because search() now spins
        // up the hidden WebView per call (the ajax endpoint is challenge-
        // gated), so we can't afford to walk all 8 seeds serially here.
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<SearchResult>()
        for (seed in BROWSE_SEEDS.take(2)) {
            val hits = runCatching { search(seed) }.getOrDefault(emptyList())
            for (h in hits) {
                if (seen.add(h.id)) merged.add(h)
                if (merged.size >= 24) return@withContext merged
            }
        }
        merged
    }

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        // The /ajax/anime/search endpoint is now gated by a JS/JWT anti-bot
        // challenge: a raw HTTP GET gets back a `window.location.replace(...)`
        // bootstrap (with a signed `js=` token + `sid`), NOT the JSON it used
        // to return. A plain OkHttp call can't execute that, so we render the
        // /browser results page in the hidden WebView — which clears the
        // challenge transparently — and parse the same `.aitem` markup the
        // ajax HTML used to carry.
        //
        // Bounded by SEARCH_RENDER_BUDGET_MS so a slow/blocked render can't
        // stall the unified Search tab (which awaits every provider together).
        val r = resolver ?: return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$SITE/browser?keyword=$encoded"
        val html = kotlinx.coroutines.withTimeoutOrNull(SEARCH_RENDER_BUDGET_MS) {
            r.renderHtml(url, settleMs = 2_500L)
        } ?: return@withContext emptyList()
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

        val out = mutableListOf<StreamSource>()
        // Sub (default audio) — same single capture as before.
        val subUrl = r.captureStreamUrl(watchUrl, dub = false)
        subUrl?.let { out.add(toSource(it, "anikai · Sub")) }

        // Dub — only attempt the extra capture for titles the listing tagged
        // as dubbed (Aniwave-style audio-type switch). If it resolves to a
        // different stream than sub, offer it as a second pick. If it fails,
        // the user simply gets Sub — no regression.
        if (titleId in dubSlugs) {
            val dubUrl = r.captureStreamUrl(watchUrl, dub = true)
            if (dubUrl != null && dubUrl != subUrl) out.add(toSource(dubUrl, "anikai · Dub"))
        }
        return out
    }

    private fun toSource(streamUrl: String, label: String): StreamSource {
        val kind = if (streamUrl.contains(".m3u8", ignoreCase = true)) StreamKind.Hls
        else StreamKind.DirectEmbed
        return StreamSource(
            url = streamUrl,
            kind = kind,
            serverLabel = label,
            // megacloud / megaup embeds reject empty or wrong referers. For
            // HLS we don't strictly need this (ExoPlayer builds its own
            // request), but for DirectEmbed the WebView passes it through.
            headers = mapOf("Referer" to "$SITE/"),
        )
    }

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
            if (hasDub) dubSlugs.add(slug)
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
        // anikai.to went fully dead (DNS NXDOMAIN) in 2026; animekai.bz is
        // the live successor for the same AnimeKai backend/markup.
        private const val SITE = "https://animekai.bz"

        // Upper bound on the hidden-WebView render for search() so a slow or
        // challenge-stuck load can't gate the unified Search tab.
        private const val SEARCH_RENDER_BUDGET_MS = 10_000L

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

        // Slugs the listing flagged with a dub badge (class="dub"). streams()
        // only spends the extra dub capture on these.
        private val dubSlugs = mutableSetOf<String>()
    }
}
