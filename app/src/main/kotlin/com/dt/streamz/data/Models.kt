package com.dt.streamz.data

enum class MediaKind { Anime, Movie, Series }

data class SearchResult(
    val providerId: String,
    val id: String,
    val title: String,
    val poster: String?,
    val year: Int? = null,
    val kind: MediaKind,
)

data class TitleDetails(
    val providerId: String,
    val id: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val synopsis: String?,
    val year: Int?,
    val kind: MediaKind,
    val episodes: List<Episode>,
)

data class Episode(
    val id: String,
    val number: Int,
    val title: String?,
    val thumbnail: String? = null,
    val runtimeSeconds: Int? = null,
)

data class StreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val kind: StreamKind,
    val quality: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val serverLabel: String? = null,
)

enum class StreamKind { Hls, Mp4, DirectEmbed }

data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String = language,
)
