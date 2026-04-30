package com.dt.streamz.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.dt.streamz.DtApplication
import com.dt.streamz.data.StreamKind
import com.dt.streamz.ui.twitchchat.TwitchChatOverlay

@Composable
fun PlayerScreen(
    url: String,
    streamKind: StreamKind = StreamKind.Hls,
    title: String = "",
    twitchChannel: String? = null,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val monitor = (context.applicationContext as? DtApplication)?.networkMonitor
    var chatOpen by remember(twitchChannel) { mutableStateOf(twitchChannel != null) }

    DisposableEffect(url) {
        monitor?.setActiveHost(url)
        onDispose { monitor?.setActiveHost(null) }
    }

    Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val loadControl = DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            /* minBufferMs = */ 5_000,
                            /* maxBufferMs = */ 30_000,
                            /* bufferForPlaybackMs = */ 1_500,
                            /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                        )
                        .build()

                    val player = ExoPlayer.Builder(ctx)
                        .setLoadControl(loadControl)
                        .build()
                        .apply {
                            val source = buildMediaSource(url, streamKind)
                            setMediaSource(source)
                            prepare()
                            playWhenReady = true
                        }

                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                        useController = true
                        controllerAutoShow = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                onRelease = { view ->
                    view.player?.release()
                    view.player = null
                },
            )
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

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles player disposal */ }
    }
}

/**
 * Pick the right [MediaSource] factory for the stream type. HLS stays on
 * HlsMediaSource (live + VOD). MP4 / progressive containers go through
 * ProgressiveMediaSource. DASH manifests use DashMediaSource — needed for
 * YouTube where most modern videos no longer expose progressive streams.
 *
 * DirectEmbed never reaches here (it routes to WebPlayer instead).
 */
private fun buildMediaSource(url: String, kind: StreamKind): MediaSource {
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
    val item = MediaItem.Builder().setUri(url).setMimeType(mime).build()
    return builderFn(factory, item)
}

private fun hlsSource(factory: DefaultHttpDataSource.Factory, item: MediaItem): MediaSource =
    HlsMediaSource.Factory(factory).createMediaSource(item)

private fun progressiveSource(factory: DefaultHttpDataSource.Factory, item: MediaItem): MediaSource =
    ProgressiveMediaSource.Factory(factory).createMediaSource(item)

private fun dashSource(factory: DefaultHttpDataSource.Factory, item: MediaItem): MediaSource =
    DashMediaSource.Factory(factory).createMediaSource(item)
