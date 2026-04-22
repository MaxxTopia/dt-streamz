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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dt.streamz.data.Episode
import com.dt.streamz.data.TitleDetails
import com.dt.streamz.scraper.ProviderRegistry

@Composable
fun DetailsScreen(
    registry: ProviderRegistry,
    providerId: String,
    titleId: String,
    onPlayEpisode: (titleId: String, episode: Episode, providerId: String) -> Unit,
) {
    val vm: DetailsViewModel = viewModel(
        key = "$providerId:$titleId",
        factory = DetailsViewModel.Factory(registry, providerId, titleId),
    )
    val state by vm.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        when (val s = state) {
            DetailsState.Loading -> CenterMessage("Loading…")
            is DetailsState.Error -> CenterMessage("Error: ${s.message}")
            is DetailsState.Loaded -> Loaded(
                details = s.details,
                onPlay = { onPlayEpisode(titleId, it, providerId) },
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
private fun Loaded(details: TitleDetails, onPlay: (Episode) -> Unit) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(330.dp)
                .clip(RoundedCornerShape(12.dp))
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
                Text(details.title.take(2).uppercase(), style = MaterialTheme.typography.displayMedium)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = details.title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val subtitle = listOfNotNull(details.year?.toString(), details.kind.name).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            if (!details.synopsis.isNullOrBlank()) {
                Text(
                    text = details.synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Episodes (${details.episodes.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            EpisodeList(details.episodes, onPlay)
        }
    }
}

@Composable
private fun EpisodeList(episodes: List<Episode>, onPlay: (Episode) -> Unit) {
    if (episodes.isEmpty()) {
        Text("No episodes found.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(episodes, key = { "${it.number}:${it.id}" }) { ep ->
            EpisodeRow(ep, onPlay)
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onPlay: (Episode) -> Unit) {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .border(1.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp)),
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
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
