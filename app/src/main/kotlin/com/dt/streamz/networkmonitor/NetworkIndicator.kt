package com.dt.streamz.networkmonitor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Small top-right overlay showing connection health. Auto-hides when
 * everything's fine (green for >GREEN_HIDE_MS), re-shows instantly on
 * any degradation or disconnect. Never focusable — D-pad ignores it.
 */
@Composable
fun NetworkIndicator(
    monitor: NetworkMonitor,
    modifier: Modifier = Modifier,
    // During playback we keep the indicator faint so it doesn't draw the eye
    // away from the video — it's still there for a quick glance, just quiet.
    dim: Boolean = false,
) {
    val state by monitor.state.collectAsState()
    var hiddenAfterGreen by remember { mutableStateOf(false) }

    LaunchedEffect(state.tier) {
        if (state.tier == Tier.Green && state.connected) {
            delay(GREEN_HIDE_MS)
            // Re-check after the delay; user may have degraded in the meantime.
            if (monitor.state.value.tier == Tier.Green && monitor.state.value.connected) {
                hiddenAfterGreen = true
            }
        } else {
            hiddenAfterGreen = false
        }
    }

    val visible = !hiddenAfterGreen || state.tier != Tier.Green || !state.connected
    // Faint while watching (so it doesn't take away from the video), full
    // opacity otherwise. A disconnect still forces near-full so a genuine
    // problem mid-stream is readable.
    val maxAlpha = if (dim && state.connected) 0.3f else 1f
    val alphaFrac by animateFloatAsState(
        targetValue = if (visible) maxAlpha else 0f,
        label = "netmon-alpha",
    )
    if (alphaFrac < 0.01f) return

    val dotColor = when {
        !state.connected -> Color(0xFFEF5350)
        state.tier == Tier.Green -> Color(0xFF66BB6A)
        state.tier == Tier.Yellow -> Color(0xFFFFCA28)
        state.tier == Tier.Red -> Color(0xFFEF5350)
        else -> Color(0xFFB0BEC5)
    }

    Row(
        modifier = modifier
            .alpha(alphaFrac)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF212121).copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        val label = when {
            !state.connected -> "offline"
            state.latencyMs != null -> "${state.latencyMs} ms"
            else -> "…"
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

private const val GREEN_HIDE_MS = 30_000L
