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
import androidx.compose.runtime.mutableStateListOf
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

    // Firework state — list of in-flight particles + a frame-tick counter
    // that forces recomposition every ~16ms while bursts are alive.
    val particles = remember { mutableStateListOf<FireParticle>() }
    var frameTick by remember { mutableStateOf(0L) }

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
        // Fireworks: 4 staggered bursts radiating from random points in the
        // upper half of the screen. Triggers right as the wordmark begins
        // to fade in — celebratory but bounded. Frame ticker drives Canvas
        // recomposition for the integration loop.
        val rng = kotlin.random.Random(System.currentTimeMillis())
        launch {
            val tickerStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - tickerStart < 1200L) {
                frameTick = System.currentTimeMillis()
                delay(16)
            }
            frameTick = System.currentTimeMillis()
        }
        launch {
            val burstOffsets = listOf(0L, 180L, 360L, 540L)
            for (off in burstOffsets) {
                delay(off)
                val cx = 0.20f + rng.nextFloat() * 0.60f
                val cy = 0.18f + rng.nextFloat() * 0.30f
                particles.addAll(
                    spawnBurst(cx, cy, System.currentTimeMillis(), rng),
                )
            }
        }
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
        // Fireworks layer — draws behind the brand column. Reads frameTick
        // so it recomposes each tick while bursts are alive; once particles
        // is empty the canvas is effectively a no-op.
        if (particles.isNotEmpty()) {
            FireworksLayer(particles = particles, frameTick = frameTick)
        }
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
    // entryProgress drives scale (0.7→1.0) + alpha (0→1) — EaseOutBack
    // gives the tile a small overshoot for spring feel. pulse is a steady
    // 0..1..0 used for outer glow. Glow alpha cut roughly in half from
    // the original loud version so the row breathes instead of throbbing.
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
            drawCircle(
                color = tile.color.copy(alpha = glowAlpha),
                radius = size.minDimension * 0.55f,
                style = Stroke(width = 6f),
            )
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
    // Rounded white pill with a centered red play-triangle. Antialiased
    // path-only — no chunky right-angle corners.
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
    // Filmstrip: rounded white rect with paired sprocket holes top + bottom.
    // Holes drawn as cutouts via rounded rects so corners read smoothly at
    // 110dp. Two rows + four columns reads more "film" than the prior
    // single-row strip.
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
    val tint = Color(0xFF8A6800)  // tile color punched through
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
    // 4-point sparkle with a soft halo + tighter waist for a smoother
    // "anime eye-flash" silhouette. Bezier curves on each petal so the
    // diamond points don't read as jagged at small sizes.
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
    // Soft halo for sparkle bloom
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
    // CRT silhouette with soft round-cap antennae + rounded screen + small
    // base. Stroke caps + joins set to round so antennae don't end in
    // hard pixels.
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

    // Outer bezel
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenW, screenH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.10f, s.width * 0.10f),
    )
    // Inner glassy screen
    val inset = s.width * 0.05f
    drawRoundRect(
        color = Color(0xFF0D47A1),
        topLeft = Offset(screenLeft + inset, screenTop + inset),
        size = Size(screenW - inset * 2, screenH - inset * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s.width * 0.07f, s.width * 0.07f),
    )
    // Soft scanline highlight for that CRT feel
    drawRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(screenLeft + inset, screenTop + inset + (screenH - inset * 2) * 0.18f),
        size = Size(screenW - inset * 2, (screenH - inset * 2) * 0.10f),
    )
    // Tiny base feet
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
    // Cleaner glitch-mark silhouette + two rounded "eye" pills. Slight
    // softening on the bevel angle so the chamfer reads at 110dp.
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

@Composable
private fun FireworksLayer(particles: List<FireParticle>, frameTick: Long) {
    // We integrate each particle's position from (vel * elapsed) plus a
    // small downward bias so they arc instead of stay on the spawn ring.
    // Origin coords are normalized 0..1 in the splash size; multiply
    // back out per-frame against the canvas size.
    val gravity = 0.0012f  // px/ms²-ish, in normalized units
    val now = frameTick.takeIf { it != 0L } ?: System.currentTimeMillis()
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (p in particles) {
            val elapsed = (now - p.bornAt).coerceAtLeast(0L)
            if (elapsed > p.lifeMs) continue
            val t = elapsed.toFloat() / p.lifeMs.toFloat()
            // Position integration in screen space. velX/velY are in
            // arbitrary units; we treat them as fraction-of-width per
            // 100ms to keep things resolution-independent.
            val px = p.originX * size.width + (p.velX * elapsed * 0.0008f) * size.width
            val py = p.originY * size.height + (p.velY * elapsed * 0.0008f) * size.height +
                gravity * elapsed * elapsed * size.height * 0.0008f
            val fade = 1f - t
            val r = p.sizeDp * (0.7f + (1f - t) * 0.5f)
            // Soft halo
            drawCircle(
                color = p.color.copy(alpha = (fade * 0.30f)),
                radius = r * 2.4f,
                center = Offset(px, py),
            )
            // Core
            drawCircle(
                color = p.color.copy(alpha = fade.coerceIn(0f, 1f)),
                radius = r,
                center = Offset(px, py),
            )
        }
    }
}

/**
 * Single emitted particle. We keep this a plain data class with mutable
 * fields rather than animatables since each splash spawns at most ~64
 * particles and lifetimes are sub-second — driving each via Animatable
 * would be far heavier than per-frame integration.
 */
private data class FireParticle(
    val originX: Float,
    val originY: Float,
    val velX: Float,
    val velY: Float,
    val color: Color,
    val bornAt: Long,
    val lifeMs: Long,
    val sizeDp: Float,
)

private val FIREWORK_PALETTE = listOf(
    Color(0xFFFFD54F),
    Color(0xFFFF7043),
    Color(0xFF42A5F5),
    Color(0xFFE91E63),
    Color(0xFF66BB6A),
    Color(0xFFAB47BC),
)

private fun spawnBurst(
    cx: Float,
    cy: Float,
    now: Long,
    rng: kotlin.random.Random,
): List<FireParticle> {
    val count = 16
    val color = FIREWORK_PALETTE[rng.nextInt(FIREWORK_PALETTE.size)]
    val baseSpeed = rng.nextFloat() * 2.0f + 4.0f  // px/ms baseline
    return List(count) { i ->
        val angle = (i.toFloat() / count) * (2.0 * Math.PI).toFloat() +
            (rng.nextFloat() - 0.5f) * 0.18f
        val speed = baseSpeed * (0.7f + rng.nextFloat() * 0.6f)
        FireParticle(
            originX = cx,
            originY = cy,
            velX = kotlin.math.cos(angle) * speed,
            velY = kotlin.math.sin(angle) * speed,
            color = color,
            bornAt = now,
            lifeMs = 700L + rng.nextLong(0, 250L),
            sizeDp = 2.4f + rng.nextFloat() * 1.6f,
        )
    }
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

