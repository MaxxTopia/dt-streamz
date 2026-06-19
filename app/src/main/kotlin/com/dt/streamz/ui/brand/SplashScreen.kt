package com.dt.streamz.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Cold-start splash, hardened for the VSeebox / SuperBox class of low-power
 * Android TV boxes.
 *
 * Frame-drop-proof by design: ALL choreography is driven from ONE wall-clock
 * timeline. A single [withFrameNanos] loop records the first rendered frame's
 * timestamp (t0) and, every frame, computes elapsed real-time milliseconds and
 * derives EVERY phase's value as a pure function of that elapsed time. So a
 * dropped/late frame never desyncs the visuals from the fixed-length guitar
 * (fired once at [onBegin] on the first frame) and never leaves the animation
 * stuck mid-state — a late frame just jumps progress to where the wall clock
 * now says it should be. This kills the old "rendered visuals drift vs the
 * wall-clock guitar on frame drops" problem.
 *
 * Per-frame cost is minimised:
 *  - alpha/scale are read inside [graphicsLayer] lambdas (draw phase), so the
 *    tiles / wordmark / subtitle / root update WITHOUT recomposing and without
 *    re-running the glyph [Canvas] draws.
 *  - every Brush/gradient/TextStyle is hoisted/remembered, not rebuilt per frame.
 *
 * Visual identity is unchanged: five brand tiles slide+scale+fade in staggered,
 * then VIEWMAXXING + "made by DT" fade in, then everything fades out (~2.3s
 * body, tuned to the 1.94s arpeggio). Press any key to skip (short graceful fade).
 */
@Composable
fun SplashScreen(onFinished: () -> Unit, onBegin: () -> Unit = {}) {
    val tiles = remember { TILES }

    // Single source of truth for every animated value. Plain float states
    // written once per frame from the timeline loop and read ONLY inside
    // graphicsLayer lambdas (draw phase) — so updating them never recomposes
    // the tiles or re-runs their glyph Canvases.
    val tileProgress = remember { List(tiles.size) { mutableFloatStateOf(0f) } }
    val wordmarkAlpha = remember { mutableFloatStateOf(0f) }
    val subtitleAlpha = remember { mutableFloatStateOf(0f) }
    val rootAlpha = remember { mutableFloatStateOf(1f) }

    // Skip handling. skipStartMs < 0 == not skipped. lastElapsedMs is published
    // each frame so the key handler can timestamp the skip against the SAME
    // wall-clock origin the loop uses. Instance-scoped (not top-level) so a
    // second show in-process would start clean.
    val skipStartMs = remember { mutableFloatStateOf(-1f) }
    val lastElapsedMs = remember { mutableFloatStateOf(0f) }

    val focusReq = remember { FocusRequester() }
    val bgBrush = remember {
        Brush.radialGradient(
            colors = listOf(Color(0xFF101226), Color(0xFF050610), Color.Black),
            radius = 1400f,
        )
    }

    LaunchedEffect(Unit) {
        runCatching { focusReq.requestFocus() }

        // t0 = first ACTUALLY rendered frame. The body runs before the splash
        // paints, so gating both the guitar and t=0 on the first frame means
        // audio and the first visible tile motion start together — and the
        // whole timeline shares that one origin.
        val t0 = withFrameNanos { it }
        onBegin()

        var finished = false
        fun finishOnce() { if (!finished) { finished = true; onFinished() } }

        while (!finished) {
            val frame = withFrameNanos { it }
            val elapsedMs = (frame - t0) / 1_000_000f
            lastElapsedMs.floatValue = elapsedMs

            // A skip folds the timeline: freeze the body at the skip moment and
            // run a short fade from there instead of jumping to black.
            val skip = skipStartMs.floatValue
            val body: Float
            val fadeProgress: Float
            if (skip >= 0f) {
                body = skip
                fadeProgress = ((elapsedMs - skip) / SKIP_FADE_MS).coerceIn(0f, 1f)
            } else {
                body = elapsedMs
                fadeProgress = if (elapsedMs >= FADE_START_MS) {
                    ((elapsedMs - FADE_START_MS) / FADE_MS).coerceIn(0f, 1f)
                } else 0f
            }

            for (i in tiles.indices) {
                val raw = ((body - i * TILE_STAGGER_MS) / TILE_ANIM_MS).coerceIn(0f, 1f)
                tileProgress[i].floatValue = easeOutBack(raw)
            }
            wordmarkAlpha.floatValue =
                ((body - WORDMARK_START_MS) / WORDMARK_MS).coerceIn(0f, 1f)
            subtitleAlpha.floatValue =
                ((body - SUBTITLE_START_MS) / SUBTITLE_MS).coerceIn(0f, 1f)
            rootAlpha.floatValue = easeOutCubic(1f - fadeProgress)

            if (fadeProgress >= 1f) finishOnce()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Root fade via graphicsLayer (draw phase) — no recomposition.
            .graphicsLayer { alpha = rootAlpha.floatValue }
            .focusRequester(focusReq)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && skipStartMs.floatValue < 0f) {
                    // Timeline loop turns this into a graceful fade and is the
                    // SOLE caller of onFinished — no double-finish race.
                    skipStartMs.floatValue = lastElapsedMs.floatValue
                    true
                } else false
            }
            .background(bgBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                tiles.forEachIndexed { index, tile ->
                    // Pass the float STATE, not its value — read inside
                    // graphicsLayer so per-frame ticks skip recomposition.
                    BrandTile(tile = tile, progress = tileProgress[index])
                }
            }
            Spacer(Modifier.height(36.dp))
            Wordmark(alpha = wordmarkAlpha)
            Spacer(Modifier.height(14.dp))
            Text(
                text = "made by DT",
                modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.floatValue },
                style = SUBTITLE_STYLE,
            )
        }
    }
}

