package com.dt.streamz.ui

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.dt.streamz.R
import androidx.tv.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.dt.streamz.DtApplication
import com.dt.streamz.data.CANONICAL_ANILIST_PROVIDER_ID
import com.dt.streamz.data.MediaKind
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.data.WatchEntry
import com.dt.streamz.data.canonicalSearchTitle
import com.dt.streamz.data.canonicalizedForCatalog
import com.dt.streamz.data.completedEpisodeIds
import com.dt.streamz.data.displayLabel
import com.dt.streamz.networkmonitor.NetworkIndicator
import com.dt.streamz.scraper.Binge
import com.dt.streamz.ui.brand.DtLogo
import com.dt.streamz.ui.brand.UpdateChip
import androidx.compose.runtime.collectAsState
import com.dt.streamz.ui.details.DetailsScreen
import com.dt.streamz.ui.genres.GenresScreen
import com.dt.streamz.ui.home.CuratedRow
import com.dt.streamz.ui.home.HomeScreen
import com.dt.streamz.scraper.tmdb.TmdbProvider
import com.dt.streamz.ui.library.LibraryScreen
import com.dt.streamz.ui.player.PlayerScreen
import com.dt.streamz.ui.search.SearchScreen
import com.dt.streamz.ui.settings.SettingsScreen
import com.dt.streamz.ui.sourcepicker.SourcePickerScreen
import com.dt.streamz.twitch.TwitchStreamResolver
import com.dt.streamz.ui.twitch.TwitchScreen
import com.dt.streamz.ui.webplayer.WebPlayerScreen
import com.dt.streamz.ui.youtube.YouTubeTabScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

private enum class Section(val label: String) {
    Home("Home"),
    Anime("Anime"),
    Movies("Movies"),
    TV("TV"),
    YouTube("YouTube"),
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

