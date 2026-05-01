package com.dt.streamz.ui.brand

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * "DT" wordmark with a subtle horizontal shimmer sweep. Lives top-right
 * next to the network indicator.
 */
@Composable
fun DtLogo(modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "dt-shimmer")
    val phase by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dt-phase",
    )

    // 3-stop gradient that travels across the text. Highlight stop is the
    // middle; both ends fade toward the primary color so the sweep reads
    // without obscuring the letters.
    val base = Color(0xFFDDE4FF)
    val bright = Color(0xFFFFFFFF)
    val sweepWidth = 220f
    val startX = -sweepWidth + phase * (sweepWidth * 3f)
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to base,
            0.5f to bright,
            1f to base,
        ),
        start = Offset(startX, 0f),
        end = Offset(startX + sweepWidth, 0f),
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.30f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "DT",
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                brush = brush,
            ),
        )
    }
}