@Composable
private fun BrandTile(tile: BrandTile, progress: MutableFloatState) {
    // Hoisted per-tile gradient — allocated once, reused every frame.
    val bgBrush = remember(tile.color) {
        Brush.linearGradient(
            listOf(
                tile.color.copy(alpha = 0.95f),
                tile.color.copy(alpha = 0.55f),
            ),
        )
    }
    Box(
        modifier = Modifier
            .size(width = 110.dp, height = 110.dp)
            // alpha + scale in the draw phase: animating [progress] never
            // recomposes this Box nor re-runs the glyph Canvas below.
            .graphicsLayer {
                val p = progress.floatValue
                alpha = p
                val s = 0.7f + 0.3f * p
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(20.dp))
            .background(bgBrush),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = size.minDimension * 0.22f
            val inner = Size(size.width - pad * 2, size.height - pad * 2)
            drawIntoInner(tile, Offset(pad, pad), inner)
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = tile.label, style = TILE_LABEL_STYLE)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun DrawScope.drawIntoInner(tile: BrandTile, offset: Offset, area: Size) {
    when (tile.kind) {
        TileKind.Movies -> drawMoviesGlyph(offset, area)
        TileKind.Anime -> drawAnimeGlyph(offset, area)
        TileKind.Tv -> drawTvGlyph(offset, area)
        TileKind.YouTube -> drawYouTubeGlyph(offset, area)
        TileKind.Twitch -> drawTwitchGlyph(offset, area)
    }
}

private fun DrawScope.drawYouTubeGlyph(o: Offset, s: Size) {
    val pillW = s.width * 0.78f
    val pillH = s.height * 0.52f
    val pillX = o.x + (s.width - pillW) / 2f
    val pillY = o.y + (s.height - pillH) / 2f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(pillX, pillY),
        size = Size(pillW, pillH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillH * 0.36f, pillH * 0.36f),
    )
    val cx = o.x + s.width / 2f
    val cy = o.y + s.height / 2f
    val triH = pillH * 0.52f
    val triW = triH * 0.86f
    val tri = Path().apply {
        moveTo(cx - triW / 2f, cy - triH / 2f)
        lineTo(cx + triW / 2f, cy)
        lineTo(cx - triW / 2f, cy + triH / 2f)
        close()
    }
    drawPath(tri, color = Color(0xFFE53935))
}

