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
    ) : Route
}
