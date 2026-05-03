package com.dt.streamz.ui.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil3.compose.AsyncImage
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.Provider
import com.dt.streamz.scraper.ProviderRegistry
import com.dt.streamz.ui.search.SearchEditorDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * YouTube tab — search-first instead of browse-first. Top row is a search
 * bar; results render as a grid below. With no query, the screen falls
 * back to the YouTube provider's trending feed (browse()), so the tab is
 * never empty.
 */
@Composable
fun YouTubeTabScreen(
    registry: ProviderRegistry,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
) {
    val provider = remember { registry.all.firstOrNull { it.supportsYouTube } }
    if (provider == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "YouTube provider unavailable.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }

    var query by remember { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var trending by remember { mutableStateOf<List<SearchResult>?>(null) }
    var results by remember { mutableStateOf<List<SearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(provider.id) {
        // 5s cap — NewPipeExtractor on Android 9 can hang for tens of
        // seconds on filtered networks while it retries YouTube's
        // anti-bot endpoints. Fail fast and let the user search instead.
        trending = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { provider.browse() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // Debounced live search — typing in the dialog will pre-warm results
    // before submit, but we also re-fire on submit to be safe.
    fun runSearch(q: String) {
        searchJob?.cancel()
        if (q.length < 2) {
            results = null
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            delay(300)
            val out = runCatching { provider.search(q) }.getOrDefault(emptyList())
            results = out
            searching = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            YouTubeSearchBar(
                query = query,
                onClick = { editorOpen = true },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotBlank()) {
                ClearButton(onClick = {
                    query = ""
                    results = null
                    searching = false
                    searchJob?.cancel()
                })
            }
        }

        when {
            query.isBlank() -> {
                val list = trending
                Text(
                    text = "Trending on YouTube",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                when {
                    list == null -> Hint("Loading trending…")
                    list.isEmpty() -> Hint(
                        "Trending unavailable on this network — open the search bar above to look up a video by name.",
                    )
                    else -> ResultsGrid(list, onOpenTitle)
                }
            }
            searching && results == null -> Hint("Searching YouTube for \"$query\"…")
            else -> {
                val r = results
                Text(
                    text = "Results for \"$query\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                when {
                    r == null -> Hint("Searching…")
                    r.isEmpty() -> Hint("No results.")
                    else -> ResultsGrid(r, onOpenTitle)
                }
            }
        }
    }

    if (editorOpen) {
        SearchEditorDialog(
            initialQuery = query,
            onDismiss = { editorOpen = false },
            onSubmit = { text ->
                query = text
                editorOpen = false
                runSearch(text)
            },
        )
    }
}

@Composable
private fun YouTubeSearchBar(
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color(0xFFFF0000)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Box(modifier = Modifier.width(10.dp))
                Text(
                    text = query.ifBlank { "Search YouTube…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (query.isBlank())
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color(0xFFFF0000)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Clear",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
}

@Composable
private fun ResultsGrid(
    results: List<SearchResult>,
    onOpen: (String, String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { "${it.providerId}:${it.id}" }) { result ->
            VideoCard(
                result = result,
                onClick = { onOpen(result.providerId, result.id) },
            )
        }
    }
}

@Composable
private fun VideoCard(
    result: SearchResult,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (focused) 2.dp else 0.dp,
                        color = if (focused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (result.poster != null) {
                    AsyncImage(
                        model = result.poster,
                        contentDescription = result.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        Text(
            text = result.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            color = if (focused) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}
