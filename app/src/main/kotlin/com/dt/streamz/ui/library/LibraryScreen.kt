package com.dt.streamz.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.FavoriteEntry
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.WatchEntry
import kotlinx.coroutines.launch

/**
 * Unified "My Library" — Continue Watching row on top, Favorites grid
 * below. Tap a favorite to open details, MENU on it to unfavorite.
 */
@Composable
fun LibraryScreen(
    continueWatching: ContinueWatchingStore,
    favorites: FavoritesStore,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
    onResume: (WatchEntry) -> Unit,
    onRemoveContinue: (WatchEntry) -> Unit,
) {
    val continueEntries by continueWatching.entries.collectAsState(initial = emptyList())
    val favoriteEntries by favorites.entries.collectAsState(initial = emptyList())
    var pendingRemoval by remember { mutableStateOf<WatchEntry?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "My Library",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (continueEntries.isEmpty() && favoriteEntries.isEmpty()) {
            Text(
                text = "Nothing saved yet — open a title and press MENU on the poster to favorite it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
            return
        }

        if (continueEntries.isNotEmpty()) {
            Text(
                text = "Continue watching  ·  MENU to remove",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowItems(continueEntries, key = { "${it.providerId}:${it.titleId}" }) { entry ->
                    ContinueTile(
                        entry = entry,
                        onClick = { onResume(entry) },
                        onRequestRemove = { pendingRemoval = entry },
                    )
                }
            }
        }

        if (favoriteEntries.isNotEmpty()) {
            Text(
                text = "Favorites  ·  MENU to unfavorite",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(favoriteEntries, key = { "${it.providerId}:${it.titleId}" }) { entry ->
                    FavoriteTile(
                        entry = entry,
                        onClick = { onOpenTitle(entry.providerId, entry.titleId) },
                        onToggle = { scope.launch { favorites.toggle(entry) } },
                    )
                }
            }
        }
    }

    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { androidx.compose.material3.Text("Remove from Continue Watching?") },
            text = { androidx.compose.material3.Text("${target.titleName} · Ep ${target.episodeNumber}") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveContinue(target); pendingRemoval = null
                }) { androidx.compose.material3.Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ContinueTile(
    entry: WatchEntry,
    onClick: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(200.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val menuKey = event.key == Key.Menu || event.key == Key.F10
                if (focused && menuKey && event.type == KeyEventType.KeyUp) {
                    onRequestRemove(); true
                } else false
            },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.width(200.dp).height(120.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.poster != null) {
                    AsyncImage(
                        model = entry.poster,
                        contentDescription = entry.titleName,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "▶ Ep ${entry.episodeNumber}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = entry.titleName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun FavoriteTile(
    entry: FavoriteEntry,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val menuKey = event.key == Key.Menu || event.key == Key.F10
                if (focused && menuKey && event.type == KeyEventType.KeyUp) {
                    onToggle(); true
                } else false
            },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, border, RoundedCornerShape(10.dp)),
            ) {
                if (entry.poster != null) {
                    AsyncImage(
                        model = entry.poster,
                        contentDescription = entry.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFD54F),
                    )
                }
            }
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}
