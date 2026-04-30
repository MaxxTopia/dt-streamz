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
}
