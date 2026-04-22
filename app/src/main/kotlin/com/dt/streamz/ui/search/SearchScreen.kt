package com.dt.streamz.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth(0.6f),
            singleLine = true,
            label = { androidx.compose.material3.Text("Search anime or movies") },
            placeholder = { androidx.compose.material3.Text("e.g. frieren, dune") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.onSubmit() }),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            ),
        )

        when (val s = state) {
            SearchState.Idle -> Hint("Type at least 2 characters.")
            SearchState.Loading -> Hint("Searching…")
            is SearchState.Error -> Hint("Error: ${s.message}")
            is SearchState.Loaded -> ResultsGrid(
                results = s.results,
                onOpen = onOpenTitle,
            )
        }
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
        items(results, key = { "${it.providerId}:${it.id}" }) { result ->
            PosterCard(result = result, onClick = { onOpen(result.providerId, result.id) })
        }
    }
}

@Composable
private fun PosterCard(result: SearchResult, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Color.White else Color.Transparent
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
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
