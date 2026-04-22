package com.dt.streamz

import android.app.Application
import com.dt.streamz.config.ScraperConfigLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DtApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var scraperConfig: ScraperConfigLoader
        private set

    override fun onCreate() {
        super.onCreate()
        scraperConfig = ScraperConfigLoader(this)
        appScope.launch { scraperConfig.loadCachedThenRefresh() }
    }
}
