package com.dt.streamz.data

enum class MediaKind { Anime, Movie, Series }

data class SearchResult(
    val providerId: String,
    val id: String,
    val title: String,
    val poster: String?,
    val year: Int? = null,
    val kind: MediaKind,
    /** True for an in-progress live broadcast (currently YouTube only). */
    val isLive: Boolean = false,
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
    val qualityNote: String? = null,
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
    /**
     * Optional separate audio-only track URL. Modern YouTube only muxes
     * audio+video together at <=360p; for higher quality the video-only and
     * audio-only adaptive streams come separately and must be merged at
     * playback time. When set, [url] is the video-only track and the player
     * merges this audio track alongside it. Null = [url] already carries audio.
     */
    val audioUrl: String? = null,
    /**
     * Selectable audio tracks (one best-bitrate track per language) when the
     * source ships multi-language audio — e.g. YouTube auto-dubs. [audioUrl]
     * is the default (English/original) pick; this list lets the player offer
     * an in-player language switch. Empty / single-entry = no choice to offer.
     */
    val audioTracks: List<AudioOption> = emptyList(),
)

enum class StreamKind { Hls, Mp4, Dash, DirectEmbed }

/** One selectable audio-only track for the in-player audio-language switch. */
data class AudioOption(
    val url: String,
    /** BCP-47 / ISO language code, e.g. "en", "es". Empty if unknown. */
    val language: String,
    /** Human-readable label shown on the picker chip, e.g. "English". */
    val label: String,
)

data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String = language,
    /** Explicit MIME type; when null the player infers it from [url]'s suffix. */
    val mimeOverride: String? = null,
)
