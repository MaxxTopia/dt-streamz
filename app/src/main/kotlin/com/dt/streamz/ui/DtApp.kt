package com.dt.streamz.ui

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.compose.ui.Alignment
import com.dt.streamz.DtApplication
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.networkmonitor.NetworkIndicator
import com.dt.streamz.ui.brand.DtLogo
import com.dt.streamz.ui.brand.UpdateChip
import androidx.compose.runtime.collectAsState
import com.dt.streamz.ui.details.DetailsScreen
import com.dt.streamz.ui.genres.GenresScreen
import com.dt.streamz.ui.home.HomeScreen
import com.dt.streamz.ui.home.LatestScreen
import com.dt.streamz.ui.library.LibraryScreen
import com.dt.streamz.ui.player.PlayerScreen
import com.dt.streamz.ui.search.SearchScreen
import com.dt.streamz.ui.settings.SettingsScreen
import com.dt.streamz.ui.sourcepicker.SourcePickerScreen
import com.dt.streamz.twitch.TwitchStreamResolver
import com.dt.streamz.ui.twitch.TwitchScreen
import com.dt.streamz.ui.webplayer.WebPlayerScreen
import com.dt.streamz.ui.youtube.YouTubeTabScreen
import kotlinx.coroutines.launch

private enum class Section(val label: String) {
    Home("Home"),
    Anime("Anime"),
    Movies("Movies"),
    TV("TV"),
    YouTube("YouTube"),
    Latest("Latest"),
    Search("Search"),
    Genres("Genres"),
    Twitch("Twitch"),
    Library("Library"),
    Settings("Settings"),
}

