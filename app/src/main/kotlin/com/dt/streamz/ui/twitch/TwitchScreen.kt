package com.dt.streamz.ui.twitch

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.dt.streamz.DtApplication
import com.dt.streamz.ui.onMenuKeyUp
import com.dt.streamz.ui.pointerClickable
import com.dt.streamz.twitch.TwitchStreamResolver
import com.dt.streamz.twitch.TwitchChannel
import com.dt.streamz.twitch.TwitchVod
import com.dt.streamz.data.ProgressMath
import com.dt.streamz.ui.components.PosterImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TwitchScreen(
    onOpenChannel: (channel: String) -> Unit = {},
    onOpenBroadcast: (channel: String, broadcast: TwitchVod) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val scope = rememberCoroutineScope()
    val channels by (app.pinnedChannels.channels).collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    // null means the probe did not complete, not that the channel is offline.
    // Keeping those states separate prevents a Twitch/API outage from being
    // presented to the user as a false offline badge.
    var liveStatus by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var refreshNonce by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchNonce by remember { mutableStateOf(0) }
    var searchResults by remember { mutableStateOf<List<TwitchChannel>?>(null) }
    var searchLoading by remember { mutableStateOf(false) }
    var selectedChannel by remember { mutableStateOf<TwitchChannel?>(null) }
    var pastBroadcasts by remember { mutableStateOf<List<TwitchVod>?>(null) }
    var pastCursor by remember { mutableStateOf<String?>(null) }
    var pastHasMore by remember { mutableStateOf(false) }
    var pastLoading by remember { mutableStateOf(false) }
    var pastRefreshNonce by remember { mutableStateOf(0) }
    var archiveFilter by remember { mutableStateOf("") }
    var showArchiveFilterDialog by remember { mutableStateOf(false) }
    val resolver = remember { TwitchStreamResolver() }

    // Probe on entry, on explicit refresh, and once a minute while this tab is
    // visible. Uses GQL user.stream (null = offline); the PAT endpoint issues
    // tokens for offline channels too, so it cannot serve as a probe.
    LaunchedEffect(channels, refreshNonce) {
        if (channels.isEmpty()) {
            liveStatus = emptyMap()
            refreshing = false
            return@LaunchedEffect
        }
        refreshing = true
        val results = channels.map { ch ->
            async {
                ch to runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(6_000) { resolver.isLive(ch) }
                }.getOrNull()
            }
        }.awaitAll()
        liveStatus = results.toMap()
        refreshing = false
    }
    LaunchedEffect(channels) {
        while (channels.isNotEmpty()) {
            delay(60_000)
            refreshNonce++
        }
    }

    LaunchedEffect(searchQuery, searchNonce) {
        if (searchQuery.isBlank()) {
            searchResults = null
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        searchResults = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                resolver.searchChannels(searchQuery)
            } ?: emptyList()
        }.getOrDefault(emptyList())
        searchLoading = false
    }

    LaunchedEffect(selectedChannel?.login, pastRefreshNonce) {
        val channel = selectedChannel ?: run {
            pastBroadcasts = null
            pastCursor = null
            pastHasMore = false
            pastLoading = false
            return@LaunchedEffect
        }
        pastLoading = true
        pastCursor = null
        pastHasMore = false
        val page = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                resolver.pastBroadcasts(channel.login)
            }
        }.getOrNull()
        pastBroadcasts = page?.vods.orEmpty()
        pastCursor = page?.endCursor
        // Twitch can report hasNextPage without an end cursor on the public
        // web query. Do not render a button that would simply reload page one.
        pastHasMore = page?.hasNextPage == true && page.endCursor != null
        pastLoading = false
    }

    fun loadOlderBroadcasts() {
        val channel = selectedChannel ?: return
        val cursor = pastCursor ?: return
        if (pastLoading || !pastHasMore) return
        scope.launch {
            pastLoading = true
            val page = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    resolver.pastBroadcasts(channel.login, cursor = cursor)
                }
            }.getOrNull()
            if (page != null) {
                pastBroadcasts = (pastBroadcasts.orEmpty() + page.vods).distinctBy { it.id }
                pastCursor = page.endCursor
                pastHasMore = page.hasNextPage && page.endCursor != null
            }
            pastLoading = false
        }
    }

    val orderedChannels = channels.sortedWith(
        compareByDescending<String> { liveStatus[it] == true },
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Twitch",
                style = androidx.tv.material3.MaterialTheme.typography.headlineSmall,
                color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
            )
            RefreshButton(
                label = if (refreshing) "Checking…" else "Refresh",
                enabled = !refreshing,
                onClick = { if (!refreshing) refreshNonce++ },
            )
            RefreshButton(
                label = "Search channels",
                enabled = true,
                onClick = { showSearchDialog = true },
            )
        }
        Text(
            text = when {
                channels.isEmpty() -> "No pinned channels — add one with the + card, or search Twitch."
                else -> "Live channels first · refreshes every minute · press MENU to remove. Search any channel for its archive."
            },
            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(orderedChannels, key = { it }) { channel ->
                ChannelCard(
                    channel = channel,
                    enabled = true,
                    live = liveStatus[channel],
                    checked = liveStatus.containsKey(channel),
                    onClick = { onOpenChannel(channel) },
                    onRequestRemove = {
                        scope.launch {
                            app.pinnedChannels.remove(channel)
                            Toast.makeText(ctx, "Removed $channel", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            item(key = "__add__") {
                AddChannelCard(onClick = { showAddDialog = true })
            }
        }

        if (searchLoading || searchQuery.isNotBlank()) {
            Text(
                text = if (searchLoading) "Searching Twitch for \"$searchQuery\"…"
                else "Channel results · \"$searchQuery\"",
                style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            val found = searchResults
            if (!searchLoading && found != null && found.isEmpty()) {
                Text(
                    text = "No channels found. Try a login or part of a display name.",
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )
            } else if (found != null) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(found, key = { it.login }) { channel ->
                        ChannelSearchCard(
                            channel = channel,
                            selected = selectedChannel?.login.equals(channel.login, ignoreCase = true),
                            onClick = {
                                selectedChannel = channel
                                archiveFilter = ""
                                pastRefreshNonce++
                            },
                        )
                    }
                }
            }
        }

        selectedChannel?.let { channel ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${channel.displayName} · past broadcasts",
                    style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                )
                RefreshButton(
                    label = "Watch live",
                    enabled = true,
                    onClick = { onOpenChannel(channel.login) },
                )
                RefreshButton(
                    label = if (pastLoading) "Loading…" else "Refresh archive",
                    enabled = !pastLoading,
                    onClick = { if (!pastLoading) pastRefreshNonce++ },
                )
                RefreshButton(
                    label = "Pin channel",
                    enabled = true,
                    onClick = {
                        scope.launch {
                            app.pinnedChannels.add(channel.login)
                            Toast.makeText(ctx, "Pinned ${channel.displayName}", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                RefreshButton(
                    label = if (archiveFilter.isBlank()) "Filter archive" else "Filter: $archiveFilter",
                    enabled = true,
                    onClick = { showArchiveFilterDialog = true },
                )
            }
            val vods = pastBroadcasts
            if (pastLoading) {
                Text(
                    text = "Loading recent broadcasts…",
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )
            } else if (vods != null && vods.isEmpty()) {
                Text(
                    text = "No saved broadcasts were returned for this channel.",
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )
            } else if (vods != null) {
                val visibleVods = vods.filter {
                    archiveFilter.isBlank() || it.title.contains(archiveFilter, ignoreCase = true)
                }
                if (visibleVods.isEmpty()) {
                    Text(
                        text = "No archived broadcasts match \"$archiveFilter\".",
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(visibleVods, key = { it.id }) { broadcast ->
                        BroadcastCard(
                            broadcast = broadcast,
                            resumeMs = ProgressMath.resumePosition(
                                app.twitchVodProgress.resumePosition(broadcast.id),
                                broadcast.lengthSeconds * 1000L,
                            ),
                            onClick = { onOpenBroadcast(channel.login, broadcast) },
                        )
                    }
                }
                if (pastHasMore) {
                    RefreshButton(
                        label = if (pastLoading) "Loading older…" else "Load older broadcasts",
                        enabled = !pastLoading,
                        onClick = ::loadOlderBroadcasts,
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        // Reuse the app-wide D-pad on-screen keyboard. The box's system IME
        // never reliably delivers committed text back to a field (see the
        // SearchEditorDialog header), so a Material text field here meant the
        // channel often couldn't be typed at all. The grid keyboard always
        // works on the remote.
        com.dt.streamz.ui.search.SearchEditorDialog(
            initialQuery = "",
            onDismiss = { showAddDialog = false },
            onSubmit = { text ->
                // Accept a pasted/typed "twitch.tv/name" or bare login; logins
                // are lower-case.
                val cleaned = text.trim().lowercase()
                    .substringAfterLast("twitch.tv/")
                    .trim('/', ' ')
                if (cleaned.isNotBlank()) {
                    scope.launch { app.pinnedChannels.add(cleaned) }
                }
                showAddDialog = false
            },
        )
    }

    if (showSearchDialog) {
        com.dt.streamz.ui.search.SearchEditorDialog(
            initialQuery = searchQuery,
            onDismiss = { showSearchDialog = false },
            onSubmit = { text ->
                searchQuery = text.trim()
                searchNonce++
                selectedChannel = null
                showSearchDialog = false
            },
        )
    }

    if (showArchiveFilterDialog) {
        com.dt.streamz.ui.search.SearchEditorDialog(
            initialQuery = archiveFilter,
            onDismiss = { showArchiveFilterDialog = false },
            onSubmit = { text ->
                archiveFilter = text.trim()
                showArchiveFilterDialog = false
            },
        )
    }
}

@Composable
private fun Text(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    androidx.tv.material3.Text(text = text, style = style, color = color)
}

@Composable
private fun ChannelCard(
    channel: String,
    enabled: Boolean,
    live: Boolean?,
    checked: Boolean,
    onClick: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(196.dp)
            .onFocusChanged { focused = it.isFocused }
            .onMenuKeyUp(focused, onRequestRemove)
            .pointerClickable { if (enabled) onClick() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = mt.colorScheme.surface,
            focusedContainerColor = mt.colorScheme.primary,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val (badgeText, badgeColor) = when {
                !checked -> "… checking" to Color(0xFFBDBDBD)
                live == true -> "● LIVE" to Color(0xFFE91916)
                live == false -> "○ OFFLINE" to Color(0xFF9E9E9E)
                else -> "? STATUS UNAVAILABLE" to Color(0xFFFFB74D)
            }
            androidx.tv.material3.Text(
                text = badgeText,
                style = mt.typography.labelSmall,
                color = if (focused) Color.White else badgeColor,
            )
            androidx.tv.material3.Text(
                text = channel,
                style = mt.typography.titleSmall,
                color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            )
            androidx.tv.material3.Text(
                text = "twitch.tv/$channel",
                style = mt.typography.labelSmall,
                color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.7f)
                else mt.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun AddChannelCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = mt.colorScheme.surface.copy(alpha = 0.5f),
            focusedContainerColor = mt.colorScheme.primary,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            androidx.tv.material3.Text(
                text = "+",
                style = mt.typography.headlineMedium,
                color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            )
            androidx.tv.material3.Text(
                text = "Add channel",
                style = mt.typography.labelMedium,
                color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.75f)
                else mt.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ChannelSearchCard(
    channel: TwitchChannel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(220.dp)
            .height(88.dp)
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = mt.colorScheme.surface,
            focusedContainerColor = mt.colorScheme.primary,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PosterImage(
                model = channel.profileImageUrl,
                title = channel.displayName,
                modifier = Modifier.width(46.dp).height(46.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.tv.material3.Text(
                    text = channel.displayName,
                    style = mt.typography.titleSmall,
                    maxLines = 1,
                    color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
                )
                androidx.tv.material3.Text(
                    text = "@${channel.login}",
                    style = mt.typography.labelSmall,
                    color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else mt.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                androidx.tv.material3.Text(
                    text = if (selected) "Selected · archive below" else "OK to view archive",
                    style = mt.typography.labelSmall,
                    color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.75f)
                    else mt.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun BroadcastCard(
    broadcast: TwitchVod,
    resumeMs: Long = 0L,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(250.dp)
            .height(166.dp)
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = mt.colorScheme.surface,
            focusedContainerColor = mt.colorScheme.primary,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PosterImage(
                model = broadcast.thumbnailUrl,
                title = broadcast.title,
                modifier = Modifier.fillMaxWidth().height(94.dp),
            )
            if (resumeMs > 0L) {
                androidx.tv.material3.Text(
                    text = "Resume ${formatVodPosition(resumeMs)}",
                    style = mt.typography.labelSmall,
                    color = if (focused) mt.colorScheme.onPrimary else Color(0xFFFFD54F),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                androidx.tv.material3.Text(
                    text = broadcast.title,
                    style = mt.typography.labelMedium,
                    maxLines = 2,
                    color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
                )
                androidx.tv.material3.Text(
                    text = listOfNotNull(
                        broadcast.createdAt.substringBefore("T").takeIf { it.isNotBlank() },
                        formatVodDuration(broadcast.lengthSeconds),
                    ).joinToString(" · "),
                    style = mt.typography.labelSmall,
                    color = if (focused) mt.colorScheme.onPrimary.copy(alpha = 0.75f)
                    else mt.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private fun formatVodDuration(seconds: Int): String {
    if (seconds <= 0) return "VOD"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatVodPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes)
    else "%dm %02ds".format(minutes, seconds)
}

@Composable
private fun RefreshButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val mt = androidx.tv.material3.MaterialTheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable { if (enabled) onClick() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = mt.colorScheme.surface,
            focusedContainerColor = mt.colorScheme.primary,
        ),
    ) {
        androidx.tv.material3.Text(
            text = label,
            style = mt.typography.labelLarge,
            color = if (focused) mt.colorScheme.onPrimary else mt.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

