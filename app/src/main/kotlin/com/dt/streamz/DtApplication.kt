package com.dt.streamz

import android.app.Application
import com.dt.streamz.adblock.BlocklistRefreshWorker
import com.dt.streamz.adblock.HostBlocker
import com.dt.streamz.config.ScraperConfigLoader
import com.dt.streamz.data.ContinueWatchingStore
import com.dt.streamz.networkmonitor.NetworkMonitor
import com.dt.streamz.scraper.ProviderRegistry
import com.dt.streamz.scraper.anicrush.AnicrushProvider
import com.dt.streamz.scraper.anikai.AnikaiProvider
import com.dt.streamz.scraper.anikai.AnikaiResolver
import com.dt.streamz.scraper.fixtures.FixturesProvider
import com.dt.streamz.scraper.gogoanimeby.GogoAnimeByProvider
import com.dt.streamz.scraper.vidsrc.VidSrcProvider
import com.dt.streamz.twitch.PinnedChannelsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DtApplication : Application() {

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

    override fun onCreate() {
        super.onCreate()
        scraperConfig = ScraperConfigLoader(this)
        appScope.launch { scraperConfig.loadCachedThenRefresh() }

        val anikaiResolver = AnikaiResolver(this)
        providerRegistry = ProviderRegistry(
            providers = listOf(
                FixturesProvider(),
                GogoAnimeByProvider(),
                VidSrcProvider(),
                AnikaiProvider(resolver = anikaiResolver),
                AnicrushProvider(),
            ),
        )

        hostBlocker = HostBlocker(this)
        appScope.launch {
            // Seed is bundled in assets — always available instantly on
            // cold start even with no network. The upstream merge is now
            // handled by BlocklistRefreshWorker on a weekly cadence.
            hostBlocker.loadSeedFromAssets()
        }
        BlocklistRefreshWorker.schedule(this)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.start()

        pinnedChannels = PinnedChannelsStore(this)
        continueWatching = ContinueWatchingStore(this)
    }
}
