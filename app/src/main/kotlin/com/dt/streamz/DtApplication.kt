package com.dt.streamz

import android.app.Application
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
    }
}