private fun DrawScope.drawMoviesGlyph(o: Offset, s: Size) {
    val stripL = o.x + s.width * 0.10f
    val stripR = o.x + s.width * 0.90f
    val stripT = o.y + s.height * 0.18f
    val stripB = o.y + s.height * 0.82f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(stripL, stripT),
        size = Size(stripR - stripL, stripB - stripT),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.06f, s.width * 0.06f),
    )
    val holeW = s.width * 0.085f
    val holeH = s.height * 0.085f
    val tint = Color(0xFF8A6800)
    val rows = listOf(stripT + s.height * 0.07f, stripB - s.height * 0.07f - holeH)
    for (row in rows) {
        for (i in 0..3) {
            val cx = stripL + s.width * 0.10f + i * s.width * 0.18f
            drawRoundRect(
                color = tint,
                topLeft = Offset(cx, row),
                size = Size(holeW, holeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(holeW * 0.35f, holeW * 0.35f),
            )
        }
    }
}

private fun DrawScope.drawAnimeGlyph(o: Offset, s: Size) {
    val cx = o.x + s.width / 2f
    val cy = o.y + s.height / 2f
    val long = s.width * 0.46f
    val waist = s.width * 0.07f

    fun petal(angleRad: Double): Path {
        val cos = kotlin.math.cos(angleRad).toFloat()
        val sin = kotlin.math.sin(angleRad).toFloat()
        val tx = cos * long
        val ty = sin * long
        val px = -sin * waist
        val py = cos * waist
        return Path().apply {
            moveTo(cx + tx, cy + ty)
            quadraticTo(cx + px, cy + py, cx, cy)
            quadraticTo(cx - px, cy - py, cx + tx, cy + ty)
            close()
        }
    }
    drawCircle(
        color = Color(0xFFFFFFFF).copy(alpha = 0.18f),
        radius = s.width * 0.40f,
        center = Offset(cx, cy),
    )
    val petals = listOf(0.0, Math.PI / 2, Math.PI, 3 * Math.PI / 2)
    for (a in petals) {
        drawPath(petal(a), color = Color.White)
        drawPath(petal(a + Math.PI), color = Color.White)
    }
    drawCircle(
        color = Color(0xFFFFE8EC),
        radius = s.width * 0.055f,
        center = Offset(cx, cy),
    )
}

private fun DrawScope.drawTvGlyph(o: Offset, s: Size) {
    val antennaTopY = o.y + s.height * 0.06f
    val screenTop = o.y + s.height * 0.32f
    val screenLeft = o.x + s.width * 0.13f
    val screenW = s.width * 0.74f
    val screenH = s.height * 0.50f
    val cx = o.x + s.width / 2f

    val antennaStroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 4.5f,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
        join = androidx.compose.ui.graphics.StrokeJoin.Round,
    )
    val leftPath = Path().apply {
        moveTo(cx - 2f, screenTop)
        lineTo(cx - s.width * 0.20f, antennaTopY)
    }
    val rightPath = Path().apply {
        moveTo(cx + 2f, screenTop)
        lineTo(cx + s.width * 0.20f, antennaTopY)
    }
    drawPath(leftPath, color = Color.White, style = antennaStroke)
    drawPath(rightPath, color = Color.White, style = antennaStroke)

    drawRoundRect(
        color = Color.White,
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenW, screenH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.10f, s.width * 0.10f),
    )
    val inset = s.width * 0.05f
    drawRoundRect(
        color = Color(0xFF0D47A1),
        topLeft = Offset(screenLeft + inset, screenTop + inset),
        size = Size(screenW - inset * 2, screenH - inset * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.07f, s.width * 0.07f),
    )
    val baseY = screenTop + screenH + s.height * 0.02f
    val footW = s.width * 0.08f
    val footH = s.height * 0.05f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(cx - s.width * 0.20f, baseY),
        size = Size(footW, footH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(footH * 0.4f, footH * 0.4f),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(cx + s.width * 0.12f, baseY),
        size = Size(footW, footH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(footH * 0.4f, footH * 0.4f),
    )
}

