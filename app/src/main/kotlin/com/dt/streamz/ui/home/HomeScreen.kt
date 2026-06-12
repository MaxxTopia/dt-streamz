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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.runtime.collectAsState
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.FavoriteEntry
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.scraper.BrowseCache
import com.dt.streamz.scraper.Provider
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    title: String = "Home",
    registry: ProviderRegistry? = null,
    providerFilter: (Provider) -> Boolean = { it.supportsAnime },
    kindFilter: (MediaKind) -> Boolean = { true },
    // null on Home (show every kind); on Anime/Movies/TV we pass the
    // matching MediaKind so Continue Watching only shows that tab's content.
    // Entries persisted before kind was tracked have a null .kind and are
    // hidden from typed tabs — they still appear on Home.
    cwKind: MediaKind? = null,
    continueWatching: ContinueWatchingStore? = null,
    favorites: FavoritesStore? = null,
    onOpenTitle: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onResume: (WatchEntry) -> Unit = {},
    onRemoveContinue: (WatchEntry) -> Unit = {},
    // Show the TMDb-fed "Must Watch / trending" row. On for Home/Movies/TV,
    // off for Anime (trending is movies+TV, not anime).
    showMustWatch: Boolean = false,
) {
    val rawContinueEntries by (continueWatching?.entries ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val continueEntries = remember(rawContinueEntries, cwKind) {
        if (cwKind == null) rawContinueEntries
        else rawContinueEntries.filter { it.kind == cwKind.name }
    }
    val favoriteEntries by (favorites?.entries ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val favoriteKeys = remember(favoriteEntries) {
        favoriteEntries.map { "${it.providerId}:${it.titleId}" }.toSet()
    }
    val scope = rememberCoroutineScope()
    val toggleFavorite: (SearchResult) -> Unit = { r ->
        favorites?.let {
            scope.launch {
                it.toggle(
                    FavoriteEntry(
                        providerId = r.providerId,
                        titleId = r.id,
                        title = r.title,
                        poster = r.poster,
                        kind = r.kind.name,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
    var pendingRemoval by remember { mutableStateOf<WatchEntry?>(null) }
    // tmdb feeds only the dedicated Must-Watch row — keep it out of the
    // generic per-provider browse rows.
    val visibleProviders = registry?.all?.filter(providerFilter).orEmpty()
        .filter { it.id != "tmdb" }
    val mustWatchProvider = registry?.all?.firstOrNull { it.id == "tmdb" }
    val watchedKeys = remember(continueEntries) {
        continueEntries.map { "${it.providerId}:${it.titleId}" }.toSet()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (continueEntries.isNotEmpty()) {
            ContinueRow(
                entries = continueEntries,
                onResume = onResume,
                onRequestRemove = { pendingRemoval = it },
            )
        }
        if (showMustWatch && mustWatchProvider != null) {
            BrowseRow(
                provider = mustWatchProvider,
                watchedKeys = watchedKeys,
                favoriteKeys = favoriteKeys,
                kindFilter = kindFilter,
                titleOverride = "🔥 Must Watch · trending now",
                onOpenTitle = onOpenTitle,
                onToggleFavorite = toggleFavorite,
            )
        }
        RandomPickCard(
            providers = visibleProviders,
            onOpenTitle = onOpenTitle,
        )
        val recentProviders = remember(continueEntries, visibleProviders) {
            continueEntries
                .map { it.providerId }
                .distinct()
                .mapNotNull { pid -> visibleProviders.firstOrNull { it.id == pid } }
                .take(3)
        }
        recentProviders.forEach { provider ->
            BrowseRow(
                provider = provider,
                watchedKeys = watchedKeys,
                favoriteKeys = favoriteKeys,
                kindFilter = kindFilter,
                titleOverride = "Because you watched · ${provider.displayName}",
                onOpenTitle = onOpenTitle,
                onToggleFavorite = toggleFavorite,
            )
        }
        val otherProviders = visibleProviders.filter { p -> recentProviders.none { it.id == p.id } }
        otherProviders.forEach { provider ->
            BrowseRow(
                provider = provider,
                watchedKeys = watchedKeys,
                favoriteKeys = favoriteKeys,
                kindFilter = kindFilter,
                onOpenTitle = onOpenTitle,
                onToggleFavorite = toggleFavorite,
            )
        }
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
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            .width(168.dp)
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
                .width(168.dp)
                .height(100.dp),
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
            color = if (focused) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}

@Composable
internal fun BrowseRow(
    provider: Provider,
    watchedKeys: Set<String>,
    favoriteKeys: Set<String> = emptySet(),
    kindFilter: (MediaKind) -> Boolean = { true },
    titleOverride: String? = null,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
    onToggleFavorite: (SearchResult) -> Unit = {},
) {
    var results by remember(provider.id) { mutableStateOf<List<SearchResult>?>(null) }
    LaunchedEffect(provider.id) {
        results = runCatching { BrowseCache.browse(provider) }.getOrDefault(emptyList())
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
    val kinded = list.filter { kindFilter(it.kind) }
    val filtered = if (titleOverride != null) {
        kinded.filterNot { "${it.providerId}:${it.id}" in watchedKeys }
    } else kinded
    if (filtered.isEmpty()) return
    Text(
        text = titleOverride ?: "Latest · ${provider.displayName}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(filtered, key = { "${it.providerId}:${it.id}" }) { item ->
            PosterCard(
                result = item,
                watched = "${item.providerId}:${item.id}" in watchedKeys,
                favorited = "${item.providerId}:${item.id}" in favoriteKeys,
                onClick = { onOpenTitle(item.providerId, item.id) },
                onToggleFavorite = { onToggleFavorite(item) },
            )
        }
    }
}

private enum class RandomKind(val label: String, val matches: (MediaKind) -> Boolean) {
    Any("Any", { true }),
    Movie("Movie", { it == MediaKind.Movie }),
    TV("TV", { it == MediaKind.Series }),
    Anime("Anime", { it == MediaKind.Anime });

    fun next(): RandomKind = entries[(ordinal + 1) % entries.size]
}

@Composable
private fun RandomPickCard(
    providers: List<Provider>,
    onOpenTitle: (String, String) -> Unit,
) {
    var kind by remember { mutableStateOf(RandomKind.Any) }
    var loading by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun pickRandom() {
        if (loading || providers.isEmpty()) return
        loading = true
        scope.launch {
            val all = providers.map { p ->
                async { runCatching { BrowseCache.browse(p) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
            val pool = all.filter { kind.matches(it.kind) }
            loading = false
            val pick = pool.randomOrNull() ?: return@launch
            onOpenTitle(pick.providerId, pick.id)
        }
    }

    Surface(
        onClick = ::pickRandom,
        modifier = Modifier
            .width(232.dp)
            .height(120.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val menuKey = event.key == Key.Menu || event.key == Key.F10
                if (focused && menuKey && event.type == KeyEventType.KeyUp) {
                    kind = kind.next()
                    true
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (loading) "🎲 picking…" else "🎲 Random · ${kind.label}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "OK to pick · MENU to cycle kind",
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (focused) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
internal fun PosterCard(
    result: SearchResult,
    watched: Boolean,
    favorited: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(132.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val menuKey = event.key == Key.Menu || event.key == Key.F10
                if (focused && menuKey && event.type == KeyEventType.KeyUp) {
                    onToggleFavorite()
                    true
                } else false
            },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .width(132.dp)
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
                        style = MaterialTheme.typography.headlineSmall,
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
                if (favorited) {
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
        }
        Text(
            text = result.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            color = if (focused) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}

