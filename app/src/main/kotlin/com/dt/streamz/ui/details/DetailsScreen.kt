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
import androidx.compose.ui.focus.focusRequester
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
import com.dt.streamz.data.completedEpisodeIds
import com.dt.streamz.data.displayLabel
import com.dt.streamz.scraper.ProviderRegistry
import com.dt.streamz.ui.pointerClickable
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
        kind: com.dt.streamz.data.MediaKind,
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
    val watchedEpisodeIds = remember(cwEntries, providerId, titleId) {
        cwEntries
            .filter { it.providerId == providerId && it.titleId == titleId }
            .flatMap { it.completedEpisodeIds() }
            .toSet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            DetailsState.Loading -> CenterMessage("Loading…")
            is DetailsState.Error -> ErrorMessage(
                message = s.message,
                onRetry = { vm.load() },
            )
            is DetailsState.Loaded -> {
                // Surface a one-tap Resume/Play action if this title has a
                // current, unfinished episode. The store records the episode
                // before stream resolution, so a 404 or provider outage can
                // legitimately leave position at zero; hiding the controls
                // in that case made Continue Watching look like it vanished.
                val resume = remember(cwEntries, providerId, titleId, s.details) {
                    val e = cwEntries.firstOrNull {
                        it.providerId == providerId && it.titleId == titleId &&
                            (it.durationMs <= 0L ||
                                it.positionMs < (it.durationMs - 20_000L).coerceAtLeast(0L))
                    }
                    val ep = e?.let { en -> s.details.episodes.firstOrNull { it.id == en.episodeId } }
                    if (e != null && ep != null) ResumeInfo(ep, e.positionMs) else null
                }
                Loaded(
                    details = s.details,
                    watchedEpisodeIds = watchedEpisodeIds,
                    resume = resume,
                    onPlay = { ep ->
                        onPlayEpisode(titleId, ep, providerId, s.details.title, s.details.poster, s.details.kind)
                    },
                )
            }
        }
    }
}

private data class ResumeInfo(val episode: Episode, val positionMs: Long)

private fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit) {
    val retryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Couldn't load: $message",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Surface(
                onClick = onRetry,
                modifier = Modifier.focusRequester(retryFocus),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun Loaded(
    details: TitleDetails,
    watchedEpisodeIds: Set<String>,
    resume: ResumeInfo?,
    onPlay: (Episode) -> Unit,
) {
    val isMovie = details.kind == com.dt.streamz.data.MediaKind.Movie
    Box(modifier = Modifier.fillMaxSize()) {
        // Cinematic full-bleed backdrop, dimmed + scrimmed for legibility.
        val backdrop = details.backdrop ?: details.poster
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                    ),
                ),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            com.dt.streamz.ui.components.PosterImage(
                model = details.poster,
                title = details.title,
                modifier = Modifier
                    .width(170.dp)
                    .height(255.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val subtitle = listOfNotNull(
                    details.year?.toString(),
                    if (isMovie) "Movie" else "Series",
                ).joinToString(" · ")
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
                Spacer(Modifier.height(4.dp))
                if (isMovie) {
                    // A movie is one thing to watch — show a single big Play /
                    // Resume button, never an "Episodes (1)" list.
                    val ep = details.episodes.firstOrNull()
                    if (ep != null) {
                        val label = if (resume != null) {
                            "▶ Resume  ·  ${formatClock(resume.positionMs)}"
                        } else {
                            "▶ Play"
                        }
                        PrimaryPlayButton(label) { onPlay(resume?.episode ?: ep) }
                    } else {
                        Text(
                            "No playable source found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    if (resume != null) {
                        val nextEpisode = remember(details.episodes, resume.episode.id) {
                            val index = details.episodes.indexOfFirst { it.id == resume.episode.id }
                            if (index >= 0) details.episodes.getOrNull(index + 1) else null
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResumeButton(resume, onClick = { onPlay(resume.episode) })
                            if (nextEpisode != null) {
                                NextEpisodeButton(nextEpisode, onClick = { onPlay(nextEpisode) })
                            }
                        }
                    }
                    val seasons = remember(details.episodes) { details.episodes.map { it.season }.distinct().sorted() }
                    val episodeLabel = if (seasons.size > 1) {
                        "Episodes (${details.episodes.size} across ${seasons.size} seasons)"
                    } else {
                        "Episodes (${details.episodes.size})"
                    }
                    Text(
                        text = episodeLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    )
                    EpisodeStatusLegend()
                    EpisodeList(details.episodes, watchedEpisodeIds, onPlay)
                }
            }
        }
    }
}

