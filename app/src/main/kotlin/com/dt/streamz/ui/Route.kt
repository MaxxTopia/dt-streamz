package com.dt.streamz.ui

import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource

sealed interface Route {
    data object Tabs : Route
    data class Details(val providerId: String, val titleId: String) : Route
    data class SourcePicker(val title: String, val sources: List<StreamSource>) : Route
    data class Player(
        val url: String,
        val title: String = "",
        val twitchChannel: String? = null,
        val kind: StreamKind = StreamKind.Hls,
    ) : Route
    data class WebPlayer(
        val embedUrl: String,
        val title: String = "",
        val headers: Map<String, String> = emptyMap(),
        /**
         * DirectEmbed mirrors to auto-try if [embedUrl] fails with a
         * transport error (ERR_CONNECTION_CLOSED, ERR_NAME_NOT_RESOLVED,
         * etc.). WebPlayerScreen walks this list silently before showing
         * the error overlay so a single dead mirror doesn't dead-end
         * playback.
         */
        val fallbacks: List<StreamSource> = emptyList(),
    ) : Route
}
