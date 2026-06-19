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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.FavoriteEntry
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.ui.onMenuKeyUp
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
    var menuEntry by remember { mutableStateOf<WatchEntry?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "My Library",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (continueEntries.isEmpty() && favoriteEntries.isEmpty()) {
            Text(
                text = "Nothing saved yet — open a title and press MENU on the poster to favorite it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            return
        }

        if (continueEntries.isNotEmpty()) {
            Text(
                text = "Continue watching  ·  MENU to remove",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems(continueEntries, key = { "${it.providerId}:${it.titleId}" }) { entry ->
                    ContinueTile(
                        entry = entry,
                        onClick = { menuEntry = entry },
                        onRequestRemove = { pendingRemoval = entry },
                    )
                }
            }
        }

        if (favoriteEntries.isNotEmpty()) {
            Text(
                text = "Favorites  ·  MENU to unfavorite",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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

    menuEntry?.let { target ->
        com.dt.streamz.ui.home.ContinueOptionsDialog(
            entry = target,
            onResume = { menuEntry = null; onResume(target) },
            onEpisodes = { menuEntry = null; onOpenTitle(target.providerId, target.titleId) },
            onRemove = { menuEntry = null; pendingRemoval = target },
            onDismiss = { menuEntry = null },
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
            .width(168.dp)
            .onFocusChanged { focused = it.isFocused }
            .onMenuKeyUp(focused, onRequestRemove),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.width(168.dp).height(100.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                com.dt.streamz.ui.components.PosterImage(
                    model = entry.poster,
                    title = entry.titleName,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "▶ Ep ${entry.episodeNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
                if (entry.durationMs > 0) {
                    val frac = (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac)
                                .height(4.dp)
                                .background(Color(0xFFE51C23)),
                        )
                    }
                }
            }
        }
        Text(
            text = entry.titleName,
            style = MaterialTheme.typography.labelMedium,
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
            .onMenuKeyUp(focused, onToggle),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(198.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, border, RoundedCornerShape(10.dp)),
            ) {
                com.dt.streamz.ui.components.PosterImage(
                    model = entry.poster,
                    title = entry.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                )
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
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}