/** Big primary Play/Resume button for movies, auto-focused on open. */
@Composable
private fun PrimaryPlayButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        onClick = onClick,
        modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .border(
                    if (focused) 2.dp else 0.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 40.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun ResumeButton(resume: ResumeInfo, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Land the D-pad on this action when the screen opens with a saved episode,
    // so continuing (or retrying a zero-position start) is one OK press.
    val resumeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { resumeFocus.requestFocus() } }
    val label = buildString {
        append("▶ ")
        append(if (resume.positionMs >= 10_000L) "Resume" else "Play")
        append(" Ep ")
        append(resume.episode.number)
        append("  ·  ")
        append(formatClock(resume.positionMs))
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.focusRequester(resumeFocus).onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .border(
                    if (focused) 2.dp else 0.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun NextEpisodeButton(nextEpisode: Episode, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = "Next Ep ${nextEpisode.number}  ▶|",
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .border(
                    if (focused) 2.dp else 0.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EpisodeStatusLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        EpisodeLegendItem(Color(0xFF2E7D32), "Watched")
        EpisodeLegendItem(Color(0xFFE0A800), "Filler")
        EpisodeLegendItem(Color(0xFF7A3030), "Unwatched")
    }
}

@Composable
private fun EpisodeLegendItem(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
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
private fun SeasonChips(
    seasons: List<Int>,
    selectedSeason: Int,
    onSelectSeason: (Int) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        items(seasons, key = { it }) { season ->
            var focused by remember { mutableStateOf(false) }
            val isSelected = season == selectedSeason
            Surface(
                onClick = { onSelectSeason(season) },
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .pointerClickable { onSelectSeason(season) },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            ) {
                Text(
                    text = "Season $season",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected || focused) Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .border(
                            width = if (focused) 2.dp else 0.dp,
                            color = if (focused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val WATCHED_GREEN = Color(0xFF2E7D32)
private val WATCHED_GREEN_FOCUSED = Color(0xFF43A047)
private val FILLER_YELLOW = Color(0xFFE0A800)
private val FILLER_YELLOW_FOCUSED = Color(0xFFF2BD24)
private val UNWATCHED_RED = Color(0xFF7A3030)
private val UNWATCHED_RED_FOCUSED = Color(0xFF9A4141)

private fun episodeBaseColor(ep: Episode, watched: Boolean): Color = when {
    watched -> WATCHED_GREEN
    ep.isFiller -> FILLER_YELLOW
    else -> UNWATCHED_RED
}

private fun episodeFocusedColor(ep: Episode, watched: Boolean): Color = when {
    watched -> WATCHED_GREEN_FOCUSED
    ep.isFiller -> FILLER_YELLOW_FOCUSED
    else -> UNWATCHED_RED_FOCUSED
}

private fun episodeTextColor(ep: Episode, watched: Boolean, focused: Boolean): Color = when {
    focused || watched || !ep.isFiller -> Color.White
    else -> Color(0xFF241B00)
}

@Composable
private fun EpisodeList(
    episodes: List<Episode>,
    watchedEpisodeIds: Set<String>,
    onPlay: (Episode) -> Unit,
) {
    if (episodes.isEmpty()) {
        Text("No episodes found.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    var selectedSeason by remember(episodes) {
        mutableStateOf(seasons.firstOrNull() ?: 1)
    }
    val currentSeasonEpisodes = remember(episodes, selectedSeason) {
        if (seasons.size <= 1) episodes else episodes.filter { it.season == selectedSeason }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            SeasonChips(
                seasons = seasons,
                selectedSeason = selectedSeason,
                onSelectSeason = { selectedSeason = it },
            )
        }
        if (currentSeasonEpisodes.size <= 50) {
            EpisodeBars(currentSeasonEpisodes, watchedEpisodeIds, onPlay)
        } else {
            EpisodeGrid(currentSeasonEpisodes, watchedEpisodeIds, onPlay)
        }
    }
}

@Composable
private fun EpisodeBars(
    episodes: List<Episode>,
    watchedEpisodeIds: Set<String>,
    onPlay: (Episode) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(episodes, key = { "${it.season}:${it.episodeNumber}:${it.id}" }) { ep ->
            EpisodeRow(ep, watched = ep.id in watchedEpisodeIds, onPlay = onPlay)
        }
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<Episode>,
    watchedEpisodeIds: Set<String>,
    onPlay: (Episode) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 68.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(episodes, key = { "${it.season}:${it.episodeNumber}:${it.id}" }) { ep ->
            EpisodeSquare(ep, watched = ep.id in watchedEpisodeIds, onPlay = onPlay)
        }
    }
}

@Composable
private fun EpisodeSquare(ep: Episode, watched: Boolean, onPlay: (Episode) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val baseColor = episodeBaseColor(ep, watched)
    val focusColor = episodeFocusedColor(ep, watched)
    Surface(
        onClick = { onPlay(ep) },
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable { onPlay(ep) },
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (ep.season > 1 || ep.episodeNumber != ep.number) {
                        "S${ep.season} E${ep.episodeNumber}"
                    } else {
                        "Ep ${ep.number}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = episodeTextColor(ep, watched, focused),
                )
                Text(
                    text = buildString {
                        if (ep.isFiller) append("Filler · ")
                        append(ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    color = episodeTextColor(ep, watched, focused).copy(alpha = if (focused || watched) 0.9f else 0.85f),
                )
            }
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
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable { onPlay(ep) },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = episodeBaseColor(ep, watched),
            focusedContainerColor = episodeFocusedColor(ep, watched),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .border(1.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = ep.displayLabel()
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = episodeTextColor(ep, watched, focused),
                modifier = Modifier.weight(1f),
            )
            if (watched || ep.isFiller) {
                Text(
                    text = if (watched) "▶ Watched" else "Filler",
                    style = MaterialTheme.typography.labelSmall,
                    color = episodeTextColor(ep, watched, focused).copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}
