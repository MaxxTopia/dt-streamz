@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.dt.streamz.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.dt.streamz.MainActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.PlayerView
import com.dt.streamz.DtApplication
import com.dt.streamz.data.AudioOption
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.SubtitleTrack
import com.dt.streamz.ui.twitchchat.TwitchChatOverlay
import kotlinx.coroutines.delay

/** How often we persist the resume position while a stream is playing. */
private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

/** Transport-controller (and overlay-chip) auto-hide delay after last input. */
private const val CONTROLLER_TIMEOUT_MS = 4_000

@Composable
fun PlayerScreen(
    url: String,
    streamKind: StreamKind = StreamKind.Hls,
    title: String = "",
    twitchChannel: String? = null,
    startPositionMs: Long = 0,
    audioUrl: String? = null,
    subtitles: List<SubtitleTrack> = emptyList(),
    // Selectable audio-language tracks (YouTube multi-audio). When >1 the
    // player shows an "Audio" chip that cycles languages, rebuilding playback
    // at the same position. Empty/single = no chip.
    audioTracks: List<AudioOption> = emptyList(),
    // Whether captions start visible absent a remembered choice. Anime/movies
    // pass true (keeps existing default-on); YouTube passes false (English
    // audio — captions are opt-in, never auto-on).
    captionsDefaultOn: Boolean = true,
    // When true, the user's CC on/off choice is read at start and persisted on
    // change (YouTube only) so it sticks across videos.
    rememberCaptions: Boolean = false,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onEnded: () -> Unit = {},
    showNextButton: Boolean = false,
    onNext: () -> Unit = {},
    onPrev: () -> Unit = {},
    // Fatal-playback-error hook. When non-null it's called INSTEAD of the
    // toast+exit (e.g. YouTube native -> drop straight to the embed). Null
    // keeps the default "toast the error and bounce to tabs" behavior.
    onPlaybackError: (() -> Unit)? = null,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val monitor = (context.applicationContext as? DtApplication)?.networkMonitor
    var chatOpen by remember(twitchChannel) { mutableStateOf(twitchChannel != null) }
    var speedIdx by remember { mutableStateOf(SPEEDS.indexOf(1f)) }

    // Player "options" panel (audio language / captions / speed / episode
    // nav). Opens on D-pad UP as a focusable Compose overlay that GRABS focus:
    // the ExoPlayer controller won't hand D-pad focus to sibling Compose
    // chips, so the old always-on overlay was unreachable by remote.
    var optionsOpen by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Keep the latest callbacks without restarting the player/effects.
    val onProgressCb by rememberUpdatedState(onProgress)
    val onEndedCb by rememberUpdatedState(onEnded)
    val onExitCb by rememberUpdatedState(onExit)
    val onPlaybackErrorCb by rememberUpdatedState(onPlaybackError)

    // Remembered-captions plumbing + audio-language switching state.
    val playbackPrefs = remember {
        (context.applicationContext as? DtApplication)?.playbackPrefs
    }
    val initialCaptionsOn = remember(url) {
        (if (rememberCaptions) playbackPrefs?.captionsOn() else null) ?: captionsDefaultOn
    }
    // Live caption on/off, kept in sync by the player's onTracksChanged so the
    // options panel can show "Captions: On/Off" and toggle it.
    var captionsShown by remember(url) { mutableStateOf(initialCaptionsOn) }
    // Brief "press ▲ for options" hint at playback start (discoverability).
    var hintVisible by remember(url) { mutableStateOf(true) }
    // Audio switch: when the user cycles languages we swap [effectiveAudioUrl]
    // and capture the current position into [resumeAtMs] so the rebuilt player
    // (keyed on the audio URL) resumes exactly where it left off.
    var audioOverrideUrl by remember(url) { mutableStateOf<String?>(null) }
    val effectiveAudioUrl = audioOverrideUrl ?: audioUrl
    var resumeAtMs by remember(url) { mutableStateOf(startPositionMs) }

    // Player is hoisted out of the AndroidView factory so the progress
    // ticker + dispose handler below can read currentPosition off it. The
    // factory only attaches it to a PlayerView.
    val player = remember(url, effectiveAudioUrl) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            // ±10s jumps for the controller's rewind/fast-forward buttons
            // (and D-pad seek) instead of Media3's 5s/15s defaults.
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaSource(
                    buildMediaSource(url, streamKind, subtitles, effectiveAudioUrl, initialCaptionsOn),
                )
                addListener(object : Player.Listener {
                    // Persist the user's CC choice (YouTube only) so it sticks
                    // across videos. Fires on the initial selection too — that
                    // just re-saves the current value, which is harmless.
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val textOn = tracks.groups.any {
                            it.type == C.TRACK_TYPE_TEXT && it.isSelected
                        }
                        captionsShown = textOn
                        // Persist the choice across videos (YouTube only).
                        if (rememberCaptions) playbackPrefs?.setCaptionsOn(textOn)
                    }

                    // Surface fatal failures instead of sitting on a black
                    // screen — the native player has no mirror-walk, so the
                    // useful move is to tell the user and bounce them back.
                    override fun onPlayerError(error: PlaybackException) {
                        // If the host registered a fallback (YouTube -> embed),
                        // hand off immediately — no toast, least delay.
                        val fallback = onPlaybackErrorCb
                        if (fallback != null) {
                            fallback()
                        } else {
                            Toast.makeText(
                                context,
                                "Playback failed: ${error.errorCodeName}",
                                Toast.LENGTH_LONG,
                            ).show()
                            onExitCb()
                        }
                    }

                    // Auto-play next: when the episode finishes, let the host
                    // resolve + play the next one. No-op for movies / last
                    // episode / live (the host guards on episode context).
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) onEndedCb()
                    }
                })
                if (resumeAtMs > 0) seekTo(resumeAtMs)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(url) {
        monitor?.setActiveHost(url)
        onDispose { monitor?.setActiveHost(null) }
    }

    // Apply the chosen playback speed (and re-apply if the player is rebuilt).
    LaunchedEffect(player, speedIdx) {
        runCatching { player.setPlaybackSpeed(SPEEDS[speedIdx]) }
    }

    // Auto-hide the options hint a few seconds in.
    LaunchedEffect(url) { delay(5_000); hintVisible = false }

    // Route D-pad UP (while this player is on screen) to the options panel.
    // Intercepted at the Activity because PlayerView eats D-pad keys first.
    // Returns true (consume + open) only when the panel is closed; while it's
    // open, UP passes through so Compose can navigate the panel's chips.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.playerOptionsHandler = {
            if (optionsOpen) false else { optionsOpen = true; true }
        }
        onDispose { activity?.playerOptionsHandler = null }
    }

    // Periodically persist resume position while actually playing.
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_SAVE_INTERVAL_MS)
            val pos = player.currentPosition
            val dur = player.duration
            if (player.isPlaying && pos > 0) {
                onProgressCb(pos, if (dur > 0) dur else 0)
            }
        }
    }

    // Final save + release on leaving the screen.
    DisposableEffect(player) {
        onDispose {
            val pos = player.currentPosition
            val dur = player.duration
            if (pos > 0) onProgressCb(pos, if (dur > 0) dur else 0)
            player.release()
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                        useController = true
                        controllerAutoShow = true
                        controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        // CC button toggles the subtitle track (when present).
                        setShowSubtitleButton(subtitles.isNotEmpty())
                        playerViewRef = this
                    }
                },
                update = { view -> view.player = player },
                onRelease = { view ->
                    view.player = null
                    if (playerViewRef === view) playerViewRef = null
                },
            )
            // Discoverability hint: tell the user UP opens the options panel.
            if (hintVisible && !optionsOpen) {
                Text(
                    text = "▲ audio · captions · speed",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            // Options panel — opened by D-pad UP, grabs focus so it's reliably
            // remote-navigable (the transport controller keeps play/pause/seek
            // + CC). BACK or DOWN closes it and hands focus back to the player.
            if (optionsOpen) {
                BackHandler(enabled = true) {
                    optionsOpen = false
                    playerViewRef?.requestFocus()
                }
                val panelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { panelFocus.requestFocus() } }

                // Build the visible options in order; the first one gets initial
                // focus. Audio leads (it's the reason to open the panel), then
                // captions, speed, and episode nav.
                val rows = buildList<@Composable (Modifier) -> Unit> {
                    if (audioTracks.size > 1) {
                        val curIdx = audioTracks.indexOfFirst { it.url == effectiveAudioUrl }
                            .let { if (it < 0) 0 else it }
                        add { m ->
                            PlayerChip("Audio: ${audioTracks[curIdx].label}", modifier = m) {
                                val next = (curIdx + 1) % audioTracks.size
                                resumeAtMs = player.currentPosition.coerceAtLeast(0)
                                audioOverrideUrl = audioTracks[next].url
                                Toast.makeText(
                                    context, "Audio: ${audioTracks[next].label}", Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    if (subtitles.isNotEmpty()) {
                        add { m ->
                            PlayerChip(
                                if (captionsShown) "Captions: On" else "Captions: Off",
                                modifier = m,
                            ) {
                                val turnOn = !captionsShown
                                player.trackSelectionParameters =
                                    player.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !turnOn)
                                        .apply {
                                            if (turnOn) {
                                                setPreferredTextLanguage(
                                                    subtitles.first().language,
                                                )
                                            }
                                        }
                                        .build()
                            }
                        }
                    }
                    add { m ->
                        PlayerChip(speedLabel(SPEEDS[speedIdx]), modifier = m) {
                            speedIdx = (speedIdx + 1) % SPEEDS.size
                        }
                    }
                    if (showNextButton) {
                        add { m -> PlayerChip("Next ▶|", modifier = m, onClick = onNext) }
                        add { m -> PlayerChip("⏮ Prev", modifier = m, onClick = onPrev) }
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rows.forEachIndexed { i, row ->
                        row(if (i == 0) Modifier.focusRequester(panelFocus) else Modifier)
                    }
                    Text(
                        text = "BACK to close",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (twitchChannel != null && chatOpen) {
            TwitchChatOverlay(
                channel = twitchChannel,
                onClose = { chatOpen = false },
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight(),
            )
        }
    }
}

/** Walk the ContextWrapper chain to the hosting MainActivity (or null). */
private fun Context.findActivity(): MainActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is MainActivity) return c
        c = c.baseContext
    }
    return null
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(s: Float): String =
    (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "× speed"

@Composable
private fun PlayerChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.55f),
            focusedContainerColor = Color.White.copy(alpha = 0.92f),
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Pick the right [MediaSource] factory for the stream type. HLS stays on
 * HlsMediaSource (live + VOD). MP4 / progressive containers go through
 * ProgressiveMediaSource. DASH manifests use DashMediaSource — needed for
 * YouTube where most modern videos no longer expose progressive streams.
 *
 * Any sideloaded [subtitles] are merged in as extra tracks via
 * MergingMediaSource so the standalone per-type factories (which don't
 * read MediaItem.subtitleConfigurations themselves) still surface them.
 *
 * DirectEmbed never reaches here (it routes to WebPlayer instead).
 */
@Suppress("DEPRECATION") // SingleSampleMediaSource still the simplest sideload path
private fun buildMediaSource(
    url: String,
    kind: StreamKind,
    subtitles: List<SubtitleTrack>,
    audioUrl: String? = null,
    // When true the subtitle track is flagged DEFAULT so ExoPlayer auto-shows
    // it (anime/movies, or YouTube when the user's remembered CC choice is ON).
    // When false the track is available but starts hidden — the CC button
    // toggles it (YouTube default).
    subtitleDefaultOn: Boolean = true,
): MediaSource {
    val factory = DefaultHttpDataSource.Factory()
        .setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
    val (mime, builderFn) = when (kind) {
        StreamKind.Hls -> MimeTypes.APPLICATION_M3U8 to ::hlsSource
        StreamKind.Mp4 -> MimeTypes.VIDEO_MP4 to ::progressiveSource
        StreamKind.Dash -> MimeTypes.APPLICATION_MPD to ::dashSource
        StreamKind.DirectEmbed -> MimeTypes.VIDEO_MP4 to ::progressiveSource
    }
    val subConfigs = subtitles.map { st ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(st.url))
            .setMimeType(st.mimeOverride ?: subtitleMime(st.url))
            .setLanguage(st.language)
            .setLabel(st.label)
            .setSelectionFlags(if (subtitleDefaultOn) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
    val item = MediaItem.Builder()
        .setUri(url)
        .setMimeType(mime)
        .setSubtitleConfigurations(subConfigs)
        .build()
    val primary = builderFn(factory, item)
    // YouTube high-quality path: [url] is a video-only track and [audioUrl] is
    // the matching audio-only track. Merge them so ExoPlayer plays one video +
    // one audio track together (the only way past YouTube's 360p muxed cap).
    val audioSource = audioUrl?.takeIf { it.isNotBlank() }?.let { au ->
        val audioItem = MediaItem.Builder().setUri(au).build()
        ProgressiveMediaSource.Factory(factory).createMediaSource(audioItem)
    }
    val subSources = subConfigs.map { cfg ->
        SingleSampleMediaSource.Factory(factory).createMediaSource(cfg, C.TIME_UNSET)
    }
    val extras = listOfNotNull(audioSource) + subSources
    if (extras.isEmpty()) return primary
    return MergingMediaSource(primary, *extras.toTypedArray())
}

private fun subtitleMime(url: String): String = when {
    url.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    url.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    url.endsWith(".ass", ignoreCase = true) ||
        url.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.TEXT_VTT
}

private fun hlsSource(factory: DefaultHttpDataSource.Factory, item: MediaItem): MediaSource =
    HlsMediaSource.Factory(factory).createMediaSource(item)

private fun progressiveSource(factory: DefaultHttpDataSource.Factory, item: MediaItem): MediaSource =
    ProgressiveMediaSource.Factory(factory).createMediaSource(item)

private fun dashSource(factory: DefaultHttpDataSource.Factory, item: MediaItem): MediaSource =
    DashMediaSource.Factory(factory).createMediaSource(item)
