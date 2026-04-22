package com.dt.streamz.ui.twitch

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dt.streamz.DtApplication
import com.dt.streamz.twitch.TwitchStreamResolver
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TwitchScreen(
    onPlayHlsWithChat: (hlsUrl: String, title: String, channel: String) -> Unit = { _, _, _ -> },
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val scope = rememberCoroutineScope()
    val resolver = remember { TwitchStreamResolver() }
    val channels by (app.pinnedChannels.channels).collectAsState(initial = emptyList())
    var resolving by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newChannel by remember { mutableStateOf("") }

    fun openChannel(channel: String) {
        if (resolving != null) return
        resolving = channel
        scope.launch {
            val url = resolver.resolveHls(channel)
            resolving = null
            if (url == null) {
                Toast.makeText(ctx, "$channel is offline or Twitch refused the token", Toast.LENGTH_SHORT).show()
            } else {
                onPlayHlsWithChat(url, "twitch.tv/$channel", channel)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Twitch",
            style = androidx.tv.material3.MaterialTheme.typography.displaySmall,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = when {
                resolving != null -> "Resolving $resolving…"
                channels.isEmpty() -> "No pinned channels — add one with the + card."
                else -> "Click a channel to watch (ad-free HLS). Long-press OK to remove."
            },
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(channels, key = { it }) { channel ->
                ChannelCard(
                    channel = channel,
                    enabled = resolving == null,
                    onClick = { openChannel(channel) },
                    onLongClick = {
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
        AddChannelDialog(
            initial = newChannel,
            onChange = { newChannel = it },
            onDismiss = {
                showAddDialog = false
                newChannel = ""
            },
            onSubmit = {
                val cleaned = newChannel.trim()
                if (cleaned.isNotBlank()) {
                    scope.launch { app.pinnedChannels.add(cleaned) }
                }
                showAddDialog = false
                newChannel = ""
            },
        )
    }
}

@Composable
private fun Text(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    androidx.tv.material3.Text(text = text, style = style, color = color)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    channel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Box(
        modifier = Modifier
            .width(260.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) mt.colorScheme.primary else mt.colorScheme.surface,
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.tv.material3.Text(
                text = "● LIVE",
                style = mt.typography.labelLarge,
                color = if (focused) Color.White else Color(0xFFE91916),
            )
            androidx.tv.material3.Text(
                text = channel,
                style = mt.typography.titleLarge,
                color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            )
            androidx.tv.material3.Text(
                text = "twitch.tv/$channel",
                style = mt.typography.bodySmall,
                color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.75f)
                else mt.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddChannelCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Box(
        modifier = Modifier
            .width(200.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) mt.colorScheme.primary
                else mt.colorScheme.surface.copy(alpha = 0.5f),
            )
            .combinedClickable(onClick = onClick)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.tv.material3.Text(
                text = "+",
                style = mt.typography.displayMedium,
                color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            )
            androidx.tv.material3.Text(
                text = "Add channel",
                style = mt.typography.bodyMedium,
                color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.75f)
                else mt.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun AddChannelDialog(
    initial: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val mt = androidx.tv.material3.MaterialTheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(16.dp))
                .background(mt.colorScheme.surface)
                .padding(24.dp),
        ) {
            OutlinedTextField(
                value = initial,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { androidx.compose.material3.Text("Twitch channel (login)") },
                placeholder = { androidx.compose.material3.Text("e.g. aussieantics") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onSubmit()
                }),
                textStyle = mt.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = mt.colorScheme.surface,
                    unfocusedContainerColor = mt.colorScheme.surface,
                    focusedTextColor = mt.colorScheme.onSurface,
                    unfocusedTextColor = mt.colorScheme.onSurface,
                    cursorColor = mt.colorScheme.primary,
                    focusedLabelColor = mt.colorScheme.primary,
                    unfocusedLabelColor = mt.colorScheme.onSurface.copy(alpha = 0.7f),
                    focusedBorderColor = mt.colorScheme.primary,
                    unfocusedBorderColor = mt.colorScheme.onSurface.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

@Suppress("unused")
private val unusedDefault = flowOf<List<String>>(emptyList())
