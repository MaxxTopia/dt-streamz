package com.dt.streamz

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.dt.streamz.adblock.BlocklistRefreshWorker
import com.dt.streamz.adblock.HostBlocker
import com.dt.streamz.config.ScraperConfigLoader
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.data.FavoritesStore
import com.dt.streamz.data.InterestStore
import com.dt.streamz.data.YouTubeInterestStore
import com.dt.streamz.data.PlaybackPrefs
import com.dt.streamz.data.ServerStatsStore
import com.dt.streamz.networkmonitor.NetworkMonitor
import com.dt.streamz.scraper.ProviderRegistry
import com.dt.streamz.scraper.anicrush.AnicrushProvider
import com.dt.streamz.scraper.anilist.AniListProvider
import com.dt.streamz.scraper.fixtures.FixturesProvider
import com.dt.streamz.scraper.tmdb.TmdbProvider
import com.dt.streamz.scraper.vidsrc.VidSrcProvider
import com.dt.streamz.scraper.youtube.YouTubeProvider
import com.dt.streamz.twitch.PinnedChannelsStore
import com.dt.streamz.updater.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DtApplication : Application(), SingletonImageLoader.Factory {

    /**
     * Wider memory cache + on-disk persistence + 180ms crossfade so posters
     * fade in instead of popping (or staying grey forever) on the box.
     * The grey users were seeing was the surfaceVariant placeholder behind
     * a Coil load that hadn't completed — slow box + no disk persistence
     * meant every scroll re-fetched. 80 MiB of disk gives a row of posters
     * room to stick around.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(180)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(80L * 1024 * 1024)
                    .build()
            }
            .build()
    }


    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var scraperConfig: ScraperConfigLoader
        private set
    lateinit var providerRegistry: ProviderRegistry
        private set
    lateinit var hostBlocker: HostBlocker
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var pinnedChannels: PinnedChannelsStore
        private set
    lateinit var continueWatching: ContinueWatchingStore
        private set
    lateinit var favorites: FavoritesStore
        private set
    lateinit var interests: InterestStore
        private set
    // YouTube-only interest model (searches + watched video IDs) driving the
    // personalised YouTube grid. Kept separate from [interests] so movies/shows
    // never influence YouTube.
    lateinit var youtubeInterests: YouTubeInterestStore
        private set
    lateinit var serverStats: ServerStatsStore
        private set
    lateinit var playbackPrefs: PlaybackPrefs
        private set

    private val _availableUpdate = MutableStateFlow<UpdateChecker.Update?>(null)

    /** Latest update found by the launch-time check, or null if none/not yet checked. */
    val availableUpdate: StateFlow<UpdateChecker.Update?> = _availableUpdate.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        com.dt.streamz.diag.Telemetry.init(this, BuildConfig.VERSION_NAME)
        scraperConfig = ScraperConfigLoader(this)
        appScope.launch { scraperConfig.loadCachedThenRefresh() }

        // On-device interest model — must exist before the provider registry
        // so the YouTube provider can pull learned seeds for its grid.
        interests = InterestStore(this)
        // YouTube-only signal store for the personalised YouTube grid.
        youtubeInterests = YouTubeInterestStore(this)
        // Per-server reliability stats driving best-first mirror ordering.
        serverStats = ServerStatsStore(this)
        // Playback preferences (remembered captions choice + quality cap).
        playbackPrefs = PlaybackPrefs(this)

        // YouTube provider boots NewPipeExtractor lazily on first call,
        // but doing it here avoids the cold-start tax on first browse.
        YouTubeProvider.initOnce()
        // 9animetv (gogoanime.by's decryption backend) was nuked in 2024;
        // the provider's stream URLs now resolve to a dead player. Anikai
        // takes over as primary anime source — its hidden-WebView resolver
        // renders JS-heavy pages and captures direct m3u8 streams.
        providerRegistry = ProviderRegistry(
            providers = buildList {
                // FixturesProvider is the offline dev/sanity catalog (Mux
                // BipBop test stream etc.). It must NEVER ship in release —
                // its test content polluted real browse rows + Continue
                // Watching. Debug builds keep it for offline development.
                if (BuildConfig.DEBUG) add(FixturesProvider())
                add(TmdbProvider())
                // Anime: AniList metadata + vidnest WebView embed. The
                // scraping backends (animekai, AllAnime) both 403'd behind
                // Cloudflare from the box's IP; this embed path plays the same
                // way movies do (which work on the box).
                add(AniListProvider())
                add(VidSrcProvider())
                add(AnicrushProvider())
                // Personalise the YouTube grid from YOUTUBE-ONLY signals (no
                // login): watched video IDs seed YouTube's own related-video
                // graph, recent YouTube searches add intent. Movies/shows never
                // touch this — see [youtubeInterests].
                add(
                    YouTubeProvider(
                        recentWatchIds = { youtubeInterests.recentWatchIds(5) },
                        searchSeeds = { youtubeInterests.topSearchTerms(4) },
                        qualityCap = { playbackPrefs.qualityCap() },
                    ),
                )
            },
        )

        hostBlocker = HostBlocker(this)
        appScope.launch {
            // Seed is bundled in assets — always available instantly on
            // cold start even with no network. The upstream merge is now
            // handled by BlocklistRefreshWorker on a weekly cadence.
            hostBlocker.loadSeedFromAssets()
        }
        BlocklistRefreshWorker.schedule(this)

        // Sampling is driven by MainActivity's start/stop lifecycle so the
        // 3s TCP probe loop doesn't run while the app is backgrounded.
        networkMonitor = NetworkMonitor(this)

        pinnedChannels = PinnedChannelsStore(this)
        continueWatching = ContinueWatchingStore(this)
        favorites = FavoritesStore(this)

        appScope.launch {
            // Throttle: only re-poll GitHub if the last successful check was
            // > 6 hours ago. Survives across launches via SharedPreferences.
            val prefs = getSharedPreferences("updater", MODE_PRIVATE)
            val last = prefs.getLong("last_check_ms", 0L)
            val now = System.currentTimeMillis()
            if (now - last < 6 * 60 * 60 * 1000L) return@launch
            val update = runCatching { UpdateChecker().checkForUpdate() }.getOrNull()
            prefs.edit().putLong("last_check_ms", now).apply()
            _availableUpdate.value = update
        }
    }
}
