package com.dt.streamz.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val DtDarkScheme = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.White,
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1A1A1E),
    onSurface = Color(0xFFEDEDED),
)

@Composable
fun DtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DtDarkScheme,
        content = content,
    )
}
