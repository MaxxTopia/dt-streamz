package com.dt.streamz.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.data.FavoriteEntry
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.flow.flowOf
import com.dt.streamz.ui.theme.focusGlow
import com.dt.streamz.ui.onMenuKeyUp
import com.dt.streamz.ui.pointerClickable
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    registry: ProviderRegistry,
    favorites: FavoritesStore? = null,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
    // Scope key keeps each tab's search VM (and its query/results) separate and
    // activity-scoped, so they don't share state and each survives a trip into
    // the player. Default "all" = the global Search tab.
    scopeKey: String = "all",
    // Limits results to one kind (Anime tab -> anime only, etc.). Default = all.
    kindFilter: (MediaKind) -> Boolean = { true },
    placeholder: String = "🔍  Search anime, movies, TV…",
    // Shown when no search is active (Idle). The Anime/Movies/TV tabs pass their
    // browse rows here so the tab is "search bar on top, browse below" and only
    // swaps to scoped results while a query is running.
    idleContent: (@Composable () -> Unit)? = null,
) {
    val vm: SearchViewModel = viewModel(
        key = "search:$scopeKey",
        factory = SearchViewModel.Factory(registry, kindFilter),
    )
    val query by vm.query.collectAsState()
    val state by vm.state.collectAsState()
    var editorOpen by remember { mutableStateOf(false) }

    // Recent searches, persisted so the painful on-screen keyboard isn't
    // needed to re-run a past query. SharedPreferences keeps it simple;
    // historyTick re-reads after a write.
    val ctx = LocalContext.current
    val historyPrefs = remember { ctx.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE) }
    var historyTick by remember { mutableStateOf(0) }
    val recentSearches = remember(historyTick) { readSearchHistory(historyPrefs) }
    // Pin focus to the always-present search bar after a search runs.
    // Without this, clicking a recent/suggestion chip removed the chip's
    // container from composition, focus snapped UP to the TabRow, and its
    // onFocus handler silently switched you to the Movies tab.
    val searchBarFocus = remember { FocusRequester() }
    var pinFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pinFocus) {
        if (pinFocus) { kotlinx.coroutines.delay(60); runCatching { searchBarFocus.requestFocus() }; pinFocus = false }
    }
    fun runQuery(q: String) {
        // Grab focus onto the always-present search bar BEFORE we mutate
        // state. Submitting flips Idle -> Loading, which removes the focused
        // recent/suggestion chip from composition in the same frame; if focus
        // is still on that chip when it vanishes, Compose's fallback search
        // snaps focus UP to the TabRow and its Tab.onFocus switches you off
        // the Search tab (the "lands on a random Movies tab" bug). Moving
        // focus to the stable search bar first means there's nothing to
        // escape from. pinFocus below is a belt-and-suspenders re-grab.
        runCatching { searchBarFocus.requestFocus() }
        vm.onQueryChange(q); vm.onSubmit()
        writeSearchHistory(historyPrefs, q); historyTick++
        // Feed the on-device interest model so recommendations adapt.
        (ctx.applicationContext as? com.dt.streamz.DtApplication)?.interests?.recordSearch(q)
        pinFocus = true
    }
    val liveCount = (state as? SearchState.Loaded)?.results?.size
    val favoriteEntries by (favorites?.entries ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchBarCard(
            query = query,
            placeholder = placeholder,
            onClick = { editorOpen = true },
            focusRequester = searchBarFocus,
        )

        when (val s = state) {
            SearchState.Idle -> {
                if (idleContent != null) {
                    // Content tab (Anime/Movies/TV): show the browse rows under
                    // the search bar until the user actually searches.
                    Box(modifier = Modifier.fillMaxSize()) { idleContent() }
                } else {
                    Hint("Press OK on the search bar to type.")
                    if (recentSearches.isNotEmpty()) {
                        RecentSearches(
                            items = recentSearches,
                            onPick = { runQuery(it) },
                            onClear = { clearSearchHistory(historyPrefs); historyTick++ },
                        )
                    }
                }
            }
            SearchState.Loading -> Hint("Searching…")
            is SearchState.Error -> Hint("Error: ${s.message}")
            is SearchState.Loaded -> ResultsGrid(
                results = s.results,
                favoriteKeys = favoriteKeys,
                onOpen = onOpenTitle,
                onToggleFavorite = toggleFav,
            )
        }
    }

    if (editorOpen) {
        // Type-ahead suggestions from the movie/show provider (IMDB titles),
        // so the unified search bar behaves like YouTube's — a live dropdown
        // of real titles as you type.
        val suggestProvider = remember { registry.all.firstOrNull { it.supportsMovies } }
        SearchEditorDialog(
            initialQuery = query,
            liveResultCount = liveCount,
            onLiveQuery = { vm.onQueryChange(it) },   // search-as-you-type (VM debounces)
            onDismiss = { editorOpen = false },
            onSubmit = { text ->
                runQuery(text)
                editorOpen = false
            },
            suggestionsProvider = suggestProvider?.let { p -> { q -> p.suggest(q) } },
            recentSearches = recentSearches,
        )
    }
}

