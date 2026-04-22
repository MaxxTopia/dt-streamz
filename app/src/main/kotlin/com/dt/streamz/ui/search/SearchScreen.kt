package com.dt.streamz.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dt.streamz.data.SearchResult
import com.dt.streamz.scraper.ProviderRegistry

@Composable
fun SearchScreen(
    registry: ProviderRegistry,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(registry))
    val query by vm.query.collectAsState()
    val state by vm.state.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    val barFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SearchBarCard(
                query = query,
                onClick = { showEditor = true },
                modifier = Modifier.focusRequester(barFocus),
            )

            when (val s = state) {
                SearchState.Idle -> Hint("Press OK on the search bar above to start typing.")
                SearchState.Loading -> Hint("Searching…")
                is SearchState.Error -> Hint("Error: ${s.message}")
                is SearchState.Loaded -> ResultsGrid(
                    results = s.results,
                    firstItemFocus = firstResultFocus,
                    onOpen = onOpenTitle,
                )
            }
        }

        if (showEditor) {
            SearchEditorOverlay(
                initial = query,
                onQueryChange = vm::onQueryChange,
                onSubmit = {
                    vm.onSubmit()
                    showEditor = false
                },
                onDismiss = { showEditor = false },
            )
        }
    }

    // Initial focus claim so Search tab doesn't defer to TabRow.
    LaunchedEffect(Unit) {
        runCatching { barFocus.requestFocus() }
    }

    // When the editor closes, explicitly place focus inside Search so it can't
    // fall back to the TabRow (which would re-trigger Home's onFocus).
    LaunchedEffect(showEditor, state) {
        if (showEditor) return@LaunchedEffect
        val s = state
        if (s is SearchState.Loaded && s.results.isNotEmpty()) {
            runCatching { firstResultFocus.requestFocus() }
        } else {
            runCatching { barFocus.requestFocus() }
        }
    }
}

@Composable
private fun SearchBarCard(
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(0.6f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "🔍", style = MaterialTheme.typography.titleLarge)
            Text(
                text = query.ifBlank { "Search anime or movies" },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SearchEditorOverlay(
    initial: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tfFocus = remember { FocusRequester() }

    BackHandler(enabled = true) { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(24.dp),
        ) {
            OutlinedTextField(
                value = initial,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(tfFocus),
                singleLine = true,
                label = { androidx.compose.material3.Text("Search anime or movies") },
                placeholder = { androidx.compose.material3.Text("e.g. frieren, dune") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        runCatching { tfFocus.requestFocus() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
}

@Composable
private fun ResultsGrid(
    results: List<SearchResult>,
    firstItemFocus: FocusRequester,
    onOpen: (String, String) -> Unit,
) {
    if (results.isEmpty()) {
        Hint("No results.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(results) { index, result ->
            PosterCard(
                result = result,
                onClick = { onOpen(result.providerId, result.id) },
                modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun PosterCard(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
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
            }
        }
        Text(
            text = result.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            color = if (focused) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}
