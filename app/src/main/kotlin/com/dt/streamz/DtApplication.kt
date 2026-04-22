package com.dt.streamz

import android.app.Application
import com.dt.streamz.adblock.HostBlocker
import com.dt.streamz.config.ScraperConfigLoader
import com.dt.streamz.scraper.ProviderRegistry
import com.dt.streamz.scraper.anicrush.AnicrushProvider
import com.dt.streamz.scraper.anikai.AnikaiProvider
import com.dt.streamz.scraper.fixtures.FixturesProvider
import com.dt.streamz.scraper.gogoanimeby.GogoAnimeByProvider
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

    override fun onCreate() {
        super.onCreate()
        scraperConfig = ScraperConfigLoader(this)
        appScope.launch { scraperConfig.loadCachedThenRefresh() }

        providerRegistry = ProviderRegistry(
            providers = listOf(
                FixturesProvider(),
                GogoAnimeByProvider(),
                AnikaiProvider(),
                AnicrushProvider(),
            ),
        )

        hostBlocker = HostBlocker(this)
        appScope.launch {
            hostBlocker.loadSeedFromAssets()
            // Best-effort refresh from upstream; failure keeps the seed list.
            hostBlocker.refreshFromUrl(HostBlocker.UPSTREAM_HOSTS_URL)
        }
    }
}