private fun DrawScope.drawTwitchGlyph(o: Offset, s: Size) {
    val left = o.x + s.width * 0.20f
    val right = o.x + s.width * 0.80f
    val top = o.y + s.height * 0.13f
    val bottom = o.y + s.height * 0.83f
    val stepX = s.width * 0.13f
    val stepY = s.height * 0.13f
    val mark = Path().apply {
        moveTo(left, top + stepY)
        lineTo(left + stepX * 0.85f, top)
        lineTo(right, top)
        lineTo(right, bottom - stepY * 1.4f)
        lineTo(right - stepX * 0.95f, bottom - stepY * 0.55f)
        lineTo(right - stepX * 1.7f, bottom - stepY * 0.55f)
        lineTo(right - stepX * 2.4f, bottom)
        lineTo(right - stepX * 2.9f, bottom)
        lineTo(right - stepX * 2.9f, bottom - stepY * 0.55f)
        lineTo(left, bottom - stepY * 0.55f)
        close()
    }
    drawPath(mark, color = Color.White)
    val eyeW = stepX * 0.42f
    val eyeH = stepY * 1.6f
    val eyeY = top + stepY * 1.55f
    drawRoundRect(
        color = Color(0xFF6A2BD8),
        topLeft = Offset(left + stepX * 1.05f, eyeY),
        size = Size(eyeW, eyeH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW * 0.45f, eyeW * 0.45f),
    )
    drawRoundRect(
        color = Color(0xFF6A2BD8),
        topLeft = Offset(left + stepX * 2.30f, eyeY),
        size = Size(eyeW, eyeH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW * 0.45f, eyeW * 0.45f),
    )
}

@Composable
private fun Wordmark(alpha: MutableFloatState) {
    Text(
        text = "VIEWMAXXING",
        modifier = Modifier.graphicsLayer { this.alpha = alpha.floatValue },
        style = WORDMARK_STYLE,
    )
}

// --- Cached, hoisted styles (no per-frame allocation) -----------------------

private val WORDMARK_BRUSH: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color(0xFFB0BBFF),
        0.5f to Color(0xFFFFFFFF),
        1f to Color(0xFFB0BBFF),
    ),
)

private val WORDMARK_STYLE = TextStyle(
    fontSize = 48.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 8.sp,
    brush = WORDMARK_BRUSH,
)

private val SUBTITLE_STYLE = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 6.sp,
    color = Color(0xFF9AA3D9),
)

private val TILE_LABEL_STYLE = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 2.sp,
    color = Color.White,
)

// --- Easing (pure functions of progress — no Compose animation clock) -------
// Match the original EaseOutBack (tiles) and EaseOutCubic (root fade) feel.

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val t = x - 1f
    return 1f + c3 * t * t * t + c1 * t * t
}

private fun easeOutCubic(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}

// --- One wall-clock timeline, tuned to the 1.94s app-open arpeggio ----------
//   tiles:    165ms stagger, 340ms each -> last tile lands ~1.0s
//   wordmark: last tile + 120ms beat, 380ms fade
//   subtitle: +140ms after the wordmark window, 300ms fade
//   then a 360ms hold and a 360ms fade so picture + sound resolve together.
private const val TILE_STAGGER_MS = 165f
private const val TILE_ANIM_MS = 340f
private const val WORDMARK_START_MS = (4 * 165f) + 340f + 120f          // 1120
private const val WORDMARK_MS = 380f
private const val SUBTITLE_START_MS = WORDMARK_START_MS + WORDMARK_MS + 140f // 1640
private const val SUBTITLE_MS = 300f
private const val FADE_START_MS = SUBTITLE_START_MS + SUBTITLE_MS + 360f // 2300
private const val FADE_MS = 360f
private const val SKIP_FADE_MS = 220f

private enum class TileKind { Movies, Anime, Tv, YouTube, Twitch }

private data class BrandTile(
    val kind: TileKind,
    val label: String,
    val color: Color,
)

private val TILES = listOf(
    BrandTile(TileKind.Movies, "MOVIES", Color(0xFFFFC107)),
    BrandTile(TileKind.Anime, "ANIME", Color(0xFFE51C23)),
    BrandTile(TileKind.Tv, "TV", Color(0xFF1E88E5)),
    BrandTile(TileKind.YouTube, "YOUTUBE", Color(0xFFFF0000)),
    BrandTile(TileKind.Twitch, "TWITCH", Color(0xFF9146FF)),
)
