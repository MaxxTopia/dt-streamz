package com.dt.streamz.ui.twitch

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.twitch.TwitchConfig
import com.dt.streamz.twitch.TwitchStreamResolver
import kotlinx.coroutines.launch

@Composable
fun TwitchScreen(onPlayHls: (hlsUrl: String, title: String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember { TwitchStreamResolver() }
    var resolving by remember { mutableStateOf<String?>(null) }

    fun openChannel(channel: String) {
        if (resolving != null) return
        resolving = channel
        scope.launch {
            val url = resolver.resolveHls(channel)
            resolving = null
            if (url == null) {
                Toast.makeText(ctx, "$channel is offline or Twitch refused the token", Toast.LENGTH_SHORT).show()
            } else {
                onPlayHls(url, "twitch.tv/$channel")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Twitch",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (resolving != null) "Resolving $resolving…"
            else "Pinned live channels — click to watch (ad-free via Twire-style HLS).",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TwitchConfig.PINNED_CHANNELS.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    enabled = resolving == null,
                    onClick = { openChannel(channel) },
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.32f)
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Transparent)
                .border(
                    2.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "● LIVE",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused) Color.White else Color(0xFFE91916),
                )
                Text(
                    text = channel,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "twitch.tv/$channel",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
