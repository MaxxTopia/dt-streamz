package com.dt.streamz.scraper.fixtures

import com.dt.streamz.data.Episode
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.Provider

/**
 * Offline, seeded provider. Useful while a real scraper's upstream is down
 * or during UI iteration. Returns a stable, matchable-by-substring catalog
 * with playable Mux test HLS streams so the full search -> details -> play
 * flow can be exercised without any network dependency on third-party sites.
 */
class FixturesProvider : Provider {
    override val id = "fixtures"
    override val displayName = "Fixtures"
    override val supportsAnime = true
    override val supportsMovies = true

    override suspend fun search(query: String): List<SearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return catalog.filter {
            q in it.title.lowercase() || q in it.id.lowercase()
        }.map {
            SearchResult(
                providerId = id,
                id = it.id,
                title = it.title,
                poster = it.poster,
                year = it.year,
                kind = it.kind,
            )
        }
    }

    override suspend fun details(titleId: String): TitleDetails {
        val t = catalog.firstOrNull { it.id == titleId } ?: error("fixtures: unknown id=$titleId")
        return TitleDetails(
            providerId = id,
            id = t.id,
            title = t.title,
            poster = t.poster,
            backdrop = t.poster,
            synopsis = t.synopsis,
            year = t.year,
            kind = t.kind,
            episodes = List(t.episodeCount) { i ->
                Episode(id = "${t.id}-ep${i + 1}", number = i + 1, title = null)
            },
        )
    }

    override suspend fun streams(titleId: String, episode: Episode): List<StreamSource> =
        listOf(
            StreamSource(
                url = TEST_HLS,
                kind = StreamKind.Hls,
                quality = "1080p",
                serverLabel = "Mux test",
            ),
        )

    private data class FixtureTitle(
        val id: String,
        val title: String,
        val year: Int,
        val kind: MediaKind,
        val poster: String?,
        val synopsis: String,
        val episodeCount: Int,
    )

    companion object {
        private const val TEST_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        private val catalog = listOf(
            FixtureTitle(
                id = "fixture-bipbop",
                title = "Mux BipBop (test HLS)",
                year = 2024,
                kind = MediaKind.Movie,
                poster = null,
                synopsis = "Reference HLS stream courtesy of Mux. Use it to sanity-check the player + UI wiring.",
                episodeCount = 1,
            ),
            FixtureTitle(
                id = "fixture-frieren",
                title = "Frieren: Beyond Journey's End (fixture)",
                year = 2023,
                kind = MediaKind.Anime,
                poster = null,
                synopsis = "Offline placeholder entry so the details screen renders during dev when anicrush is unreachable.",
                episodeCount = 28,
            ),
            FixtureTitle(
                id = "fixture-dune",
                title = "Dune: Part Two (fixture)",
                year = 2024,
                kind = MediaKind.Movie,
                poster = null,
                synopsis = "Offline placeholder entry so the details screen renders during dev when fmovies is unreachable.",
                episodeCount = 1,
            ),
        )
    }
}
