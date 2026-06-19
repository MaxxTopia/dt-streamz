package com.dt.streamz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/**
 * A poster/thumbnail that NEVER shows a dead grey box. A branded gradient +
 * the title sits underneath as a permanent fallback; the real image fades in
 * on top when it loads. If the URL is null, slow, or fails, the gradient
 * fallback stays — so a missing poster reads as intentional, not broken.
 *
 * The gradient hue is derived from the title so each fallback looks distinct
 * (a wall of identical placeholders looks like a bug; varied ones look styled).
 */
@Composable
fun PosterImage(
    model: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier = modifier.background(brush = fallbackBrush(title)), contentAlignment = Alignment.Center) {
        Text(
            text = title.ifBlank { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        if (!model.isNullOrBlank()) {
            AsyncImage(
                model = model,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Deterministic two-stop gradient seeded by the title, in the app's palette. */
fun fallbackBrush(seed: String): Brush {
    val h = seed.fold(0) { acc, c -> acc * 31 + c.code }
    val top = POSTER_FALLBACK_COLORS[(h ushr 1).mod(POSTER_FALLBACK_COLORS.size)]
    val bottom = POSTER_FALLBACK_COLORS[(h ushr 5).mod(POSTER_FALLBACK_COLORS.size)]
    return Brush.verticalGradient(listOf(top, bottom))
}

// Muted, dark jewel tones — on-brand with the app's navy/dark theme.
private val POSTER_FALLBACK_COLORS = listOf(
    Color(0xFF2A2D4A), Color(0xFF3A2A4A), Color(0xFF2A3A4A),
    Color(0xFF4A2A3A), Color(0xFF2A4A3E), Color(0xFF3A3A2A),
)
