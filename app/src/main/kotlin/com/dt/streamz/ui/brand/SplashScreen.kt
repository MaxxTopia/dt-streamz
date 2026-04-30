package com.dt.streamz.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cold-start splash — runs once before [com.dt.streamz.ui.DtApp] takes over.
 *
 * Sequence (~2.6s total):
 *   0-100ms  black hold
 *   100-1100 4 brand tiles slide in from below + fade in, staggered 120ms
 *   1100-1400 tiles settle, soft glow pulse begins
 *   1400-2200 "VIEWMAXXING" wordmark + "made by DT" reveal
 *   2200-2600 whole splash fades to black, [onFinished] fires
 *
 * Visuals are pure Compose (Canvas + drawBehind) so no asset pipeline.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val tiles = remember { TILES }
    val tileAnims = remember { List(tiles.size) { Animatable(0f) } }
    var wordmarkAlpha by remember { mutableStateOf(0f) }
    var subtitleAlpha by remember { mutableStateOf(0f) }
    val rootAlpha = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }
    val focusReq = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusReq.requestFocus() }
        delay(100)
        if (skipped) { onFinished(); return@LaunchedEffect }
        // Stagger tile entries — each tile animates 0..1 over 700ms with
        // 120ms offset so the row reads as a wave, not a popcorn burst.
        tileAnims.forEachIndexed { index, anim ->
            launch {
                delay(index * 120L)
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 700, easing = EaseOutBack),
                )
            }
        }
        delay(1100)
        // Wordmark + subtitle fade-in, sequenced.
        val wordmarkAnim = Animatable(0f)
        launch {
            wordmarkAnim.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        }
        // Drive state from animation each frame via a snapshotFlow-free
        // pattern: poll inside this coroutine. The duration is short so the
        // few extra recomps are cheap.
        val wmStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - wmStart < 450) {
            wordmarkAlpha = wordmarkAnim.value
            delay(16)
        }
        wordmarkAlpha = 1f

        delay(250)
        val subAnim = Animatable(0f)
        launch { subAnim.animateTo(1f, tween(400, easing = LinearEasing)) }
        val subStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - subStart < 400) {
            subtitleAlpha = subAnim.value
            delay(16)
        }
        subtitleAlpha = 1f

        delay(550)
        rootAlpha.animateTo(0f, tween(durationMillis = 400, easing = EaseOutCubic))
        onFinished()
    }

    val pulse = rememberInfiniteTransition(label = "splash-pulse")
    val pulsePhase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-phase",
    )

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
                    val t = tileAnims[index].value
                    BrandTile(
                        tile = tile,
                        entryProgress = t,
                        pulse = pulsePhase,
                    )
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
        Text(
            text = "press any key to skip",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .alpha(0.35f),
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.sp,
                color = Color(0xFF7782B5),
            ),
        )
    }
}

@Composable
private fun BrandTile(tile: BrandTile, entryProgress: Float, pulse: Float) {
    // entryProgress drives scale (0.6→1.0) + alpha (0→1) — EaseOutBack
    // gives the tile a small overshoot for spring feel. pulse is a steady
    // 0..1..0 used for outer glow.
    val tileScale = 0.6f + 0.4f * entryProgress
    val glowAlpha = 0.18f + 0.22f * pulse
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
            // Outer glow ring — pulse makes the row "breathe."
            drawCircle(
                color = tile.color.copy(alpha = glowAlpha),
                radius = size.minDimension * 0.55f,
                style = Stroke(width = 6f),
            )
            // Translate the icon glyph to a clean inner box.
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

/**
 * Each tile draws a custom icon glyph via Canvas — no icon-set dependency.
 * Glyphs are simplified silhouettes that read well at 110dp.
 */
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
    // White play triangle inside a slightly inset rounded rect — the
    // outer tile color already reads "YouTube red," so just need the
    // recognizable play-button silhouette.
    val pad = s.width * 0.05f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(o.x + pad, o.y + s.height * 0.20f),
        size = Size(s.width - pad * 2, s.height * 0.55f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.12f, s.width * 0.12f),
    )
    val cx = o.x + s.width / 2f
    val cy = o.y + s.height * 0.475f
    val tri = Path().apply {
        moveTo(cx - s.width * 0.10f, cy - s.height * 0.13f)
        lineTo(cx + s.width * 0.14f, cy)
        lineTo(cx - s.width * 0.10f, cy + s.height * 0.13f)
        close()
    }
    drawPath(tri, color = Color(0xFFFF0000))
}

private fun DrawScope.drawMoviesGlyph(o: Offset, s: Size) {
    // Filmstrip: rounded rect with 4 sprocket holes top and bottom.
    val white = Color.White
    val stripTop = o.y + s.height * 0.2f
    val stripBottom = o.y + s.height * 0.65f
    drawRoundRect(
        color = white,
        topLeft = Offset(o.x + s.width * 0.05f, stripTop),
        size = Size(s.width * 0.9f, stripBottom - stripTop),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
    )
    val holeRadius = s.width * 0.05f
    val holeY = (stripTop + stripBottom) / 2f
    for (i in 0..3) {
        val cx = o.x + s.width * (0.18f + 0.21f * i)
        drawCircle(
            color = Color(0xFF000000),
            radius = holeRadius,
            center = Offset(cx, holeY),
        )
    }
}

