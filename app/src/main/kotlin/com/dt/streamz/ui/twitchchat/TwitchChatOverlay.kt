package com.dt.streamz.ui.twitchchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.twitch.ChatMessage
import com.dt.streamz.twitch.TwitchChat

/**
 * Right-side chat column that lives inside PlayerScreen while a Twitch
 * stream is playing. Owns a [TwitchChat] IRC client keyed on [channel];
 * starts on compose, stops on dispose.
 */
@Composable
fun TwitchChatOverlay(
    channel: String,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val chat = remember(channel) { TwitchChat(channel) }
    DisposableEffect(channel) {
        chat.start()
        onDispose { chat.stop() }
    }

    val messages by chat.messages.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "chat · #$channel",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFA18AFF),
                modifier = Modifier.weight(1f),
            )
            CloseChatChip(onClose = onClose)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages) { msg -> ChatRow(msg) }
        }
    }
}

@Composable
private fun CloseChatChip(onClose: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClose,
        modifier = Modifier
            .size(26.dp)
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.18f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ChatRow(msg: ChatMessage) {
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = userColor(msg.user), fontWeight = FontWeight.SemiBold)) {
            append(msg.user)
        }
        append(": ")
        append(msg.text)
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
    )
}

private fun userColor(user: String): Color {
    // Stable pastel per user so chat is readable without an IRCv3 tag parser.
    val hash = user.hashCode()
    val hue = ((hash and 0xFFFFFF) % 360).toFloat()
    return hsv(hue, 0.55f, 0.95f)
}

private fun hsv(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = v - c
    val (rp, gp, bp) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(rp + m, gp + m, bp + m)
}
