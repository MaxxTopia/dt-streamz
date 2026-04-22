package com.dt.streamz.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun HomeScreen(title: String = "Home", onPlayTestStream: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Scraper rows land in Phase 3. In the meantime:",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        PlayTestStreamCard(onClick = onPlayTestStream)
    }
}

@Composable
private fun PlayTestStreamCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(360.dp)
            .height(200.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(if (focused) Color.White else MaterialTheme.colorScheme.primary)
                        .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(36.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "▶",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                Text(
                    text = "Play test stream",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (focused) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
