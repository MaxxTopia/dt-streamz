package com.dt.streamz.data

import android.content.Context

/**
 * Per-server reliability tracker for the movie/TV embed mirrors. Records how
 * often each server actually delivered video vs failed (in SharedPreferences)
 * and exposes a [score] the router uses to try the most-likely-to-work server
 * FIRST instead of the provider's fixed order.
 *
 * Keyed by the embed host (e.g. "vidlink.pro"). A Bayesian prior keeps a brand
 * new server neutral (~0.5) rather than ranking it top or bottom on zero data,
 * and the counts are halved once they pass [CAP] so a server that was flaky a
 * while back can climb again as it starts working — no permanent lock-in.
 */
class ServerStatsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordSuccess(key: String) = bump(key, success = true)

    fun recordFailure(key: String) = bump(key, success = false)

    /** Reliability in [0,1]; higher = more likely to work. Neutral ≈ 0.5. */
    fun score(key: String): Double {
        if (key.isBlank()) return 0.5
        val (s, f) = read(key)
        return (s + PRIOR_S) / (s + f + PRIOR_TOTAL)
    }

    private fun bump(key: String, success: Boolean) {
        if (key.isBlank()) return
        val (rs, rf) = read(key)
        var s = rs
        var f = rf
        if (success) s++ else f++
        if (s + f > CAP) { s = (s + 1) / 2; f = (f + 1) / 2 }
        prefs.edit().putString(key, "$s,$f").apply()
    }

    private fun read(key: String): Pair<Int, Int> {
        val raw = prefs.getString(key, null) ?: return 0 to 0
        val parts = raw.split(',')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    companion object {
        private const val PREFS = "server_stats"
        private const val PRIOR_S = 1.0
        private const val PRIOR_TOTAL = 2.0
        private const val CAP = 24
    }
}
