package com.dt.streamz.ui

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.networkmonitor.NetworkIndicator
import com.dt.streamz.ui.details.DetailsScreen
import com.dt.streamz.ui.home.HomeScreen
import com.dt.streamz.ui.player.PlayerScreen
import com.dt.streamz.ui.search.SearchScreen
import com.dt.streamz.ui.settings.SettingsScreen
import com.dt.streamz.ui.sourcepicker.SourcePickerScreen
import com.dt.streamz.ui.twitch.TwitchScreen
import com.dt.streamz.ui.webplayer.WebPlayerScreen
import kotlinx.coroutines.launch

private enum class Section(val label: String) {
    Home("Home"),
    Anime("Anime"),
    Movies("Movies"),
    Twitch("Twitch"),
    Search("Search"),
    Settings("Settings"),
}

@Composable
fun DtApp() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val registry = app.providerRegistry
    val scope = rememberCoroutineScope()

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
                onPlayTest = { url, title -> route = Route.Player(url, title) },
                onPlayTwitch = { url, title, channel ->
                    route = Route.Player(url, title, twitchChannel = channel)
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
                                sources.size == 1 -> route = playRouteFor(sources.first(), label)
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
                                        sources.size == 1 -> route = playRouteFor(sources.first(), epLabel)
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
                    onPick = { picked -> route = playRouteFor(picked, r.title) },
                )
            }
            is Route.Player -> {
                BackHandler { route = Route.Tabs }
                PlayerScreen(
                    hlsUrl = r.hlsUrl,
                    title = r.title,
                    twitchChannel = r.twitchChannel,
                    onExit = { route = Route.Tabs },
                )
            }
            is Route.WebPlayer -> {
                BackHandler { route = Route.Tabs }
                WebPlayerScreen(embedUrl = r.embedUrl, onExit = { route = Route.Tabs })
            }
        }
    }
    NetworkIndicator(
        monitor = app.networkMonitor,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(12.dp),
    )
    }
}

@Composable
private fun TabsDestination(
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
    onPlayTest: (String, String) -> Unit,
    onPlayTwitch: (String, String, String) -> Unit,
    onResume: (com.dt.streamz.data.WatchEntry) -> Unit,
    onRemoveContinue: (com.dt.streamz.data.WatchEntry) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    var selected by remember { mutableStateOf(Section.Home) }

    Column {
        TabRow(selectedTabIndex = selected.ordinal) {
            Section.entries.forEach { section ->
                Tab(
                    selected = selected == section,
                    onFocus = { selected = section },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = section.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        when (selected) {
            Section.Home -> HomeScreen(
                registry = app.providerRegistry,
                continueWatching = app.continueWatching,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
                onPlayTestStream = {
                    onPlayTest(TEST_HLS_URL, "Test Stream (Mux BipBop)")
                },
            )
            Section.Anime -> HomeScreen(
                title = "Anime",
                registry = app.providerRegistry,
                providerFilter = { it.supportsAnime },
                continueWatching = app.continueWatching,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.Movies -> HomeScreen(
                title = "Movies",
                registry = app.providerRegistry,
                providerFilter = { it.supportsMovies },
                continueWatching = app.continueWatching,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
            )
            Section.Twitch -> TwitchScreen(onPlayHlsWithChat = { url, label, channel ->
                onPlayTwitch(url, label, channel)
            })
            Section.Search -> SearchScreen(registry = app.providerRegistry, onOpenTitle = onOpenTitle)
            Section.Settings -> SettingsScreen()
        }
    }
}

private fun playRouteFor(source: StreamSource, label: String): Route = when (source.kind) {
    StreamKind.Hls, StreamKind.Mp4 -> Route.Player(source.url, label)
    StreamKind.DirectEmbed -> Route.WebPlayer(source.url, label)
}

private const val TAG = "DtApp"
private const val TEST_HLS_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
