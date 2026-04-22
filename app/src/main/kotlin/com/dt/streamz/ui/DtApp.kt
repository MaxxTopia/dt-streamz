package com.dt.streamz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.dt.streamz.ui.home.HomeScreen
import com.dt.streamz.ui.player.PlayerScreen
import com.dt.streamz.ui.search.SearchScreen
import com.dt.streamz.ui.settings.SettingsScreen
import com.dt.streamz.ui.twitch.TwitchScreen

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
    var route: Route by remember { mutableStateOf(Route.Tabs) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        when (val r = route) {
            Route.Tabs -> TabsDestination(onPlay = { url, title -> route = Route.Player(url, title) })
            is Route.Player -> {
                BackHandler { route = Route.Tabs }
                PlayerScreen(hlsUrl = r.hlsUrl, title = r.title, onExit = { route = Route.Tabs })
            }
        }
    }
}

@Composable
private fun TabsDestination(onPlay: (String, String) -> Unit) {
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
            Section.Home -> HomeScreen(onPlayTestStream = {
                onPlay(TEST_HLS_URL, "Test Stream (Mux BipBop)")
            })
            Section.Anime -> HomeScreen(title = "Anime", onPlayTestStream = {})
            Section.Movies -> HomeScreen(title = "Movies", onPlayTestStream = {})
            Section.Twitch -> TwitchScreen()
            Section.Search -> SearchScreen()
            Section.Settings -> SettingsScreen()
        }
    }
}

private const val TEST_HLS_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
