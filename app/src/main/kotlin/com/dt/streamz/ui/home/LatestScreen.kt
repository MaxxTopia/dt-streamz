package com.dt.streamz.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.FavoriteEntry
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Clean feed of "Latest · <provider>" rows only — no dashboard widgets.
 * For when the user just wants to browse what's new.
 */
@Composable
fun LatestScreen(
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Latest",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        registry.all.forEach { provider ->
            BrowseRow(
                provider = provider,
                watchedKeys = watchedKeys,
                favoriteKeys = favoriteKeys,
                onOpenTitle = onOpenTitle,
                onToggleFavorite = toggleFav,
            )
        }
    }
}
