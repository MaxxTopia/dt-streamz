package com.dt.streamz.scraper.anilist

/**
 * Small, explicit filler catalog for the canonical Naruto: Shippuden route.
 * The ranges are the strict-filler classification; mixed canon/filler arcs
 * stay unmarked so the grid does not pretend that a subjective classification
 * is exact. Source: animefillerlist.com/shows/naruto-shippuden.
 */
internal object FillerCatalog {
    private const val NARUTO_SHIPPUDEN_ID = "1735"

    private val narutoShippudenStrictFiller = listOf(
        57..71,
        91..112,
        144..151,
        170..171,
        176..196,
        223..242,
        257..260,
        271..271,
        279..281,
        284..295,
        303..320,
        347..361,
        376..377,
        388..390,
        394..413,
        416..417,
        422..423,
        427..450,
        464..468,
        480..483,
    )

    fun isNarutoShippudenFiller(titleId: String, episodeNumber: Int): Boolean =
        titleId == NARUTO_SHIPPUDEN_ID &&
            narutoShippudenStrictFiller.any { episodeNumber in it }
}