private const val HISTORY_KEY = "queries"
private const val HISTORY_MAX = 12

private fun readSearchHistory(prefs: android.content.SharedPreferences): List<String> =
    prefs.getString(HISTORY_KEY, "").orEmpty().split("\n").filter { it.isNotBlank() }

private fun writeSearchHistory(prefs: android.content.SharedPreferences, query: String) {
    val q = query.trim()
    if (q.length < 2) return
    val next = (listOf(q) + readSearchHistory(prefs).filterNot { it.equals(q, ignoreCase = true) })
        .take(HISTORY_MAX)
    prefs.edit().putString(HISTORY_KEY, next.joinToString("\n")).apply()
}

private fun clearSearchHistory(prefs: android.content.SharedPreferences) {
    prefs.edit().remove(HISTORY_KEY).apply()
}

@Composable
private fun RecentSearches(items: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Recent",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            RecentChip(label = "✕ Clear", onClick = onClear)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyRowItems(items) { q ->
                RecentChip(label = q, onClick = { onPick(q) })
            }
        }
    }
}

@Composable
private fun RecentChip(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (focused) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SearchBarCard(
    query: String,
    placeholder: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .height(44.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
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
                    color = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = query.ifBlank { placeholder },
                style = MaterialTheme.typography.bodyMedium,
                color = if (query.isBlank())
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * On-screen keyboard for TV input. We deliberately avoid any
 * system IME (Material `OutlinedTextField` + ImeAction.Search) because
 * on the SuperBox (Android 9) opening the IME locks the whole box up,
 * and on the VSeebox the IME shows but never delivers committed text
 * back to the field. A grid of D-pad-navigable letter cells sidesteps
 * the IME entirely — same approach Netflix / Prime / YouTube TV use.
 *
 * Layout: A-Z grid (6 cols), 0-9 row, special row (space/del/clear),
 * action row (Search/Close). First letter cell auto-focuses.
 */
@Composable
internal fun SearchEditorDialog(
    initialQuery: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    liveResultCount: Int? = null,
    onLiveQuery: (String) -> Unit = {},
    // Optional type-ahead source (YouTube tab passes the provider's
    // autocomplete). When null, no suggestion row renders.
    suggestionsProvider: (suspend (String) -> List<String>)? = null,
    // Previously-searched queries — shown as tappable chips before you start
    // typing so you don't have to peck the same thing out again on the TV.
    recentSearches: List<String> = emptyList(),
) {
    var text by remember { mutableStateOf(initialQuery) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val firstKeyFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { firstKeyFocus.requestFocus() }
    }
    // Search-as-you-type: push each change to the VM (which debounces) so
    // results are already warm when the dialog closes.
    LaunchedEffect(text) { onLiveQuery(text) }

    // Debounced autocomplete fetch. Re-keys on every keystroke; the 250ms
    // delay collapses bursts so we don't fire a request per letter.
    LaunchedEffect(text, suggestionsProvider) {
        val provider = suggestionsProvider
        val q = text.trim()
        if (provider == null || q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        suggestions = runCatching { provider(q) }.getOrDefault(emptyList())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .padding(16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Query + Search/Close on ONE top row so the confirm button is
                // always visible without scrolling past the keyboard on a TV.
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = if (text.isEmpty()) "type below…" else "$text|",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (text.isEmpty())
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    PrimaryActionCell(
                        label = "🔍  Search",
                        enabled = text.isNotBlank(),
                        onClick = { onSubmit(text.trim()) },
                        modifier = Modifier.width(132.dp),
                    )
                    PrimaryActionCell(
                        label = "Close",
                        enabled = true,
                        onClick = onDismiss,
                        modifier = Modifier.width(96.dp),
                    )
                }

                // Suggestions / live count sit directly under the query — near
                // the top so they stay on-screen (they were getting pushed below
                // the fold under the keyboard before).
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = "Suggestions · OK to search",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lazyRowItems(suggestions) { s ->
                            SuggestionChip(label = s, onClick = { onSubmit(s) })
                        }
                    }
                } else if (text.isBlank() && recentSearches.isNotEmpty()) {
                    Text(
                        text = "Recent · OK to search",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lazyRowItems(recentSearches) { s ->
                            SuggestionChip(label = s, onClick = { onSubmit(s) })
                        }
                    }
                } else if (liveResultCount != null && text.trim().length >= 2) {
                    Text(
                        text = "🔎 $liveResultCount result${if (liveResultCount == 1) "" else "s"} so far",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Letters: 6-col grid, A-Z (26 cells, 4 rows of 6 + 1
                // row of 2). First cell carries the auto-focus requester.
                LETTER_ROWS.forEachIndexed { rowIdx, row ->
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { ch ->
                            KeyCell(
                                label = ch.toString(),
                                onClick = { text = text + ch },
                                focusRequester = if (rowIdx == 0 && ch == 'A') firstKeyFocus else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad short rows so widths stay consistent.
                        repeat(6 - row.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 0-9 row.
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (n in '0'..'9') {
                        KeyCell(
                            label = n.toString(),
                            onClick = { text = text + n },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Edit-action row.
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KeyCell(
                        label = "SPACE",
                        onClick = { text = text + " " },
                        modifier = Modifier.weight(2f),
                    )
                    KeyCell(
                        label = "DEL",
                        onClick = { text = text.dropLast(1) },
                        modifier = Modifier.weight(1f),
                    )
                    KeyCell(
                        label = "CLEAR",
                        onClick = { text = "" },
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "D-pad to move · OK to type · BACK to close · Search/Close are up top",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

private val LETTER_ROWS: List<List<Char>> = listOf(
    listOf('A', 'B', 'C', 'D', 'E', 'F'),
    listOf('G', 'H', 'I', 'J', 'K', 'L'),
    listOf('M', 'N', 'O', 'P', 'Q', 'R'),
    listOf('S', 'T', 'U', 'V', 'W', 'X'),
    listOf('Y', 'Z'),
)

@Composable
private fun KeyCell(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(44.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (focused) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PrimaryActionCell(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .height(50.dp)
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable { if (enabled) onClick() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused && enabled) MaterialTheme.colorScheme.onPrimary
                else if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
    )
}

@Composable
private fun ResultsGrid(
    results: List<SearchResult>,
    favoriteKeys: Set<String>,
    onOpen: (String, String) -> Unit,
    onToggleFavorite: (SearchResult) -> Unit,
) {
    if (results.isEmpty()) {
        Hint("No results.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { "${it.providerId}:${it.id}" }) { result ->
            PosterCard(
                result = result,
                favorited = "${result.providerId}:${result.id}" in favoriteKeys,
                onClick = { onOpen(result.providerId, result.id) },
                onToggleFavorite = { onToggleFavorite(result) },
            )
        }
    }
}

@Composable
private fun PosterCard(
    result: SearchResult,
    favorited: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .onMenuKeyUp(focused, onToggleFavorite)
            // Air-mouse cursor click support — the TV Surface below only fires
            // on D-pad CENTER, so search-result cards were dead to the mouse.
            .pointerClickable(onClick),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusGlow(focused),
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
                    model = result.poster,
                    title = result.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                )
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
