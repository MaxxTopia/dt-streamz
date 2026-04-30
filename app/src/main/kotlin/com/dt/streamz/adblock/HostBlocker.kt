package com.dt.streamz.adblock

import android.content.Context
import android.util.Log
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimalist ad-host blocker for WebView requests. Loads a hosts-file-
 * format blocklist from bundled assets at startup, optionally merges a
 * larger list fetched from an upstream URL on launch. Matches requests
 * by exact host OR any parent domain.
 *
 * This is deliberately simpler than AdblockAndroid (Edsuns) — we sidestep
 * EasyList's cosmetic/element-hiding rules for now and stick to
 * hostname-based URL blocking, which kills 80%+ of embed-host ads.
 */
class HostBlocker(private val context: Context) {

    private val hostsRef = AtomicReference<Set<String>>(emptySet())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Mirror persisted toggle into the global flag at startup so the
        // first WebView opened post-launch sees the right value without
        // needing a Settings round-trip.
        enabledFlag = prefs.getBoolean(KEY_ENABLED, true)
    }

    /** Persist + reflect the toggle. False bypasses every block check. */
    fun setEnabled(enabled: Boolean) {
        enabledFlag = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun enabled(): Boolean = enabledFlag

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val set = hostsRef.get()
        if (set.isEmpty()) return false
        val lower = host.lowercase()
        if (lower in set) return true
        // Check each parent-domain suffix: ads.doubleclick.net -> doubleclick.net -> net
        var idx = lower.indexOf('.')
        while (idx in 0 until lower.length - 1) {
            val suffix = lower.substring(idx + 1)
            if (suffix.contains('.') && suffix in set) return true
            idx = lower.indexOf('.', idx + 1)
        }
        return false
    }

    suspend fun loadSeedFromAssets(paths: List<String> = DEFAULT_SEED_PATHS) = withContext(Dispatchers.IO) {
        val merged = mutableSetOf<String>()
        for (path in paths) {
            runCatching {
                context.assets.open(path).bufferedReader().use { reader ->
                    parseHostsLines(reader.readText(), into = merged)
                }
            }.onFailure { Log.w(TAG, "seed $path failed to load", it) }
        }
        hostsRef.set(merged.toSet())
        Log.i(TAG, "loaded ${merged.size} ad-hosts from seed assets")
        merged.size
    }

    suspend fun refreshFromUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", Http.DESKTOP_UA)
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "refresh $url -> HTTP ${resp.code}")
                    return@use false
                }
                val text = resp.body?.string() ?: return@use false
                val newHosts = mutableSetOf<String>()
                parseHostsLines(text, into = newHosts)
                if (newHosts.isEmpty()) return@use false
                // Merge with whatever seed we already have so refresh never
                // shrinks coverage even if the remote list is truncated.
                val merged = hostsRef.get().toMutableSet().apply { addAll(newHosts) }
                hostsRef.set(merged)
                Log.i(TAG, "refresh $url merged ${newHosts.size} hosts (total ${merged.size})")
                true
            }
        }.onFailure { Log.w(TAG, "refresh $url failed", it) }.getOrDefault(false)
    }

    fun size(): Int = hostsRef.get().size

    private fun parseHostsLines(text: String, into: MutableSet<String>) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // Common hosts-file shapes:
            //   0.0.0.0 ad.example.com
            //   127.0.0.1 ad.example.com
            //   ad.example.com
            val parts = line.split(WHITESPACE)
            val host = when {
                parts.size == 1 -> parts[0]
                parts.size >= 2 && parts[0] in IP_PREFIXES -> parts[1]
                else -> null
            } ?: continue
            if (host.length !in 4..253) continue
            if (!host.contains('.')) continue
            if (host.any { it == ' ' || it == '\t' || it == '<' || it == '>' }) continue
            into.add(host.lowercase().trimEnd('.'))
        }
    }

    companion object {
        private const val TAG = "HostBlocker"
        private const val PREFS = "adblock"
        private const val KEY_ENABLED = "enabled"

        // Volatile so WebView threads see toggle changes without a fence.
        @Volatile
        private var enabledFlag: Boolean = true

        /**
         * Static accessor for [WebPlayerScreen]'s WebViewClient — it runs
         * on a thread without a HostBlocker reference and we want the
         * cheapest possible early-out for shouldInterceptRequest.
         */
        fun isEnabled(): Boolean = enabledFlag

        private val WHITESPACE = Regex("\\s+")
        private val IP_PREFIXES = setOf("0.0.0.0", "127.0.0.1", "255.255.255.255", "::1")

        val DEFAULT_SEED_PATHS = listOf(
            "filters/peter_lowe_hosts.txt",
            "filters/streaming_extras.txt",
        )

        /**
         * Upstream hosts file to merge on launch. StevenBlack unified +
         * social + porn variant would be huge (~4MB, 200k entries); we
         * use just "unified" which is ~2MB and ~130k entries — generous
         * but fits in a WebView's hot path easily.
         */
        const val UPSTREAM_HOSTS_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    }
}
