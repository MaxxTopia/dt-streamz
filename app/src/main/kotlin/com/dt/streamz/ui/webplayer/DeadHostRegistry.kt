package com.dt.streamz.ui.webplayer

import android.net.Uri
import com.dt.streamz.diag.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime cache of embed hosts that have hit transport-level
 * errors (ERR_CONNECTION_CLOSED, ERR_CONNECTION_REFUSED, etc.) — the
 * signature of an ISP / system adblock / DNS block. Once a host is
 * marked dead, the WebPlayer skips any mirror whose URL hosts on it
 * instead of waiting LOAD_TIMEOUT_MS to discover the same outcome.
 *
 * Cleared on app process restart. We deliberately don't persist this:
 * networks change (hotel wifi / hotspot / VPN toggles), and a host
 * that was unreachable yesterday may be reachable today.
 */
object DeadHostRegistry {
    private const val TAG = "DeadHosts"
    private val dead: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun mark(host: String) {
        val normalized = host.lowercase()
        if (dead.add(normalized)) {
            DebugLog.i(TAG, "mark $normalized as dead — will skip on future picks this session")
        }
    }

    fun markIfHost(url: String) {
        runCatching { Uri.parse(url).host }.getOrNull()?.let { mark(it) }
    }

    fun isDead(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host in dead
    }

    fun snapshot(): Set<String> = dead.toSet()
}
