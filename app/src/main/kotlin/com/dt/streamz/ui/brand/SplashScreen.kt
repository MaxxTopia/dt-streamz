package com.dt.streamz.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cold-start splash. Kept deliberately light so it stays smooth on the
 * VSeebox / SuperBox class of low-power Android TV boxes — earlier
 * versions ran an infinite tile-pulse loop, an infinite wordmark
 * shimmer, and a 64-particle fireworks layer concurrently, which made
 * the whole splash judder. Now: tiles slide in, wordmark fades in,
 * everything fades out. Total ~5s with a hold beat so all five tiles
 * land before the screen moves on. Press any key to skip.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit, onBegin: () -> Unit = {}) {
    val tiles = remember { TILES }
    val tileAnims = remember { List(tiles.size) { Animatable(0f) } }
    var wordmarkAlpha by remember { mutableStateOf(0f) }
    var subtitleAlpha by remember { mutableStateOf(0f) }
    val rootAlpha = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }
    val focusReq = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusReq.requestFocus() }
        // Fire the open sound in lockstep with the first animation frame so
        // the guitar and the tiles start together. The whole splash is then
        // choreographed to the 1.94s arpeggio:
        //   0 – ~1.0s  strum body  -> five tiles slide in, one per beat
        //   ~1.0 – 1.9s ring-out   -> wordmark + subtitle settle on the decay
        //   ~1.95s onward          -> fade as the note dies (~2.3s total)
        // Previously the animation ran ~4.6s while the guitar finished at
        // 1.9s, so the back half played in silence — that's the mismatch.
        onBegin()
        if (skipped) { onFinished(); return@LaunchedEffect }
        // Tile entry — staggered slide-in tuned so all five land within the
        // strum body (~1.0s). Snappier per-tile spec than before; the strum
        // carries the motion so it reads as deliberate, not rushed.
        tileAnims.forEachIndexed { index, anim ->
            launch {
                delay(index * TILE_STAGGER_MS)
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = TILE_ANIM_MS, easing = EaseOutBack),
                )
            }
        }
        // Last tile lands at (n-1)*stagger + anim; small beat, then the
        // wordmark rises on the guitar's sustain.
        delay(TILE_STAGGER_MS * (tiles.size - 1) + TILE_ANIM_MS + 120L)
        if (skipped) { onFinished(); return@LaunchedEffect }

        val wordmarkAnim = Animatable(0f)
        launch {
            wordmarkAnim.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        }
        val wmStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - wmStart < 380 && !skipped) {
            wordmarkAlpha = wordmarkAnim.value
            delay(16)
        }
        wordmarkAlpha = 1f

        delay(140)
        val subAnim = Animatable(0f)
        launch { subAnim.animateTo(1f, tween(300)) }
        val subStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - subStart < 300 && !skipped) {
            subtitleAlpha = subAnim.value
            delay(16)
        }
        subtitleAlpha = 1f

        // Hold on the guitar's ring-out, then fade out exactly as the note
        // dies so picture and sound resolve together.
        delay(360)
        rootAlpha.animateTo(0f, tween(durationMillis = 360, easing = EaseOutCubic))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(rootAlpha.value)
            .focusRequester(focusReq)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && !skipped) {
                    skipped = true
                    onFinished()
                    true
                } else false
            }
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF101226), Color(0xFF050610), Color.Black),
                    radius = 1400f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                tiles.forEachIndexed { index, tile ->
                    BrandTile(tile = tile, entryProgress = tileAnims[index].value)
                }
            }
            Spacer(Modifier.height(36.dp))
            Wordmark(alpha = wordmarkAlpha)
            Spacer(Modifier.height(14.dp))
            Text(
                text = "made by DT",
                modifier = Modifier.alpha(subtitleAlpha),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 6.sp,
                    color = Color(0xFF9AA3D9),
                ),
            )
        }
    }
}

@Composable
private fun BrandTile(tile: BrandTile, entryProgress: Float) {
    val tileScale = 0.7f + 0.3f * entryProgress
    Box(
        modifier = Modifier
            .size(width = 110.dp, height = 110.dp)
            .alpha(entryProgress)
            .scale(tileScale)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        tile.color.copy(alpha = 0.95f),
                        tile.color.copy(alpha = 0.55f),
                    ),
                ),
            ),
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
            Text(
                text = tile.label,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = Color.White,
                ),
            )
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
private fun Wordmark(alpha: Float) {
    // Static gradient — no infinite shimmer (was burning frames every
    // single recomposition of the splash, contributing to the lag).
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0xFFB0BBFF),
            0.5f to Color(0xFFFFFFFF),
            1f to Color(0xFFB0BBFF),
        ),
    )
    Text(
        text = "VIEWMAXXING",
        modifier = Modifier.alpha(alpha),
        style = TextStyle(
            fontSize = 48.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 8.sp,
            brush = brush,
        ),
    )
}

// Tile choreography, tuned to the 1.94s app-open arpeggio: five tiles land
// across the ~1.0s strum body (165ms apart, 340ms each → last lands ~1.0s).
private const val TILE_STAGGER_MS = 165L
private const val TILE_ANIM_MS = 340

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
