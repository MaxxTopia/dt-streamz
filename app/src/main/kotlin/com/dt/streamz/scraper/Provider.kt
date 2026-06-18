package com.dt.streamz.scraper

import com.dt.streamz.data.Episode
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.TitleDetails

interface Provider {
    val id: String
    val displayName: String
    val supportsAnime: Boolean get() = false
    val supportsMovies: Boolean get() = false
    val supportsYouTube: Boolean get() = false

    suspend fun search(query: String): List<SearchResult>

    suspend fun details(titleId: String): TitleDetails

    suspend fun streams(titleId: String, episode: Episode): List<StreamSource>

    /** Optional: latest / featured items for the Home browse rows. Default is empty. */
    suspend fun browse(): List<SearchResult> = emptyList()

    /** Optional: type-ahead query suggestions for the search box. Default none. */
    suspend fun suggest(query: String): List<String> = emptyList()

    /**
     * Optional: related/up-next title IDs for [titleId], most-relevant first.
     * Powers YouTube-style autoplay (play a related video when the current
     * one ends). Default none — providers without a related feed opt out.
     */
    suspend fun related(titleId: String): List<String> = emptyList()

    /**
     * Optional: related/up-next items for [titleId] with full metadata
     * (title + poster), most-relevant first. Powers the in-player "Up next"
     * rail. Default none — providers without a related feed opt out.
     */
    suspend fun relatedResults(titleId: String): List<SearchResult> = emptyList()

    /**
     * Optional: is [titleId] a livestream that is broadcasting RIGHT NOW?
     * Used to drop ended livestreams from recommendation rows (a result's
     * cached `isLive` flag can be stale by the time it's shown). Default
     * false — providers with no live content never surface live results, so
     * this is only meaningfully overridden by YouTube.
     */
    suspend fun isLiveNow(titleId: String): Boolean = false
}
