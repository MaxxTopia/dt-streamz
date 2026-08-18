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
        // Some native HLS CDNs require the embed's referer/origin on the
        // manifest and segment requests. DirectEmbed already carries headers;
        // native playback must carry them too when a provider returns HLS.
        val headers: Map<String, String> = emptyMap(),
        /**
         * Same-language candidates to try if this native route fails before
         * playback starts. The first candidate is selected automatically;
         * the picker remains available through BACK for manual selection.
         */
        val fallbacks: List<StreamSource> = emptyList(),
        /**
         * Full same-title source list retained across a native-to-WebView
         * handoff. Without this, a failed MegaPlay route handed VidNest only
         * its own URL, so the final error screen could not offer the original
         * English-Dub server choices.
         */
        val sourceChoices: List<StreamSource> = emptyList(),
        /**
         * Optional startup watchdog for a native route whose same-language
         * fallback should take over if the box never reaches READY.
         */
        val startupFallbackDelayMs: Long? = null,
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
        // Per-source caption preference. YouTube still applies its own
        // remembered off-by-default behavior in DtApp.
        val captionsDefaultOn: Boolean = true,
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
        // Keep the selected source label with the player route. A provider can
        // expose Sub and Dub as siblings, but a failed Dub route must never
        // silently fall through to the Japanese/Sub route.
        val selectedSourceLabel: String? = null,
        val providerId: String? = null,
        val titleId: String? = null,
        val episodeId: String? = null,
        val startPositionMs: Long = 0,
        // English Dub embeds can carry an English text track that the provider
        // marks DEFAULT. The WebView applies this preference on that source.
        val captionsDefaultOn: Boolean = true,
    ) : Route
}
