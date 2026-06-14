package com.dt.streamz.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * TV focus flair: when a card gains D-pad focus, it lifts slightly and casts
 * a colored glow (a colored elevation shadow) in the section's accent. Makes
 * the otherwise-flat grids feel alive and makes "where am I" obvious from
 * across the room. Both scale + glow are animated so focus movement glides.
 *
 * Colored shadows render on API 28+ (the box is API 30); on older devices it
 * degrades to a normal grey shadow — still a usable focus cue.
 */
@Composable
fun Modifier.focusGlow(
    focused: Boolean,
    color: Color = GlowCyan,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.06f,
    glow: androidx.compose.ui.unit.Dp = 22.dp,
): Modifier {
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    val elevation by animateDpAsState(if (focused) glow else 0.dp, label = "focusGlow")
    return this
        .scale(scale)
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = color,
            spotColor = color,
        )
}

/** Default glow accent — a bright cyan that reads as "selected" on dark UIs. */
val GlowCyan = Color(0xFF4FC3F7)
val GlowYouTube = Color(0xFFFF1A1A)
