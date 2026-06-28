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
        // Pre-built DASH manifest XML (YouTube wrapped-progressive). When set
        // the player builds a DashMediaSource from it instead of a progressive
        // URI — makes ExoPlayer issue ranged segment GETs so googlevideo doesn't
        // throttle the single open-ended request (the "buffers until you seek"
        // bug). [audioDashManifest] is the matching audio track. See StreamSource.
        val dashManifest: String? = null,
        val audioDashManifest: String? = null,
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
        // True for live broadcasts (YouTube live, Twitch) — the player applies
        // a live-tuned buffer + live target offset so it starts behind the edge
        // instead of stalling at it.
        val isLive: Boolean = false,
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