@Composable
fun DtApp() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val registry = app.providerRegistry
    val scope = rememberCoroutineScope()
    val twitchResolver = remember { TwitchStreamResolver() }

    var route: Route by remember { mutableStateOf(Route.Tabs) }

    Box(modifier = Modifier.fillMaxSize()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        when (val r = route) {
            Route.Tabs -> TabsDestination(
                onOpenTitle = { providerId, titleId ->
                    route = Route.Details(providerId, titleId)
                },
                onOpenTwitchChannel = { channel ->
                    // Runs in DtApp's scope so it survives a Tab focus-
                    // snap unmounting TwitchScreen mid-flight.
                    Log.i(TAG, "onOpenTwitchChannel($channel)")
                    scope.launch {
                        val url = runCatching { twitchResolver.resolveHls(channel) }
                            .onFailure { Log.w(TAG, "resolveHls($channel) threw", it) }
                            .getOrNull()
                        if (url == null) {
                            Toast.makeText(
                                ctx,
                                "$channel is offline or Twitch refused the token",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Log.i(TAG, "route -> Player(twitch=$channel, urlLen=${url.length})")
                            route = Route.Player(
                                url = url,
                                title = "twitch.tv/$channel",
                                twitchChannel = channel,
                                kind = StreamKind.Hls,
                            )
                        }
                    }
                },
                onRemoveContinue = { entry ->
                    scope.launch { app.continueWatching.remove(entry.providerId, entry.titleId) }
                },
                onResume = { entry ->
                    scope.launch {
                        val ep = com.dt.streamz.data.Episode(
                            id = entry.episodeId,
                            number = entry.episodeNumber,
                            title = null,
                        )
                        runCatching {
                            registry.get(entry.providerId).streams(entry.titleId, ep)
                        }.onSuccess { sources ->
                            val label = "${entry.titleName} · Ep ${entry.episodeNumber}"
                            when {
                                sources.isEmpty() -> Toast.makeText(
                                    ctx, "No source — title may be gone",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                sources.size == 1 -> route = playRouteFor(sources.first(), label, sources)
                                else -> route = Route.SourcePicker(label, sources)
                            }
                        }.onFailure {
                            Log.w(TAG, "resume failed", it)
                            Toast.makeText(ctx, "Couldn't resume: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            is Route.Details -> {
                BackHandler { route = Route.Tabs }
                DetailsScreen(
                    registry = registry,
                    providerId = r.providerId,
                    titleId = r.titleId,
                    onPlayEpisode = { titleId, ep, providerId, titleName, poster ->
                        scope.launch {
                            app.continueWatching.record(
                                WatchEntry(
                                    providerId = providerId,
                                    titleId = titleId,
                                    titleName = titleName,
                                    poster = poster,
                                    episodeId = ep.id,
                                    episodeNumber = ep.number,
                                    timestamp = System.currentTimeMillis(),
                                ),
                            )
                            runCatching { registry.get(providerId).streams(titleId, ep) }
                                .onSuccess { sources ->
                                    val epLabel = "Ep ${ep.number}"
                                    when {
                                        sources.isEmpty() -> {
                                            Log.w(TAG, "no playable source for $providerId/$titleId ep=${ep.number}")
                                            Toast.makeText(ctx, "No playable source found", Toast.LENGTH_SHORT).show()
                                        }
                                        sources.size == 1 -> route = playRouteFor(sources.first(), epLabel, sources)
                                        else -> route = Route.SourcePicker(epLabel, sources)
                                    }
                                }
                                .onFailure {
                                    Log.w(TAG, "streams() failed", it)
                                    Toast.makeText(ctx, "Couldn't fetch stream: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                )
            }
            is Route.SourcePicker -> {
                BackHandler { route = Route.Tabs }
                SourcePickerScreen(
                    title = r.title,
                    sources = r.sources,
                    onPick = { picked -> route = playRouteFor(picked, r.title, r.sources) },
                )
            }
            is Route.Player -> {
                BackHandler {
                    Log.i(TAG, "BackHandler fired from PlayerScreen -> Tabs")
                    route = Route.Tabs
                }
                PlayerScreen(
                    url = r.url,
                    streamKind = r.kind,
                    title = r.title,
                    twitchChannel = r.twitchChannel,
                    onExit = {
                        Log.i(TAG, "PlayerScreen.onExit() called -> Tabs")
                        route = Route.Tabs
                    },
                )
            }
            is Route.WebPlayer -> {
                BackHandler { route = Route.Tabs }
                WebPlayerScreen(
                    embedUrl = r.embedUrl,
                    headers = r.headers,
                    fallbacks = r.fallbacks,
                    onExit = { route = Route.Tabs },
                )
            }
        }
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        val update by app.availableUpdate.collectAsState()
        UpdateChip(update = update)
        NetworkIndicator(monitor = app.networkMonitor)
        DtLogo()
    }
    }
}

@Composable
private fun TabsDestination(
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
    onOpenTwitchChannel: (String) -> Unit,
    onResume: (com.dt.streamz.data.WatchEntry) -> Unit,
    onRemoveContinue: (com.dt.streamz.data.WatchEntry) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    var selected by remember { mutableStateOf(Section.Home) }

    val tabTint = tabTintFor(selected)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to tabTint.copy(alpha = 0.10f),
                    0.30f to MaterialTheme.colorScheme.background,
                    1f to MaterialTheme.colorScheme.background,
                ),
            ),
    ) {
        TabRow(selectedTabIndex = selected.ordinal) {
            Section.entries.forEach { section ->
                Tab(
                    selected = selected == section,
                    onFocus = { selected = section },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    TabLabel(section = section, selected = selected == section)
                }
            }
        }
        when (selected) {
            Section.Home -> HomeScreen(
                registry = app.providerRegistry,
                providerFilter = { true },
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.Anime -> HomeScreen(
                title = "Anime",
                registry = app.providerRegistry,
                providerFilter = { it.supportsAnime },
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.Movies -> HomeScreen(
                title = "Movies",
                registry = app.providerRegistry,
                providerFilter = { it.supportsMovies },
                kindFilter = { it == MediaKind.Movie },
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.TV -> HomeScreen(
                title = "TV Shows",
                registry = app.providerRegistry,
                providerFilter = { it.supportsMovies },
                kindFilter = { it == MediaKind.Series },
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.YouTube -> YouTubeTabScreen(
                registry = app.providerRegistry,
                onOpenTitle = onOpenTitle,
            )
            Section.Latest -> LatestScreen(
                registry = app.providerRegistry,
                favorites = app.favorites,
                continueWatching = app.continueWatching,
                onOpenTitle = onOpenTitle,
            )
            Section.Library -> LibraryScreen(
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.Genres -> GenresScreen(
                registry = app.providerRegistry,
                favorites = app.favorites,
                continueWatching = app.continueWatching,
                onOpenTitle = onOpenTitle,
            )
            Section.Twitch -> TwitchScreen(onOpenChannel = onOpenTwitchChannel)
            Section.Search -> SearchScreen(
                registry = app.providerRegistry,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
            )
            Section.Settings -> SettingsScreen()
        }
    }
}

// Brand accents — desaturated/lifted versions of the original palette so
// the row reads "branded but quiet" instead of carnival. Used for both
// the selected tab label and the gradient wash behind each section.
private val AnimeRed = androidx.compose.ui.graphics.Color(0xFFE57373)
private val MoviesGold = androidx.compose.ui.graphics.Color(0xFFE8C56A)
private val TwitchPurple = androidx.compose.ui.graphics.Color(0xFFB39DDB)
private val TvBlue = androidx.compose.ui.graphics.Color(0xFF64B5F6)
private val LibraryTeal = androidx.compose.ui.graphics.Color(0xFF80CBC4)
private val GenresPink = androidx.compose.ui.graphics.Color(0xFFF48FB1)
private val LatestGreen = androidx.compose.ui.graphics.Color(0xFF81C784)
private val YouTubeRed = androidx.compose.ui.graphics.Color(0xFFEF5350)

private fun tabTintFor(section: Section): androidx.compose.ui.graphics.Color = when (section) {
    Section.Home -> androidx.compose.ui.graphics.Color(0xFF7986CB)
    Section.Anime -> AnimeRed
    Section.Movies -> MoviesGold
    Section.TV -> TvBlue
    Section.YouTube -> YouTubeRed
    Section.Latest -> LatestGreen
    Section.Library -> LibraryTeal
    Section.Genres -> GenresPink
    Section.Twitch -> TwitchPurple
    Section.Search -> androidx.compose.ui.graphics.Color(0xFF90A4AE)
    Section.Settings -> androidx.compose.ui.graphics.Color(0xFF90A4AE)
}

@Composable
private fun TabLabel(section: Section, selected: Boolean) {
    // Quieter look: drop UPPERCASE, keep brand-tinted accent on the
    // selected tab, soft gray on the rest. No special-cased ExtraBold for
    // any one section — every tab uses the same weight rules.
    val accent = tabTintFor(section)
    val unselected = androidx.compose.ui.graphics.Color(0xFFB0B0B0)
    val color = if (selected) accent else unselected
    val weight = if (selected)
        androidx.compose.ui.text.font.FontWeight.SemiBold
    else androidx.compose.ui.text.font.FontWeight.Medium
    Text(
        text = section.label,
        style = MaterialTheme.typography.titleSmall.copy(
            color = color,
            fontWeight = weight,
            letterSpacing = 0.4.sp,
        ),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun playRouteFor(
    source: StreamSource,
    label: String,
    siblings: List<StreamSource> = emptyList(),
): Route = when (source.kind) {
    StreamKind.Hls,
    StreamKind.Mp4,
    StreamKind.Dash -> Route.Player(source.url, label, kind = source.kind)
    StreamKind.DirectEmbed -> {
        // Auto-fallback list = every other DirectEmbed source we know
        // about, preserving the provider's intent order. Lets WebPlayer
        // walk past a dead mirror without dumping the user back to the
        // picker.
        val fallbacks = siblings
            .filter { it.kind == StreamKind.DirectEmbed && it.url != source.url }
        Route.WebPlayer(source.url, label, source.headers, fallbacks)
    }
}

private const val TAG = "DtApp"
