package com.dt.streamz.ui

import com.dt.streamz.data.AudioOption
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.SubtitleTrack

sealed interface Route {
    data object Tabs : Route
    data class Details(val providerId: String, val titleId: String) : Route
    data class SourcePicker(
        val title: String,
        val sources: List<StreamSource>,
        // Resume context carried through to the chosen source's player.
        val providerId: String? = null,
        val titleId: String? = null,
        val episodeId: String? = null,
        val startPositionMs: Long = 0,
    ) : Route
    data class Player(
        val url: String,
        val title: String = "",
        val twitchChannel: String? = null,
        val kind: StreamKind = StreamKind.Hls,
        // Optional separate audio-only track, merged with [url] at playback
        // time (YouTube adaptive video-only + audio-only). Null when [url]
        // already carries audio.
        val audioUrl: String? = null,
        // Resume-watching context. Non-null for on-demand episodes/movies so
        // the player can seek to [startPositionMs] and write progress back.
        // All null for live Twitch (no position to persist).
        val providerId: String? = null,
        val titleId: String? = null,
        val episodeId: String? = null,
        val startPositionMs: Long = 0,
        val subtitles: List<SubtitleTrack> = emptyList(),
        // Selectable audio-language tracks for the in-player switch (YouTube
        // multi-audio). Empty = no switch shown.
        val audioTracks: List<AudioOption> = emptyList(),
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
        // Full ranked source list + resume context, so the player can offer a
        // manual server picker as a last resort if every mirror fails.
        val allSources: List<StreamSource> = emptyList(),
        val providerId: String? = null,
        val titleId: String? = null,
        val episodeId: String? = null,
        val startPositionMs: Long = 0,
    ) : Route
}