private fun DrawScope.drawAnimeGlyph(o: Offset, s: Size) {
    // Stylized 4-point sparkle — the "anime sparkle" eye-flash trope. Two
    // crossed elongated diamonds + a small center bead.
    val cx = o.x + s.width / 2f
    val cy = o.y + s.height / 2f
    val long = s.width * 0.45f
    val short = s.width * 0.10f
    val white = Color.White

    val vertical = Path().apply {
        moveTo(cx, cy - long)
        lineTo(cx + short, cy)
        lineTo(cx, cy + long)
        lineTo(cx - short, cy)
        close()
    }
    val horizontal = Path().apply {
        moveTo(cx - long, cy)
        lineTo(cx, cy - short)
        lineTo(cx + long, cy)
        lineTo(cx, cy + short)
        close()
    }
    drawPath(vertical, color = white)
    drawPath(horizontal, color = white)
    drawCircle(
        color = Color(0xFFFFE8EC),
        radius = s.width * 0.06f,
        center = Offset(cx, cy),
    )
}

private fun DrawScope.drawTvGlyph(o: Offset, s: Size) {
    // CRT silhouette: two antennae lines + rounded rect screen.
    val white = Color.White
    val antennaTopY = o.y + s.height * 0.05f
    val screenTop = o.y + s.height * 0.30f
    val screenLeft = o.x + s.width * 0.1f
    val screenSize = Size(s.width * 0.8f, s.height * 0.55f)

    val cx = o.x + s.width / 2f
    drawLine(
        color = white,
        start = Offset(cx - 2f, screenTop),
        end = Offset(cx - s.width * 0.18f, antennaTopY),
        strokeWidth = 5f,
    )
    drawLine(
        color = white,
        start = Offset(cx + 2f, screenTop),
        end = Offset(cx + s.width * 0.18f, antennaTopY),
        strokeWidth = 5f,
    )
    drawRoundRect(
        color = white,
        topLeft = Offset(screenLeft, screenTop),
        size = screenSize,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
    )
    // Inner darker rect to read as a screen, not a brick.
    drawRoundRect(
        color = Color(0xFF1565C0),
        topLeft = Offset(screenLeft + 6f, screenTop + 6f),
        size = Size(screenSize.width - 12f, screenSize.height - 12f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
    )
}

private fun DrawScope.drawTwitchGlyph(o: Offset, s: Size) {
    // Twitch's "glitch" silhouette — house-with-notch shape + two interior
    // stripes for the eyes. Approximated rather than pixel-exact, but
    // recognizable on the splash.
    val white = Color.White
    val left = o.x + s.width * 0.18f
    val right = o.x + s.width * 0.82f
    val top = o.y + s.height * 0.10f
    val bottom = o.y + s.height * 0.85f
    val stepX = s.width * 0.14f
    val stepY = s.height * 0.14f
    val mark = Path().apply {
        moveTo(left, top + stepY)
        lineTo(left + stepX * 0.7f, top)
        lineTo(right, top)
        lineTo(right, bottom - stepY * 1.6f)
        lineTo(right - stepX * 0.9f, bottom - stepY * 0.7f)
        lineTo(right - stepX * 1.7f, bottom - stepY * 0.7f)
        lineTo(right - stepX * 2.4f, bottom)
        lineTo(right - stepX * 2.9f, bottom)
        lineTo(right - stepX * 2.9f, bottom - stepY * 0.7f)
        lineTo(left, bottom - stepY * 0.7f)
        close()
    }
    drawPath(mark, color = white)
    // Two eye stripes (vertical mini-rects)
    drawRect(
        color = Color(0xFF6A2BD8),
        topLeft = Offset(left + stepX * 0.95f, top + stepY * 1.5f),
        size = Size(stepX * 0.45f, stepY * 1.6f),
    )
    drawRect(
        color = Color(0xFF6A2BD8),
        topLeft = Offset(left + stepX * 2.25f, top + stepY * 1.5f),
        size = Size(stepX * 0.45f, stepY * 1.6f),
    )
}

@Composable
private fun Wordmark(alpha: Float) {
    val shimmer = rememberInfiniteTransition(label = "wordmark-shimmer")
    val phase by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wordmark-phase",
    )
    val sweepWidth = 800f
    val startX = -sweepWidth + phase * (sweepWidth * 2.5f)
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0xFFB0BBFF),
            0.5f to Color(0xFFFFFFFF),
            1f to Color(0xFFB0BBFF),
        ),
        start = Offset(startX, 0f),
        end = Offset(startX + sweepWidth, 0f),
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