    // Navigation back-stack. Tabs is the permanent root (index 0, never popped);
    // pushing a Route makes it the visible screen and BACK pops one level. This
    // replaces the old flat single-route model where EVERY screen's BACK jumped
    // straight to Tabs — which collapsed past the episode list and the Sub/Dub
    // picker, so BACK from an episode dumped you back on the search grid and the
    // picker was unreachable once you'd chosen a version. With a real stack:
    // Tabs -> Details(episodes) -> SourcePicker(sub/dub) -> Player, and BACK
    // walks back through each.
    val backStack = remember { mutableStateListOf<Route>(Route.Tabs) }
    val route: Route = backStack.last()
    fun push(to: Route) { backStack.add(to) }
    // Swap the current top in place (no new back-entry) — used when a player
    // rolls into the next episode / a related video / an embed fallback, so the
    // stack doesn't grow a player-on-player chain.
    fun replaceTop(to: Route) { backStack[backStack.lastIndex] = to }
    fun popToTabs() { while (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    // Hoisted out of TabsDestination so the selected tab survives a trip into
    // a player route (TabsDestination leaves composition while a video plays);
    // otherwise BACK always dumped the user on Home instead of the tab they
    // were browsing, and the tab highlight desynced from the content.
    var selectedTab: Section by remember { mutableStateOf(Section.Home) }

    // YouTube tab search state, hoisted here (like selectedTab) so it survives
    // the player route — without this, BACK from a searched YouTube video drops
    // to the recommended grid instead of the search results you came from.
    var ytSearchQuery by remember { mutableStateOf("") }
    var ytSearchResults by remember {
        mutableStateOf<List<com.dt.streamz.data.SearchResult>?>(null)
    }
    // Prevent repeated OK presses from launching several concurrent YouTube
    // resolvers while the first native source is still being selected.
    var youtubeOpeningId by remember { mutableStateOf<String?>(null) }

    // Central source -> route: single source plays; explicit Sub/Dub variants
    // always stay a user choice. Older builds stored a global audio preference
    // and used it to skip this picker, which could hide English Dub forever
    // after a user had once chosen Sub on a different title.
    fun routeForSources(
        label: String, sources: List<StreamSource>,
        pid: String?, tid: String?, eid: String?, startMs: Long = 0,
    ): Route? {
        if (sources.isEmpty()) {
            Toast.makeText(ctx, "No source — title may be gone", Toast.LENGTH_SHORT).show()
            com.dt.streamz.diag.Telemetry.report(
                "no_source",
                mapOf("provider" to pid, "title" to tid, "episode" to eid, "label" to label),
            )
            return null
        }
        if (sources.size == 1) {
            return playRouteFor(sources.first(), label, sources, pid, tid, eid, startMs)
        }
        // YouTube returns [embed, watch-page] as primary + fallback, not a
        // user-facing server choice. Play the embed straight away and let
        // WebPlayer walk to the watch page if embedding is blocked — never
        // show the Sub/Dub-style source picker for it.
        if (pid == "youtube") {
            return playRouteFor(sources.first(), label, sources, pid, tid, eid, startMs)
        }
        // Sub/Dub is a real user choice -> keep the picker. Otherwise the
        // multiple entries are just servers (movies/TV): auto-play the most
        // reliable one and let WebPlayer walk the rest, ranked, on failure.
        // The picker is still reachable as a last resort if all of them fail.
        val isAudioChoice = sources.any {
            val l = it.serverLabel ?: ""
            l.contains("sub", ignoreCase = true) || l.contains("dub", ignoreCase = true)
        }
        if (!isAudioChoice) {
            val ranked = rankSources(app, sources)
            return playRouteFor(ranked.first(), label, ranked, pid, tid, eid, startMs)
        }
        return Route.SourcePicker(label, sources, pid, tid, eid, startMs)
    }

    // Record + resolve + route to a specific episode (fresh start, position 0).
    // Checks the prefetch cache first so Next/auto-play feel instant.
    // [replace] = true swaps the current player in place (Next/Prev/auto-play
    // from inside a player), so the stack doesn't grow a player-on-player chain;
    // false pushes a new entry (opening an episode fresh from a tab/list).
    suspend fun playEpisode(
        pid: String, tid: String, ep: com.dt.streamz.data.Episode,
        titleName: String, poster: String?, kindName: String?,
        replace: Boolean = false,
    ) {
        val episodeLabel = ep.displayLabel()
        Toast.makeText(ctx, "▶ $episodeLabel", Toast.LENGTH_SHORT).show()
        app.interests.recordWatch(titleName)
        val existing = app.continueWatching.find(pid, tid)
        app.continueWatching.record(
            WatchEntry(
                providerId = pid, titleId = tid, titleName = titleName, poster = poster,
                episodeId = ep.id, episodeNumber = ep.number,
                episodeTitle = ep.title,
                timestamp = System.currentTimeMillis(), kind = kindName,
                watchedEpisodeIds = existing?.completedEpisodeIds()?.toList().orEmpty(),
            ),
        )
        val sources = Binge.takeStreams(pid, tid, ep.id)
            ?: runCatching { registry.get(pid).streams(tid, ep) }.getOrDefault(emptyList())
        val r = routeForSources("$titleName · $episodeLabel", sources, pid, tid, ep.id) ?: return
        if (replace) replaceTop(r) else push(r)
    }

    // Resolve + play the episode [delta] steps from [r]'s current one
    // (+1 = next, -1 = previous). Shared by the Next/Prev buttons (manual =
    // true, toasts when there's nothing there) and auto-play-on-end
    // (manual = false, +1, returns to tabs at the finale).
    // Resolve + play the episode [delta] steps from (pid,tid,eid). Works for
    // both the native player and the embed (WebPlayer) routes. manual=true
    // toasts when there's nothing there; manual=false (auto-play-on-end)
    // silently returns to the tabs at the finale.
    fun advanceFrom(
        pid: String?, tid: String?, eid: String?, fallbackTitle: String,
        delta: Int, manual: Boolean,
    ) {
        if (pid == null || tid == null || eid == null) {
            if (!manual) popToTabs()
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
                else popToTabs()
                return@launch
            }
            // Next/Prev/auto-play swap the player in place — BACK from episode N
            // returns to the episode list, not a stack of every prior episode.
            playEpisode(pid, tid, target, details?.title ?: fallbackTitle, details?.poster, details?.kind?.name, replace = true)
        }
    }

    fun advanceEpisode(r: Route.Player, delta: Int, manual: Boolean) =
        advanceFrom(r.providerId, r.titleId, r.episodeId, r.title, delta, manual)

    // YouTube autoplay-next: when a video ends, play its top related video —
    // the continuity the embed used to provide, now driven from the native
    // player. No related / resolve failure -> fall back to the tabs.
    fun playYouTubeRelated(videoId: String?) {
        if (videoId == null) { popToTabs(); return }
        scope.launch {
            val nextId = runCatching { registry.get("youtube").related(videoId) }
                .getOrNull()?.firstOrNull()
            if (nextId == null) { popToTabs(); return@launch }
            app.youtubeInterests.recordWatch(nextId)
            com.dt.streamz.scraper.BrowseCache.invalidate("youtube")
            val ep = com.dt.streamz.data.Episode(id = "watch", number = 1, title = "Watch")
            val sources = runCatching { registry.get("youtube").streams(nextId, ep) }
                .getOrDefault(emptyList())
            // Swap the finished video for the next in place (BACK still returns
            // to the YouTube tab, not a chain of auto-played videos).
            val r = routeForSources("YouTube", sources, "youtube", nextId, "watch") ?: run { popToTabs(); return@launch }
            replaceTop(r)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        when (val r = route) {
            Route.Tabs -> TabsDestination(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                ytQuery = ytSearchQuery,
                onYtQueryChange = { ytSearchQuery = it },
                ytResults = ytSearchResults,
                onYtResultsChange = { ytSearchResults = it },
                youtubeOpeningId = youtubeOpeningId,
                onOpenTitle = { providerId, titleId ->
                    if (providerId == "youtube") {
                        if (youtubeOpeningId == null) {
                            // Resolve native sources immediately in the
                            // background; the card stays visible with an
                            // Opening… state and duplicate taps are ignored.
                            youtubeOpeningId = titleId
                            scope.launch {
                                try {
                                    // YouTube-only watch signal: record the
                                    // opened video so the next feed uses its
                                    // related graph for personalization.
                                    app.youtubeInterests.recordWatch(titleId)
                                    com.dt.streamz.scraper.BrowseCache.invalidate("youtube")
                                    val ep = com.dt.streamz.data.Episode(
                                        id = "watch", number = 1, title = "Watch",
                                    )
                                    val sources = runCatching {
                                        registry.get("youtube").streams(titleId, ep)
                                    }.getOrDefault(emptyList())
                                    routeForSources("YouTube", sources, "youtube", titleId, "watch")
                                        ?.let { push(it) }
                                } finally {
                                    if (youtubeOpeningId == titleId) youtubeOpeningId = null
                                }
                            }
                        }
                    } else {
                        push(Route.Details(providerId, titleId))
                    }
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
                            push(
                                Route.Player(
                                    url = url,
                                    title = "twitch.tv/$channel",
                                    twitchChannel = channel,
                                    kind = StreamKind.Hls,
                                ),
                            )
                        }
                    }
                },
                onRemoveContinue = { entry ->
                    scope.launch { app.continueWatching.remove(entry.providerId, entry.titleId) }
                },
                onResume = { entry ->
                    scope.launch {
                        // The store normally canonicalizes known aliases, but
                        // normalize again at this boundary so an old in-memory
                        // row can never route through a stale provider key.
                        val resumeEntry = entry.canonicalizedForCatalog()
                        // Continue Watching is persisted across process restarts,
                        // while several providers keep search metadata only in
                        // memory. Re-hydrate and verify the canonical title
                        // before asking any provider for a stream. In particular,
                        // never reconstruct an episode from only the saved number:
                        // a provider must confirm the exact episode id and kind.
                        val provider = runCatching { registry.get(resumeEntry.providerId) }.getOrNull()
                        if (provider == null) {
                            app.continueWatching.remove(resumeEntry.providerId, resumeEntry.titleId)
                            Toast.makeText(ctx, "Saved item was removed because its source is no longer available", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val details = Binge.details(provider, resumeEntry.titleId)
                        if (details == null ||
                            details.providerId != resumeEntry.providerId ||
                            details.id != resumeEntry.titleId ||
                            canonicalSearchTitle(details.title) != canonicalSearchTitle(resumeEntry.titleName)
                        ) {
                            Log.e(TAG, "resume refused: saved title identity did not match ${resumeEntry.providerId}:${resumeEntry.titleId}")
                            app.continueWatching.remove(resumeEntry.providerId, resumeEntry.titleId)
                            Toast.makeText(ctx, "Saved item no longer matches its source and was removed", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val savedKind = resumeEntry.kind?.let { raw ->
                            runCatching { MediaKind.valueOf(raw) }.getOrNull()
                        }
                        if (savedKind != null && savedKind != details.kind) {
                            Log.e(
                                TAG,
                                "resume refused: kind mismatch ${resumeEntry.providerId}:${resumeEntry.titleId} " +
                                    "saved=$savedKind resolved=${details.kind}",
                            )
                            app.continueWatching.remove(resumeEntry.providerId, resumeEntry.titleId)
                            Toast.makeText(ctx, "This saved entry was invalid and was removed", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val ep = details.episodes.firstOrNull { it.id == resumeEntry.episodeId }
                        if (ep == null) {
                            Log.e(
                                TAG,
                                "resume refused: episode ${resumeEntry.episodeId} is not in " +
                                    "${resumeEntry.providerId}:${resumeEntry.titleId}",
                            )
                            app.continueWatching.remove(resumeEntry.providerId, resumeEntry.titleId)
                            Toast.makeText(ctx, "This saved episode is no longer available", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        // Persist the verified identity/title/poster after a
                        // successful migration so the old provider cannot come
                        // back after the next process restart.
                        val verifiedEntry = resumeEntry.copy(
                            titleName = details.title,
                            poster = details.poster ?: resumeEntry.poster,
                            episodeId = ep.id,
                            episodeNumber = ep.number,
                            episodeTitle = ep.title,
                            kind = details.kind.name,
                        )
                        app.continueWatching.record(verifiedEntry)

                        // Up Next: if the saved episode is finished, roll into
                        // the next canonical episode instead of replaying it.
                        if (isFinished(verifiedEntry)) {
                            val idx = details.episodes.indexOfFirst { it.id == ep.id }
                            val next = if (idx >= 0) details.episodes.getOrNull(idx + 1) else null
                            if (next != null) {
                                playEpisode(
                                    verifiedEntry.providerId, verifiedEntry.titleId, next,
                                    details.title, details.poster ?: entry.poster,
                                    details.kind.name,
                                )
                                return@launch
                            }
                            // Last episode -> replay the exact verified episode.
                        }

                        val resumeMs = resumeStartMs(verifiedEntry, ep.id)
                        val sourcesResult = runCatching { provider.streams(verifiedEntry.titleId, ep) }
                        val sources = sourcesResult.getOrNull()
                        if (sources == null) {
                            val error = sourcesResult.exceptionOrNull()
                            Log.w(TAG, "resume failed", error)
                            Toast.makeText(ctx, "Couldn't resume: ${error?.message ?: "source unavailable"}", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        if (sources.isEmpty()) {
                            Log.e(TAG, "resume refused: provider returned no verified sources for ${verifiedEntry.providerId}:${verifiedEntry.titleId}:${ep.id}")
                            Toast.makeText(ctx, "This title has no verified playback source right now", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        routeForSources(
                            "${details.title} · ${ep.displayLabel()}", sources,
                            verifiedEntry.providerId, verifiedEntry.titleId, ep.id, resumeMs,
                        )?.let { push(it) }
                    }
                },
            )
            is Route.Details -> {
                BackHandler { back() }
                DetailsScreen(
                    registry = registry,
                    providerId = r.providerId,
                    titleId = r.titleId,
                    onPlayEpisode = { titleId, ep, providerId, titleName, poster, kind ->
                        scope.launch {
                            // Resume if we left this exact episode partway through.
                            val existing = app.continueWatching.find(providerId, titleId)
                            val resumeMs = resumeStartMs(existing, ep.id)
                            app.interests.recordWatch(titleName)
                            app.continueWatching.record(
                                WatchEntry(
                                    providerId = providerId,
                                    titleId = titleId,
                                    titleName = titleName,
                                    poster = poster,
                                    episodeId = ep.id,
                                    episodeNumber = ep.number,
                                    episodeTitle = ep.title,
                                    timestamp = System.currentTimeMillis(),
                                    kind = kind.name,
                                    positionMs = resumeMs,
                                    durationMs = if (existing?.episodeId == ep.id) existing.durationMs else 0,
                                    watchedEpisodeIds = existing?.completedEpisodeIds()?.toList().orEmpty(),
                                ),
                            )
                            runCatching { registry.get(providerId).streams(titleId, ep) }
                                .onSuccess { sources ->
                                    routeForSources(
                                        "$titleName · ${ep.displayLabel()}", sources,
                                        providerId, titleId, ep.id, resumeMs,
                                    )?.let { push(it) }
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
                BackHandler { back() }
                SourcePickerScreen(
                    title = r.title,
                    sources = r.sources,
                    onPick = { picked ->
                        // PUSH the player on top of the picker so BACK from
                        // playback returns here to choose the other version
                        // (Sub <-> Dub) instead of replaying the same one.
                        push(
                            playRouteFor(
                                picked, r.title, r.sources,
                                providerId = r.providerId,
                                titleId = r.titleId,
                                episodeId = r.episodeId,
                                startPositionMs = r.startPositionMs,
                            ),
                        )
                    },
                )
            }
            is Route.Player -> {
                BackHandler {
                    Log.i(TAG, "BackHandler fired from PlayerScreen -> back()")
                    back()
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
                val isYouTube = r.providerId == "youtube"
                PlayerScreen(
                    url = r.url,
                    streamKind = r.kind,
                    headers = r.headers,
                    title = r.title,
                    twitchChannel = r.twitchChannel,
                    isLive = r.isLive,
                    startPositionMs = r.startPositionMs,
                    audioUrl = r.audioUrl,
                    dashManifest = r.dashManifest,
                    audioDashManifest = r.audioDashManifest,
                    subtitles = r.subtitles,
                    audioTracks = r.audioTracks,
                    // Captions stay OFF by default for YouTube (English audio,
                    // never auto-on) but remember the user's last CC choice;
                    // anime/movies use the per-source default; English Dub
                    // sources can start with captions off.
                    captionsDefaultOn = if (isYouTube) false else r.captionsDefaultOn,
                    rememberCaptions = isYouTube,
                    onProgress = { posMs, durMs ->
                        val pid = r.providerId
                        val tid = r.titleId
                        val eid = r.episodeId
                        // YouTube isn't tracked in Continue Watching (it has no
                        // episode catalog) — skip persisting position for it.
                        if (!isYouTube && pid != null && tid != null && eid != null) {
                            scope.launch {
                                app.continueWatching.updatePosition(pid, tid, eid, posMs, durMs)
                            }
                        }
                    },
                    // Episode Next/Prev only make sense for episodic content.
                    // YouTube uses autoplay-next-related on end instead.
                    showNextButton = r.episodeId != null && !isYouTube,
                    onNext = { advanceEpisode(r, delta = 1, manual = true) },
                    onPrev = { advanceEpisode(r, delta = -1, manual = true) },
                    onEnded = {
                        if (isYouTube) playYouTubeRelated(r.titleId)
                        else scope.launch {
                            val pid = r.providerId
                            val tid = r.titleId
                            val eid = r.episodeId
                            if (pid != null && tid != null && eid != null) {
                                app.continueWatching.markEpisodeWatched(pid, tid, eid)
                            }
                            advanceEpisode(r, delta = 1, manual = false)
                        }
                    },
                    // YouTube native playback failed (bot wall, codec, URL, or
                    // box quirk) — consume the next native source first. This
                    // is important now that Piped is a real backup, not merely
                    // a search backend. Only fall into the IFrame/watch page
                    // pair after every native source has failed.
                    onPlaybackError = if (isYouTube) {
                        {
                            val next = r.fallbacks.firstOrNull()
                            if (next != null) {
                                val nextLabel = next.serverLabel ?: "YouTube backup"
                                Log.i(TAG, "YT native failed -> $nextLabel")
                                Toast.makeText(
                                    ctx,
                                    "YouTube source failed — trying $nextLabel",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                replaceTop(
                                    playRouteFor(
                                        next,
                                        r.title,
                                        r.sourceChoices.ifEmpty { r.fallbacks },
                                        providerId = r.providerId,
                                        titleId = r.titleId,
                                        episodeId = r.episodeId,
                                        startPositionMs = r.startPositionMs,
                                    ),
                                )
                            } else {
                                val vid = r.titleId.orEmpty()
                                Log.i(TAG, "YT native failed -> embed fallback for $vid")
                                replaceTop(Route.WebPlayer(
                                    embedUrl = "ytembed://$vid",
                                    title = r.title,
                                    fallbacks = listOf(
                                        StreamSource(
                                            url = "https://www.youtube.com/watch?v=$vid",
                                            kind = StreamKind.DirectEmbed,
                                            serverLabel = "YouTube (page)",
                                            headers = mapOf("Referer" to "https://www.youtube.com/"),
                                        ),
                                    ),
                                    providerId = "youtube",
                                    titleId = vid,
                                    episodeId = "watch",
                                ))
                            }
                        }
                    } else if (r.fallbacks.isNotEmpty()) {
                        {
                            val next = r.fallbacks.first()
                            val nextLabel = next.serverLabel ?: "backup server"
                            val sourceChoices = r.sourceChoices.ifEmpty { r.fallbacks }
                            Log.w(TAG, "${r.title} failed -> trying $nextLabel")
                            Toast.makeText(
                                ctx,
                                "Server failed — trying $nextLabel",
                                Toast.LENGTH_SHORT,
                            ).show()
                            // Keep the failed route out of the next candidate
                            // list so a provider outage cannot loop forever.
                            replaceTop(
                                playRouteFor(
                                    next,
                                    r.title,
                                    sourceChoices,
                                    providerId = r.providerId,
                                    titleId = r.titleId,
                                    episodeId = r.episodeId,
                                    startPositionMs = r.startPositionMs,
                                ),
                            )
                        }
                    } else null,
                    startupFallbackDelayMs = r.startupFallbackDelayMs,
                    onExit = {
                        Log.i(TAG, "PlayerScreen.onExit() called -> back()")
                        back()
                    },
                )
            }
            is Route.WebPlayer -> {
                BackHandler { back() }
                val sameAudioSources = r.allSources.filter { source ->
                    sameAudioVariant(
                        r.selectedSourceLabel,
                        r.embedUrl,
                        source.serverLabel,
                        source.url,
                    )
                }
                WebPlayerScreen(
                    embedUrl = r.embedUrl,
                    title = r.title,
                    headers = r.headers,
                    fallbacks = r.fallbacks,
                    captionsDefaultOn = r.captionsDefaultOn,
                    startPositionMs = r.startPositionMs,
                    onProgress = { posMs, durMs ->
                        val pid = r.providerId
                        val tid = r.titleId
                        val eid = r.episodeId
                        // YouTube embeds are not part of the catalog-backed
                        // Continue Watching history. Anime/movie/TV embeds
                        // report their HTML5 position back to the same store
                        // used by native playback.
                        if (r.providerId != "youtube" && pid != null && tid != null && eid != null) {
                            scope.launch {
                                app.continueWatching.updatePosition(pid, tid, eid, posMs, durMs)
                            }
                        }
                    },
                    // YouTube-style autoplay: resolve related videos for the
                    // embed player to cycle through. Only the YouTube provider
                    // returns anything; everything else opts out (empty list).
                    youtubeRelated = { videoId ->
                        runCatching {
                            registry.all.firstOrNull { it.supportsYouTube }?.related(videoId)
                        }.getOrNull().orEmpty()
                    },
                    // Rich related videos for the in-player "Up next" rail.
                    youtubeRelatedResults = { videoId ->
                        runCatching {
                            registry.all.firstOrNull { it.supportsYouTube }?.relatedResults(videoId)
                        }.getOrNull().orEmpty()
                    },
                    // Episodic embed (TV/anime, not movies/YouTube) gets the
                    // Netflix-style D-pad control bar (press UP) + best-effort
                    // auto-play-next when the embed reports the video ended.
                    showNextPrev = r.episodeId != null && r.providerId != "youtube",
                    onNext = { advanceFrom(r.providerId, r.titleId, r.episodeId, r.title, +1, manual = true) },
                    onPrev = { advanceFrom(r.providerId, r.titleId, r.episodeId, r.title, -1, manual = true) },
                    onEmbedEnded = {
                        scope.launch {
                            val pid = r.providerId
                            val tid = r.titleId
                            val eid = r.episodeId
                            if (pid != null && tid != null && eid != null) {
                                app.continueWatching.markEpisodeWatched(pid, tid, eid)
                            }
                            advanceFrom(pid, tid, eid, r.title, +1, manual = false)
                        }
                    },
                    // Last-resort manual server picker when every ranked mirror
                    // failed (not YouTube's embed/page pair).
                    // Anime sources can contain both Dub and Sub siblings; a
                    // failed English route must not present the Japanese route
                    // as though it were another English server.
                    onPickServer = if (sameAudioSources.size > 1 && r.providerId != "youtube") {
                        {
                            // Replace the dead embed with the picker so BACK from
                            // it returns to the list/tab, not the failed player.
                            replaceTop(
                                Route.SourcePicker(
                                    r.title, sameAudioSources, r.providerId,
                                    r.titleId, r.episodeId, r.startPositionMs,
                                ),
                            )
                        }
                    } else null,
                    // Mid-watch Sub<->Dub switch from the control bar. Only when
                    // this title actually has both a sub and a dub variant —
                    // otherwise there's nothing to switch to. Re-opens the picker
                    // (replacing the live player); the picker re-pushes the new
                    // player, so BACK from it lands back on the picker.
                    onSwitchAudio = run {
                        val hasSub = r.allSources.any { (it.serverLabel ?: "").contains("sub", ignoreCase = true) }
                        val hasDub = r.allSources.any { (it.serverLabel ?: "").contains("dub", ignoreCase = true) }
                        if (hasSub && hasDub && r.providerId != "youtube") {
                            {
                                replaceTop(
                                    Route.SourcePicker(
                                        r.title, r.allSources, r.providerId,
                                        r.titleId, r.episodeId, r.startPositionMs,
                                    ),
                                )
                            }
                        } else null
                    },
                    onExit = { back() },
                )
            }
        }
    }
    if (route !is Route.Tabs) androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        val update by app.availableUpdate.collectAsState()
        // Hide the DT watermark while a video is on screen — it shouldn't
        // sit over playback. NetworkIndicator stays (it self-hides when the
        // connection is healthy and is useful mid-stream) but goes faint
        // during playback so it doesn't take away from the video.
        val watching = route is Route.Player || route is Route.WebPlayer
        UpdateChip(update = update)
        NetworkIndicator(monitor = app.networkMonitor, dim = watching)
        // (DtLogo watermark removed — the viewmaxxing logo now lives in the tab
        // bar, so a second corner mark was redundant and crowded the tabs.)
    }
    }
}

@Composable
private fun TabsDestination(
    selected: Section,
    onSelect: (Section) -> Unit,
    onOpenTitle: (providerId: String, titleId: String) -> Unit,
    onOpenTwitchChannel: (String) -> Unit,
    onResume: (com.dt.streamz.data.WatchEntry) -> Unit,
    onRemoveContinue: (com.dt.streamz.data.WatchEntry) -> Unit,
    ytQuery: String,
    onYtQueryChange: (String) -> Unit,
    ytResults: List<com.dt.streamz.data.SearchResult>?,
    onYtResultsChange: (List<com.dt.streamz.data.SearchResult>?) -> Unit,
    youtubeOpeningId: String? = null,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication

    val tabTint = tabTintFor(selected)
    val bgColor = MaterialTheme.colorScheme.background
    // Keep the content selection separate from the D-pad highlight. The
    // highlight must follow the tab under the remote before OK is pressed,
    // while content should still change only on an explicit activation.
    var focusedTab by remember { mutableStateOf<Section?>(null) }
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
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.viewmaxxing_logo),
                contentDescription = "viewmaxxing",
                modifier = Modifier
                    .padding(start = 12.dp, end = 4.dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            // Status chips stay off this row so every tab, including Settings,
            // remains visible and fully clickable.
            TabRow(
                selectedTabIndex = selected.ordinal,
                // Keep focus traversal inside the tab strip before it falls
                // back to content. A scrolled grid must never land on a tab
                // and silently change the section.
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusGroup(),
                indicator = { tabPositions, doesTabRowHaveFocus ->
                    val indicatorSection = focusedTab ?: selected
                    val indicatorIndex = indicatorSection.ordinal
                    if (indicatorIndex in tabPositions.indices) {
                        TabRowDefaults.PillIndicator(
                            currentTabPosition = tabPositions[indicatorIndex],
                            doesTabRowHaveFocus = doesTabRowHaveFocus,
                        )
                    }
                },
            ) {
                Section.entries.forEach { section ->
                    Tab(
                        selected = selected == section,
                        onFocus = { focusedTab = section },
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    focusedTab = section
                                } else if (focusedTab == section) {
                                    focusedTab = null
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key.nativeKeyCode in setOf(
                                        android.view.KeyEvent.KEYCODE_ENTER,
                                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                    )
                                ) {
                                    onSelect(section)
                                    true
                                } else {
                                    false
                                }
                            }
                            .pointerClickable { onSelect(section) },
                    ) {
                        TabLabel(section = section, selected = selected == section)
                    }
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
                showHero = true,
                forYou = recommenderFor(app, { true }, { true }),
                forYouFilter = { it.providerId != "youtube" },
            )
            // Each content tab gets its OWN search bar, scoped to that tab's
            // kind — searching Anime returns only anime, Movies only movies, so
            // results never mix. Browse rows show until you search.
            Section.Anime -> SearchScreen(
                registry = app.providerRegistry,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                scopeKey = "anime",
                kindFilter = { it == MediaKind.Anime },
                providerFilter = { it.id == CANONICAL_ANILIST_PROVIDER_ID },
                resultFilter = { it.kind == MediaKind.Anime && it.providerId == CANONICAL_ANILIST_PROVIDER_ID },
                placeholder = "🔍  Search anime…",
                idleContent = {
                    HomeScreen(
                        title = "Anime",
                        registry = app.providerRegistry,
                        providerFilter = { it.id == CANONICAL_ANILIST_PROVIDER_ID },
                        kindFilter = { it == MediaKind.Anime },
                        cwKind = MediaKind.Anime,
                        continueWatching = app.continueWatching,
                        favorites = app.favorites,
                        onOpenTitle = onOpenTitle,
                        onResume = onResume,
                        onRemoveContinue = onRemoveContinue,
                        forYou = recommenderFor(app, { it.supportsAnime }, { it == MediaKind.Anime }),
                        forYouFilter = { it.kind == MediaKind.Anime && it.providerId != "youtube" },
                    )
                },
            )
            Section.Movies -> SearchScreen(
                registry = app.providerRegistry,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                scopeKey = "movies",
                kindFilter = { it == MediaKind.Movie },
                resultFilter = { it.kind == MediaKind.Movie && it.providerId != "youtube" },
                placeholder = "🔍  Search movies…",
                idleContent = {
                    HomeScreen(
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
                        // Curated TMDb rows replace the single mixed Must-Watch row.
                        forYou = recommenderFor(app, { it.supportsMovies }, { it == MediaKind.Movie }),
                        forYouFilter = { it.kind == MediaKind.Movie && it.providerId != "youtube" },
                        curatedRows = curatedRowsFor(app, tv = false),
                    )
                },
            )
            Section.TV -> SearchScreen(
                registry = app.providerRegistry,
                favorites = app.favorites,
                onOpenTitle = onOpenTitle,
                scopeKey = "tv",
                kindFilter = { it == MediaKind.Series },
                resultFilter = { it.kind == MediaKind.Series && it.providerId != "youtube" },
                placeholder = "🔍  Search TV shows…",
                idleContent = {
                    HomeScreen(
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
                        forYou = recommenderFor(app, { it.supportsMovies }, { it == MediaKind.Series }),
                        forYouFilter = { it.kind == MediaKind.Series && it.providerId != "youtube" },
                        curatedRows = curatedRowsFor(app, tv = true),
                    )
                },
            )
            Section.YouTube -> YouTubeTabScreen(
                registry = app.providerRegistry,
                onOpenTitle = onOpenTitle,
                openingVideoId = youtubeOpeningId,
                query = ytQuery,
                onQueryChange = onYtQueryChange,
                results = ytResults,
                onResultsChange = onYtResultsChange,
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
                providerFilter = { !it.supportsYouTube },
                resultFilter = { it.providerId != "youtube" },
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
private val YouTubeRed = androidx.compose.ui.graphics.Color(0xFFFF0000)

private fun tabTintFor(section: Section): androidx.compose.ui.graphics.Color = when (section) {
    Section.Home -> androidx.compose.ui.graphics.Color(0xFF3F51B5)
    Section.Anime -> AnimeRed
    Section.Movies -> MoviesGold
    Section.TV -> TvBlue
    Section.YouTube -> YouTubeRed
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
        headers = source.headers,
        providerId = providerId,
        titleId = titleId,
        episodeId = episodeId,
        startPositionMs = startPositionMs,
        fallbacks = siblings
            .dropWhile { it.url != source.url }
            .drop(1)
            .filter { candidate ->
                sameAudioVariant(
                    source.serverLabel,
                    source.url,
                    candidate.serverLabel,
                    candidate.url,
                )
            },
        sourceChoices = siblings,
        startupFallbackDelayMs = if (
            source.serverLabel.orEmpty().contains("MegaPlay", ignoreCase = true)
        ) {
            12_000L
        } else {
            null
        },
        audioUrl = source.audioUrl,
        dashManifest = source.dashManifest,
        audioDashManifest = source.audioDashManifest,
        subtitles = source.subtitles,
        audioTracks = source.audioTracks,
        captionsDefaultOn = source.captionsDefaultOn,
        isLive = source.isLive,
    )
    StreamKind.DirectEmbed -> {
        // Auto-fallback list = every other DirectEmbed source we know about,
        // preserving the (already reliability-ranked) order. Lets WebPlayer
        // walk past a dead mirror without dumping the user back to the picker.
        val fallbacks = siblings
            .filter { it.kind == StreamKind.DirectEmbed && it.url != source.url }
            .filter { candidate ->
                sameAudioVariant(
                    source.serverLabel,
                    source.url,
                    candidate.serverLabel,
                    candidate.url,
                )
            }
        Route.WebPlayer(
            embedUrl = source.url,
            title = label,
            headers = source.headers,
            fallbacks = fallbacks,
            allSources = siblings,
            selectedSourceLabel = source.serverLabel,
            providerId = providerId,
            titleId = titleId,
            episodeId = episodeId,
            startPositionMs = startPositionMs,
            captionsDefaultOn = source.captionsDefaultOn,
        )
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

/** Embed host used as the reliability-stats key (e.g. "vidlink.pro"). */
private fun hostKey(url: String): String =
    runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()

/**
 * Orders embed mirrors most-likely-to-work first: by learned reliability score,
 * with any host marked dead this session shoved to the back. This is what makes
 * the player try the optimal server first instead of the provider's fixed order.
 */
private fun rankSources(app: DtApplication, sources: List<StreamSource>): List<StreamSource> =
    sources.sortedByDescending { src ->
        if (com.dt.streamz.ui.webplayer.DeadHostRegistry.isDead(src.url)) -1.0
        else app.serverStats.score(hostKey(src.url))
    }

private enum class AudioVariant {
    Dub,
    Sub,
    Neutral,
}

private val DUB_LABEL_TOKEN = Regex("\\bdub\\b", RegexOption.IGNORE_CASE)
private val SUB_LABEL_TOKEN = Regex("\\bsub(?:titles?)?\\b", RegexOption.IGNORE_CASE)
private val AUDIO_PATH_TOKEN = Regex("/(dub|sub)(?:/|$|[?&#])", RegexOption.IGNORE_CASE)

/**
 * Identifies the language carried by a source without trusting the display
 * order. VidNest encodes the mode in the URL (`/dub` or `/sub`), while other
 * providers only expose it in the server label.
 */
private fun audioVariant(label: String?, url: String): AudioVariant {
    val labelText = label.orEmpty()
    val pathMode = AUDIO_PATH_TOKEN.find(url)?.groupValues?.getOrNull(1)
    val isDub = DUB_LABEL_TOKEN.containsMatchIn(labelText) ||
        pathMode.equals("dub", ignoreCase = true)
    val isSub = SUB_LABEL_TOKEN.containsMatchIn(labelText) ||
        pathMode.equals("sub", ignoreCase = true)
    return when {
        isDub && !isSub -> AudioVariant.Dub
        isSub && !isDub -> AudioVariant.Sub
        else -> AudioVariant.Neutral
    }
}

/** Only same-language sources may participate in automatic fallback. */
private fun sameAudioVariant(
    selectedLabel: String?,
    selectedUrl: String,
    candidateLabel: String?,
    candidateUrl: String,
): Boolean = audioVariant(selectedLabel, selectedUrl) == audioVariant(candidateLabel, candidateUrl)

/**
 * Curated TMDb rows for the Movies / TV tabs — Popular / Top Rated / Trending /
 * etc. — so those tabs are full browsable listings (most-popular to least, plus
 * what's airing now) instead of a single mixed "Must Watch" row. Empty if TMDb
 * isn't available (no API key), in which case the tabs fall back to their
 * provider browse rows.
 */
private fun curatedRowsFor(app: DtApplication, tv: Boolean): List<CuratedRow> {
    val tmdb = app.providerRegistry.all.firstOrNull { it.id == "tmdb" } as? TmdbProvider
        ?: return emptyList()
    return if (tv) listOf(
        CuratedRow("Popular") { tmdb.categoryRow("tv/popular") },
        CuratedRow("Top Rated") { tmdb.categoryRow("tv/top_rated") },
        CuratedRow("Trending this week") { tmdb.categoryRow("trending/tv/week") },
        CuratedRow("New Episodes") { tmdb.categoryRow("tv/on_the_air") },
        CuratedRow("Airing Today") { tmdb.categoryRow("tv/airing_today") },
    ) else listOf(
        CuratedRow("Popular") { tmdb.categoryRow("movie/popular") },
        CuratedRow("Top Rated") { tmdb.categoryRow("movie/top_rated") },
        CuratedRow("Now Playing") { tmdb.categoryRow("movie/now_playing") },
        CuratedRow("Trending this week") { tmdb.categoryRow("trending/movie/week") },
        CuratedRow("Upcoming") { tmdb.categoryRow("movie/upcoming") },
    )
}

/**
 * Builds the per-tab "For You" recommender: searches the tab's providers with
 * the user's top learned interest terms and returns the matching titles,
 * deduped, capped. Returns empty when personalization is off or there's no
 * history yet (cold start) so the row simply doesn't render. Runs lazily —
 * HomeScreen calls it off the main thread when the tab paints.
 */
private fun recommenderFor(
    app: DtApplication,
    providerFilter: (com.dt.streamz.scraper.Provider) -> Boolean,
    kindFilter: (MediaKind) -> Boolean,
): suspend () -> List<com.dt.streamz.data.SearchResult> = recommend@{
    // tmdb has no real search() — it feeds the Must-Watch row only. YouTube
    // has its own tab and must never bleed into a catalog For You row; its
    // loose video metadata can otherwise look like a movie to a category.
    val provs = app.providerRegistry.all
        .filter(providerFilter)
        .filter { it.id != "tmdb" && it.id != "youtube" }
    if (provs.isEmpty()) return@recommend emptyList()
    val terms = app.interests.topTerms(4)
    if (terms.isEmpty()) return@recommend emptyList()
    // Search each learned term/provider pair concurrently, but keep the
    // deferred creation order so stronger terms still win the dedupe pass.
    val searchResults = kotlinx.coroutines.coroutineScope {
        terms.flatMap { term ->
            provs.map { provider ->
                async { runCatching { provider.search(term) }.getOrDefault(emptyList()) }
            }
        }.awaitAll()
    }
    val out = LinkedHashMap<String, com.dt.streamz.data.SearchResult>()
    for (res in searchResults) {
        for (r in res) {
            if (!kindFilter(r.kind)) continue
            out.putIfAbsent("${r.providerId}:${r.id}", r)
            if (out.size >= 24) break
        }
        if (out.size >= 24) break
    }
    // For on-demand catalogs (movies / anime / TV), `isLive` only ever marks a
    // genuine livestream; those pass through, and any live-flagged one is
    // re-confirmed live-now so an ended broadcast doesn't linger. Everything
    // non-live there passes straight through. YouTube is intentionally absent
    // above because it has its own dedicated tab and recommendation surface.
    val provById = provs.associateBy { it.id }
    suspend fun liveNow(r: com.dt.streamz.data.SearchResult): Boolean =
        provById[r.providerId]
            ?.let { runCatching { it.isLiveNow(r.id) }.getOrDefault(false) } == true
    kotlinx.coroutines.coroutineScope {
        out.values.map { r ->
            async {
                when {
                    r.providerId == "youtube" -> if (liveNow(r)) r else null
                    !r.isLive -> r
                    else -> if (liveNow(r)) r else null
                }
            }
        }.awaitAll().filterNotNull()
    }
}

private const val TAG = "DtApp"
