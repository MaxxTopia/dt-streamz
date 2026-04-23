package com.dt.streamz.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.runtime.collectAsState
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.scraper.Provider
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.flow.flowOf

@Composable
fun HomeScreen(
    title: String = "Home",
    registry: ProviderRegistry? = null,
    providerFilter: (Provider) -> Boolean = { it.supportsAnime },
    continueWatching: ContinueWatchingStore? = null,
    onOpenTitle: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onResume: (WatchEntry) -> Unit = {},
    onRemoveContinue: (WatchEntry) -> Unit = {},
    onPlayTestStream: () -> Unit = {},
) {
    val continueEntries by (continueWatching?.entries ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    var pendingRemoval by remember { mutableStateOf<WatchEntry?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (continueEntries.isNotEmpty()) {
            ContinueRow(
                entries = continueEntries,
                onResume = onResume,
                onRequestRemove = { pendingRemoval = it },
            )
        }
        val watchedKeys = remember(continueEntries) {
            continueEntries.map { "${it.providerId}:${it.titleId}" }.toSet()
        }
        registry?.all?.filter(providerFilter)?.forEach { provider ->
            BrowseRow(
                provider = provider,
                watchedKeys = watchedKeys,
                onOpenTitle = onOpenTitle,
            )
        }
        PlayTestStreamCard(onClick = onPlayTestStream)
    }

    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { androidx.compose.material3.Text("Remove from Continue Watching?") },
            text = {
                androidx.compose.material3.Text(
                    "${target.titleName} · Ep ${target.episodeNumber}",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveContinue(target)
                    pendingRemoval = null
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
private fun ContinueRow(
    entries: List<WatchEntry>,
    onResume: (WatchEntry) -> Unit,
    onRequestRemove: (WatchEntry) -> Unit,
) {
    Text(
        text = "Continue watching  ·  Press MENU to remove",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(entries, key = { "${it.providerId}:${it.titleId}" }) { entry ->
            ContinueCard(
                entry = entry,
                onClick = { onResume(entry) },
                onRequestRemove = { onRequestRemove(entry) },
            )
        }
    }
}

@Composable
private fun ContinueCard(
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
                    onRequestRemove()
                    true
                } else false
            },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .width(200.dp)
                .height(120.dp),
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
            color = if (focused) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun BrowseRow(
    provider: Provider,
    watchedKeys: Set<String>,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
) {
    var results by remember(provider.id) { mutableStateOf<List<SearchResult>?>(null) }
    LaunchedEffect(provider.id) {
        results = runCatching { provider.browse() }.getOrDefault(emptyList())
    }
    val list = results
    if (list == null) {
        Text(
            text = "Loading ${provider.displayName}…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        return
    }
    if (list.isEmpty()) return
    Text(
        text = "Latest · ${provider.displayName}",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(list, key = { "${it.providerId}:${it.id}" }) { item ->
            PosterCard(
                result = item,
                watched = "${item.providerId}:${item.id}" in watchedKeys,
                onClick = { onOpenTitle(item.providerId, item.id) },
            )
        }
    }
}

@Composable
private fun PosterCard(result: SearchResult, watched: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(160.dp)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .width(160.dp)
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
                contentAlignment = Alignment.Center,
            ) {
                if (result.poster != null) {
                    AsyncImage(
                        model = result.poster,
                        contentDescription = result.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Text(
                        text = result.title.take(2).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                    )
                }
                if (watched) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "✓ WATCHED",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        Text(
            text = result.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            color = if (focused) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun PlayTestStreamCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(320.dp)
            .height(160.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "▶  Play test stream",
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
