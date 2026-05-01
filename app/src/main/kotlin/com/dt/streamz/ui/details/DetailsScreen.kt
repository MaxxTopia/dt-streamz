package com.dt.streamz.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dt.streamz.DtApplication
import com.dt.streamz.data.Episode
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.scraper.ProviderRegistry
import kotlinx.coroutines.flow.flowOf

@Composable
fun DetailsScreen(
    registry: ProviderRegistry,
    providerId: String,
    titleId: String,
    onPlayEpisode: (
        titleId: String,
        episode: Episode,
        providerId: String,
        titleName: String,
        poster: String?,
    ) -> Unit,
) {
    val vm: DetailsViewModel = viewModel(
        key = "$providerId:$titleId",
        factory = DetailsViewModel.Factory(registry, providerId, titleId),
    )
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val cwFlow = (ctx.applicationContext as? DtApplication)?.continueWatching?.entries
        ?: flowOf(emptyList())
    val cwEntries by cwFlow.collectAsState(initial = emptyList())
    val watchedNumbers = remember(cwEntries, providerId, titleId) {
        cwEntries
            .filter { it.providerId == providerId && it.titleId == titleId }
            .map(WatchEntry::episodeNumber)
            .toSet()
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        when (val s = state) {
            DetailsState.Loading -> CenterMessage("Loading…")
            is DetailsState.Error -> CenterMessage("Error: ${s.message}")
            is DetailsState.Loaded -> Loaded(
                details = s.details,
                watchedNumbers = watchedNumbers,
                onPlay = { ep ->
                    onPlayEpisode(titleId, ep, providerId, s.details.title, s.details.poster)
                },
            )
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Loaded(
    details: TitleDetails,
    watchedNumbers: Set<Int>,
    onPlay: (Episode) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            modifier = Modifier
                .width(170.dp)
                .height(255.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (details.poster != null) {
                AsyncImage(
                    model = details.poster,
                    contentDescription = details.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(details.title.take(2).uppercase(), style = MaterialTheme.typography.headlineMedium)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = details.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val subtitle = listOfNotNull(details.year?.toString(), details.kind.name).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            if (!details.qualityNote.isNullOrBlank()) {
                Text(
                    text = details.qualityNote,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFB74D),
                )
            }
            if (!details.synopsis.isNullOrBlank()) {
                ExpandableSynopsis(details.synopsis)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Episodes (${details.episodes.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            EpisodeList(details.episodes, watchedNumbers, onPlay)
        }
    }
}

@Composable
private fun ExpandableSynopsis(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflowed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val caret = if (expanded) " ▴" else " ▾"
    Surface(
        onClick = { if (overflowed || expanded) expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
            Text(
                text = if (overflowed || expanded) text + caret else text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                color = MaterialTheme.colorScheme.onBackground.copy(
                    alpha = if (focused) 1f else 0.85f,
                ),
                onTextLayout = { result ->
                    if (!expanded && result.hasVisualOverflow) overflowed = true
                },
            )
        }
    }
}

@Composable
private fun EpisodeList(
    episodes: List<Episode>,
    watchedNumbers: Set<Int>,
    onPlay: (Episode) -> Unit,
) {
    if (episodes.isEmpty()) {
        Text("No episodes found.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    // Long catalogs (Shippuuden: 499 eps) are unreadable as bars — switch
    // to a dense numbered grid above a threshold. Bars still win below it
    // because they show the episode title inline.
    if (episodes.size <= 50) {
        EpisodeBars(episodes, watchedNumbers, onPlay)
    } else {
        EpisodeGrid(episodes, watchedNumbers, onPlay)
    }
}

@Composable
private fun EpisodeBars(
    episodes: List<Episode>,
    watchedNumbers: Set<Int>,
    onPlay: (Episode) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(episodes, key = { "${it.number}:${it.id}" }) { ep ->
            EpisodeRow(ep, watched = ep.number in watchedNumbers, onPlay = onPlay)
        }
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<Episode>,
    watchedNumbers: Set<Int>,
    onPlay: (Episode) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 68.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(episodes, key = { "${it.number}:${it.id}" }) { ep ->
            EpisodeSquare(ep, watched = ep.number in watchedNumbers, onPlay = onPlay)
        }
    }
}

@Composable
private fun EpisodeSquare(ep: Episode, watched: Boolean, onPlay: (Episode) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val baseColor = if (watched) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surface
    val focusColor = if (watched) Color(0xFF43A047) else MaterialTheme.colorScheme.primary
    Surface(
        onClick = { onPlay(ep) },
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = baseColor,
            focusedContainerColor = focusColor,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color.White
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ep.number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (watched || focused) Color.White
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, watched: Boolean, onPlay: (Episode) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { onPlay(ep) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .border(1.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = buildString {
                append("Ep ")
                append(ep.number)
                if (!ep.title.isNullOrBlank()) {
                    append(" · ")
                    append(ep.title)
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (watched) {
                Text(
                    text = "▶ Watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}
