package com.dt.streamz.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches the latest ScraperConfig from a public URL, falls back to the
 * last cached copy on disk, and finally to a bundled default. Refreshed
 * on launch and every REFRESH_INTERVAL_HOURS after.
 */
class ScraperConfigLoader(context: Context) {

    private val cacheFile = File(context.filesDir, "scrapers.json")
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(empty())
    val config: StateFlow<ScraperConfig> = _config.asStateFlow()

    suspend fun loadCachedThenRefresh() {
        readCache()?.let { _config.value = it }
        refresh()
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(REMOTE_URL).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("empty body")
                val parsed = json.decodeFromString(ScraperConfig.serializer(), body)
                cacheFile.writeText(body)
                _config.value = parsed
                Log.i(TAG, "Loaded scraper config v${parsed.schemaVersion} (${parsed.providers.size} providers)")
                true
            }
        }.onFailure { Log.w(TAG, "Remote config refresh failed", it) }.getOrDefault(false)
    }

    private fun readCache(): ScraperConfig? =
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            json.decodeFromString(ScraperConfig.serializer(), cacheFile.readText())
        }.getOrNull()

    private fun empty() = ScraperConfig()

    companion object {
        private const val TAG = "ScraperConfigLoader"
        const val REMOTE_URL =
            "https://raw.githubusercontent.com/dtman-gif/dt-streamz-config/main/scrapers.json"
        const val REFRESH_INTERVAL_HOURS = 6L
    }
}
