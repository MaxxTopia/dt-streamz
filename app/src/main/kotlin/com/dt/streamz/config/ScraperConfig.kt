package com.dt.streamz.config

import kotlinx.serialization.Serializable

/**
 * Remote-updatable config describing how to scrape each supported site.
 * Hosted publicly at REMOTE_URL. Edit + push to patch a broken scraper
 * without building a new APK.
 */
@Serializable
data class ScraperConfig(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val providers: Map<String, ProviderConfig> = emptyMap(),
)

@Serializable
data class ProviderConfig(
    val enabled: Boolean = true,
    val baseUrl: String,
    val userAgent: String = DEFAULT_UA,
    val headers: Map<String, String> = emptyMap(),
    val endpoints: Map<String, String> = emptyMap(),
    val selectors: Map<String, String> = emptyMap(),
)

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
