package com.dt.streamz.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.dt.streamz.DtApplication
import com.dt.streamz.ui.twitchchat.TwitchChatOverlay

@Composable
fun PlayerScreen(
    hlsUrl: String,
    title: String = "",
    twitchChannel: String? = null,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val monitor = (context.applicationContext as? DtApplication)?.networkMonitor
    var chatOpen by remember(twitchChannel) { mutableStateOf(twitchChannel != null) }

    // While the player is visible, retarget the net-monitor probe at the
    // stream CDN so the indicator reflects the actual pipe we're watching.
    DisposableEffect(hlsUrl) {
        monitor?.setActiveHost(hlsUrl)
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
                            val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                                .createMediaSource(
                                    MediaItem.Builder()
                                        .setUri(hlsUrl)
                                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                                        .build(),
                                )
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
