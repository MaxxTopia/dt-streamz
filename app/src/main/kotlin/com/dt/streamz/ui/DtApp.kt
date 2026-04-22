package com.dt.streamz.ui

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
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.dt.streamz.ui.home.HomeScreen
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
    var selected by remember { mutableStateOf(Section.Home) }

    Surface(modifier = Modifier.fillMaxSize(), colors = androidx.tv.material3.SurfaceDefaults.colors(
        containerColor = MaterialTheme.colorScheme.background
    )) {
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
                Section.Home -> HomeScreen()
                Section.Anime -> HomeScreen(title = "Anime")
                Section.Movies -> HomeScreen(title = "Movies")
                Section.Twitch -> TwitchScreen()
                Section.Search -> SearchScreen()
                Section.Settings -> SettingsScreen()
            }
        }
    }
}
