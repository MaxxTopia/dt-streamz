package com.dt.streamz.ui.genres

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.FavoriteEntry
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.BrowseCache
import com.dt.streamz.scraper.ProviderRegistry
import com.dt.streamz.scraper.vidsrc.VidSrcProvider
import com.dt.streamz.ui.home.PosterCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val GENRES = listOf("Action", "Comedy", "Drama", "Sci-Fi", "Thriller", "Horror", "Anime")

@Composable
fun GenresScreen(
    registry: ProviderRegistry,
    favorites: FavoritesStore? = null,
    continueWatching: ContinueWatchingStore? = null,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
) {
    val continueEntries by (continueWatching?.entries ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val favoriteEntries by (favorites?.entries ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val watchedKeys = remember(continueEntries) {
        continueEntries.map { "${it.providerId}:${it.titleId}" }.toSet()
    }
    val favoriteKeys = remember(favoriteEntries) {
        favoriteEntries.map { "${it.providerId}:${it.titleId}" }.toSet()
    }
    val scope = rememberCoroutineScope()
    val toggleFav: (SearchResult) -> Unit = { r ->
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

    var allResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    val vidSrc = registry.all.filterIsInstance<VidSrcProvider>().firstOrNull()

    LaunchedEffect(Unit) {
        allResults = registry.all.map { p ->
            async { runCatching { BrowseCache.browse(p) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Genres",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        GENRES.forEach { genre ->
            val titles = allResults.filter { result ->
                when (genre) {
                    "Anime" -> result.kind == MediaKind.Anime
                    else -> vidSrc != null && genre in vidSrc.genresFor(result.id)
                }
            }
            if (titles.isEmpty()) return@forEach
            Text(
                text = genre,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(titles, key = { "${it.providerId}:${it.id}" }) { item ->
                    PosterCard(
                        result = item,
                        watched = "${item.providerId}:${item.id}" in watchedKeys,
                        favorited = "${item.providerId}:${item.id}" in favoriteKeys,
                        onClick = { onOpenTitle(item.providerId, item.id) },
                        onToggleFavorite = { toggleFav(item) },
                    )
                }
            }
        }
    }
}
