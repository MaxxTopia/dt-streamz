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
import androidx.compose.runtime.LaunchedEffect
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
import com.dt.streamz.scraper.Binge
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

    // Remembered Sub/Dub choice. When a title offers both and the user has a
    // saved preference, auto-pick it instead of showing the picker again.
    val audioPrefs = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    fun audioPref(): String? = audioPrefs.getString("audio_pref", null)
    fun setAudioPref(v: String) { audioPrefs.edit().putString("audio_pref", v).apply() }

    // Central source -> route: single source plays; a remembered Sub/Dub
    // choice auto-picks its variant; otherwise show the picker. Empty toasts.
    fun routeForSources(
        label: String, sources: List<StreamSource>,
        pid: String?, tid: String?, eid: String?, startMs: Long = 0,
    ): Route {
        if (sources.isEmpty()) {
            Toast.makeText(ctx, "No source — title may be gone", Toast.LENGTH_SHORT).show()
            return Route.Tabs
        }
        if (sources.size == 1) {
            return playRouteFor(sources.first(), label, sources, pid, tid, eid, startMs)
        }
        audioPref()?.let { pref ->
            val match = sources.firstOrNull { (it.serverLabel ?: "").contains(pref, ignoreCase = true) }
            if (match != null) return playRouteFor(match, label, sources, pid, tid, eid, startMs)
        }
        return Route.SourcePicker(label, sources, pid, tid, eid, startMs)
    }

    // Record + resolve + route to a specific episode (fresh start, position 0).
    // Checks the prefetch cache first so Next/auto-play feel instant.
    suspend fun playEpisode(
        pid: String, tid: String, ep: com.dt.streamz.data.Episode,
        titleName: String, poster: String?, kindName: String?,
    ) {
        Toast.makeText(ctx, "▶ Ep ${ep.number}", Toast.LENGTH_SHORT).show()
        app.continueWatching.record(
            WatchEntry(
                providerId = pid, titleId = tid, titleName = titleName, poster = poster,
                episodeId = ep.id, episodeNumber = ep.number,
                timestamp = System.currentTimeMillis(), kind = kindName,
            ),
        )
        val sources = Binge.takeStreams(pid, tid, ep.id)
            ?: runCatching { registry.get(pid).streams(tid, ep) }.getOrDefault(emptyList())
        route = routeForSources("$titleName · Ep ${ep.number}", sources, pid, tid, ep.id)
    }

    // Resolve + play the episode [delta] steps from [r]'s current one
    // (+1 = next, -1 = previous). Shared by the Next/Prev buttons (manual =
    // true, toasts when there's nothing there) and auto-play-on-end
    // (manual = false, +1, returns to tabs at the finale).
    fun advanceEpisode(r: Route.Player, delta: Int, manual: Boolean) {
        val pid = r.providerId
        val tid = r.titleId
        val eid = r.episodeId
        if (pid == null || tid == null || eid == null) {
            if (!manual) route = Route.Tabs
            return
        }
        scope.launch {
            val details = Binge.details(registry.get(pid), tid)
            val eps = details?.episodes.orEmpty()
            val idx = eps.indexOfFirst { it.id == eid }
            val target = if (idx >= 0) eps.getOrNull(idx + delta) else null
            if (target == null) {
                val which = if (delta > 0) "next" else "previous"
                if (manual) Toast.makeText(ctx, "No $which episode", Toast.LENGTH_SHORT).show()
                else route = Route.Tabs
                return@launch
            }
            playEpisode(pid, tid, target, details?.title ?: r.title, details?.poster, details?.kind?.name)
        }
    }

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
                        // Up Next: if the saved episode is finished, roll into
                        // the next one instead of replaying it.
                        if (isFinished(entry)) {
                            val details = Binge.details(registry.get(entry.providerId), entry.titleId)
                            val eps = details?.episodes.orEmpty()
                            val idx = eps.indexOfFirst { it.id == entry.episodeId }
                            val next = if (idx >= 0) eps.getOrNull(idx + 1) else null
                            if (next != null) {
                                playEpisode(
                                    entry.providerId, entry.titleId, next,
                                    details?.title ?: entry.titleName,
                                    details?.poster ?: entry.poster,
                                    details?.kind?.name ?: entry.kind,
                                )
                                return@launch
                            }
                            // unknown next / last episode -> fall through to replay
                        }
                        val ep = com.dt.streamz.data.Episode(
                            id = entry.episodeId,
                            number = entry.episodeNumber,
                            title = null,
                        )
                        val resumeMs = resumeStartMs(entry, entry.episodeId)
                        runCatching {
                            registry.get(entry.providerId).streams(entry.titleId, ep)
                        }.onSuccess { sources ->
                            route = routeForSources(
                                "${entry.titleName} · Ep ${entry.episodeNumber}", sources,
                                entry.providerId, entry.titleId, entry.episodeId, resumeMs,
                            )
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
                    onPlayEpisode = { titleId, ep, providerId, titleName, poster, kind ->
                        scope.launch {
                            // Resume if we left this exact episode partway through.
                            val existing = app.continueWatching.find(providerId, titleId)
                            val resumeMs = resumeStartMs(existing, ep.id)
                            app.continueWatching.record(
                                WatchEntry(
                                    providerId = providerId,
                                    titleId = titleId,
                                    titleName = titleName,
                                    poster = poster,
                                    episodeId = ep.id,
                                    episodeNumber = ep.number,
                                    timestamp = System.currentTimeMillis(),
                                    kind = kind.name,
                                    positionMs = resumeMs,
                                    durationMs = if (existing?.episodeId == ep.id) existing.durationMs else 0,
                                ),
                            )
                            runCatching { registry.get(providerId).streams(titleId, ep) }
                                .onSuccess { sources ->
                                    route = routeForSources(
                                        "Ep ${ep.number}", sources,
                                        providerId, titleId, ep.id, resumeMs,
                                    )
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
                    onPick = { picked ->
                        // Remember Sub/Dub so we can skip the picker next time.
                        val lbl = picked.serverLabel ?: ""
                        when {
                            lbl.contains("dub", ignoreCase = true) -> setAudioPref("Dub")
                            lbl.contains("sub", ignoreCase = true) -> setAudioPref("Sub")
                        }
                        route = playRouteFor(
                            picked, r.title, r.sources,
                            providerId = r.providerId,
                            titleId = r.titleId,
                            episodeId = r.episodeId,
                            startPositionMs = r.startPositionMs,
                        )
                    },
                )
            }
            is Route.Player -> {
                BackHandler {
                    Log.i(TAG, "BackHandler fired from PlayerScreen -> Tabs")
                    route = Route.Tabs
                }
                // Prefetch the next episode ~8s in (past this stream's startup)
                // so Next / auto-play land instantly.
                LaunchedEffect(r.providerId, r.titleId, r.episodeId) {
                    val pid = r.providerId
                    val tid = r.titleId
                    val eid = r.episodeId
                    if (pid != null && tid != null && eid != null) {
                        kotlinx.coroutines.delay(8_000)
                        Binge.prefetchNext(registry.get(pid), tid, eid)
                    }
                }
                PlayerScreen(
                    url = r.url,
                    streamKind = r.kind,
                    title = r.title,
                    twitchChannel = r.twitchChannel,
                    startPositionMs = r.startPositionMs,
                    subtitles = r.subtitles,
                    onProgress = { posMs, durMs ->
                        val pid = r.providerId
                        val tid = r.titleId
                        val eid = r.episodeId
                        if (pid != null && tid != null && eid != null) {
                            scope.launch {
                                app.continueWatching.updatePosition(pid, tid, eid, posMs, durMs)
                            }
                        }
                    },
                    showNextButton = r.episodeId != null,
                    onNext = { advanceEpisode(r, delta = 1, manual = true) },
                    onPrev = { advanceEpisode(r, delta = -1, manual = true) },
                    onEnded = { advanceEpisode(r, delta = 1, manual = false) },
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
    val bgColor = MaterialTheme.colorScheme.background
    val tintBrush = remember(tabTint, bgColor) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to tabTint.copy(alpha = 0.28f),
            0.45f to bgColor,
            1f to bgColor,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tintBrush),
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
                showMustWatch = true,
            )
            Section.Anime -> HomeScreen(
                title = "Anime",
                registry = app.providerRegistry,
                providerFilter = { it.supportsAnime },
                cwKind = MediaKind.Anime,
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
                cwKind = MediaKind.Movie,
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
                showMustWatch = true,
            )
            Section.TV -> HomeScreen(
                title = "TV Shows",
                registry = app.providerRegistry,
                providerFilter = { it.supportsMovies },
                kindFilter = { it == MediaKind.Series },
                cwKind = MediaKind.Series,
                continueWatching = app.continueWatching,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                onResume = onResume,
                onRemoveContinue = onRemoveContinue,
                showMustWatch = true,
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

private val AnimeRed = androidx.compose.ui.graphics.Color(0xFFE51C23)
private val MoviesGold = androidx.compose.ui.graphics.Color(0xFFFFC107)
private val TwitchPurple = androidx.compose.ui.graphics.Color(0xFF9146FF)
private val TvBlue = androidx.compose.ui.graphics.Color(0xFF1E88E5)
private val LibraryTeal = androidx.compose.ui.graphics.Color(0xFF26A69A)
private val GenresPink = androidx.compose.ui.graphics.Color(0xFFE91E63)
private val LatestGreen = androidx.compose.ui.graphics.Color(0xFF43A047)
private val YouTubeRed = androidx.compose.ui.graphics.Color(0xFFFF0000)

private fun tabTintFor(section: Section): androidx.compose.ui.graphics.Color = when (section) {
    Section.Home -> androidx.compose.ui.graphics.Color(0xFF3F51B5)
    Section.Anime -> AnimeRed
    Section.Movies -> MoviesGold
    Section.TV -> TvBlue
    Section.YouTube -> YouTubeRed
    Section.Latest -> LatestGreen
    Section.Library -> LibraryTeal
    Section.Genres -> GenresPink
    Section.Twitch -> TwitchPurple
    Section.Search -> androidx.compose.ui.graphics.Color(0xFF37474F)
    Section.Settings -> androidx.compose.ui.graphics.Color(0xFF37474F)
}

@Composable
private fun TabLabel(section: Section, selected: Boolean) {
    val color = when (section) {
        Section.Anime -> if (selected) AnimeRed else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.Movies -> if (selected) MoviesGold else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.Twitch -> if (selected) TwitchPurple else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.TV -> if (selected) TvBlue else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.YouTube -> if (selected) YouTubeRed else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.Library -> if (selected) LibraryTeal else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.Genres -> if (selected) GenresPink else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        Section.Latest -> if (selected) LatestGreen else androidx.compose.ui.graphics.Color(0xFFCFCFCF)
        else -> androidx.compose.ui.graphics.Color.White
    }
    val weight = if (section == Section.Anime && selected)
        androidx.compose.ui.text.font.FontWeight.ExtraBold else androidx.compose.ui.text.font.FontWeight.SemiBold
    Text(
        text = section.label.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            color = color,
            fontWeight = weight,
            letterSpacing = if (section == Section.Anime) 2.sp else 1.sp,
        ),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun playRouteFor(
    source: StreamSource,
    label: String,
    siblings: List<StreamSource> = emptyList(),
    providerId: String? = null,
    titleId: String? = null,
    episodeId: String? = null,
    startPositionMs: Long = 0,
): Route = when (source.kind) {
    StreamKind.Hls,
    StreamKind.Mp4,
    StreamKind.Dash -> Route.Player(
        url = source.url,
        title = label,
        kind = source.kind,
        providerId = providerId,
        titleId = titleId,
        episodeId = episodeId,
        startPositionMs = startPositionMs,
        subtitles = source.subtitles,
    )
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

// Resume thresholds: ignore a saved position under 10s (barely started),
// and treat anything within 20s of the end as finished — both start fresh.
private const val RESUME_MIN_MS = 10_000L
private const val RESUME_END_GUARD_MS = 20_000L

private fun resumeStartMs(entry: WatchEntry?, episodeId: String): Long {
    if (entry == null || entry.episodeId != episodeId) return 0
    val pos = entry.positionMs
    if (pos < RESUME_MIN_MS) return 0
    val dur = entry.durationMs
    if (dur > 0 && pos > dur - RESUME_END_GUARD_MS) return 0
    return pos
}

/** True when the saved episode was watched (essentially) to the end. */
private fun isFinished(entry: WatchEntry): Boolean =
    entry.durationMs > 0 && entry.positionMs > entry.durationMs - RESUME_END_GUARD_MS

private const val TAG = "DtApp"
