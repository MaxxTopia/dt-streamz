package com.dt.streamz.ui.brand

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.updater.ApkInstaller
import com.dt.streamz.updater.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Top-right status chip — only renders when [update] is non-null. One click
 * fires the existing [ApkInstaller] flow; no manual Settings round-trip.
 */
@Composable
fun UpdateChip(update: UpdateChecker.Update?) {
    if (update == null) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }

    // Subtle pulse so it's noticeable but not flashing.
    val pulse = rememberInfiniteTransition(label = "update-chip-pulse")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "update-chip-phase",
    )
    val borderAlpha = 0.45f + 0.45f * phase

    Surface(
        onClick = {
            if (installing) return@Surface
            installing = true
            scope.launch {
                Toast.makeText(ctx, "Downloading ${update.tagName}…", Toast.LENGTH_SHORT).show()
                val ok = ApkInstaller.downloadAndInstall(ctx, update.apkUrl)
                if (!ok) {
                    Toast.makeText(
                        ctx,
                        "Install failed — check Settings for REQUEST_INSTALL_PACKAGES",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                installing = false
            }
        },
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1A2540),
            focusedContainerColor = Color(0xFF2E4080),
        ),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = Color(0xFF42E9C1).copy(alpha = if (focused) 1f else borderAlpha),
                    shape = RoundedCornerShape(6.dp),
                )
                .background(Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Tiny green dot to read as "fresh."
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF42E9C1))
                        .padding(3.dp),
                )
                Text(
                    text = if (installing) "Installing…" else "Update ${update.tagName}",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = Color.White,
                    ),
                )
            }
        }
    }
}
