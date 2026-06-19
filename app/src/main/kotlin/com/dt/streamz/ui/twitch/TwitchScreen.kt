package com.dt.streamz.ui.twitch

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.LaunchedEffect
import com.dt.streamz.DtApplication
import com.dt.streamz.ui.onMenuKeyUp
import com.dt.streamz.twitch.TwitchStreamResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

@Composable
fun TwitchScreen(
    onOpenChannel: (channel: String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val scope = rememberCoroutineScope()
    val channels by (app.pinnedChannels.channels).collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var liveStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val resolver = remember { TwitchStreamResolver() }

    // Probe each pinned channel for live status when the tab opens or the
    // list changes. Uses GQL user.stream (null = offline); the PAT endpoint
    // issues tokens for offline channels too so it can't serve as a probe.
    LaunchedEffect(channels) {
        if (channels.isEmpty()) return@LaunchedEffect
        // Cap + guard each probe: a hung/erroring isLive() call must not leave
        // the badge stuck on "… checking" forever. Timeout or throw -> OFFLINE.
        val results = channels.map { ch ->
            async {
                ch to (runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(6_000) { resolver.isLive(ch) }
                }.getOrNull() == true)
            }
        }.awaitAll()
        liveStatus = results.toMap()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Twitch",
            style = androidx.tv.material3.MaterialTheme.typography.headlineSmall,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = when {
                channels.isEmpty() -> "No pinned channels — add one with the + card."
                else -> "Click a channel to watch (ad-free HLS). Press MENU to remove."
            },
            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(channels, key = { it }) { channel ->
                ChannelCard(
                    channel = channel,
                    enabled = true,
                    live = liveStatus[channel],
                    onClick = { onOpenChannel(channel) },
                    onRequestRemove = {
                        scope.launch {
                            app.pinnedChannels.remove(channel)
                            Toast.makeText(ctx, "Removed $channel", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            item(key = "__add__") {
                AddChannelCard(onClick = { showAddDialog = true })
            }
        }
    }

    if (showAddDialog) {
        // Reuse the app-wide D-pad on-screen keyboard. The box's system IME
        // never reliably delivers committed text back to a field (see the
        // SearchEditorDialog header), so a Material text field here meant the
        // channel often couldn't be typed at all. The grid keyboard always
        // works on the remote.
        com.dt.streamz.ui.search.SearchEditorDialog(
            initialQuery = "",
            onDismiss = { showAddDialog = false },
            onSubmit = { text ->
                // Accept a pasted/typed "twitch.tv/name" or bare login; logins
                // are lower-case.
                val cleaned = text.trim().lowercase()
                    .substringAfterLast("twitch.tv/")
                    .trim('/', ' ')
                if (cleaned.isNotBlank()) {
                    scope.launch { app.pinnedChannels.add(cleaned) }
                }
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun Text(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    androidx.tv.material3.Text(text = text, style = style, color = color)
}

@Composable
private fun ChannelCard(
    channel: String,
    enabled: Boolean,
    live: Boolean?,
    onClick: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Box(
        modifier = Modifier
            .width(196.dp)
            .onFocusChanged { focused = it.isFocused }
            .onMenuKeyUp(focused, onRequestRemove)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) mt.colorScheme.primary else mt.colorScheme.surface,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val (badgeText, badgeColor) = when (live) {
                true -> "● LIVE" to Color(0xFFE91916)
                false -> "○ OFFLINE" to Color(0xFF9E9E9E)
                null -> "… checking" to Color(0xFFBDBDBD)
            }
            androidx.tv.material3.Text(
                text = badgeText,
                style = mt.typography.labelSmall,
                color = if (focused) Color.White else badgeColor,
            )
            androidx.tv.material3.Text(
                text = channel,
                style = mt.typography.titleSmall,
                color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            )
            androidx.tv.material3.Text(
                text = "twitch.tv/$channel",
                style = mt.typography.labelSmall,
                color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.7f)
                else mt.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun AddChannelCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Box(
        modifier = Modifier
            .width(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) mt.colorScheme.primary
                else mt.colorScheme.surface.copy(alpha = 0.5f),
            )
            .clickable(onClick = onClick)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            androidx.tv.material3.Text(
                text = "+",
                style = mt.typography.headlineMedium,
                color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            )
            androidx.tv.material3.Text(
                text = "Add channel",
                style = mt.typography.labelMedium,
                color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.75f)
                else mt.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

