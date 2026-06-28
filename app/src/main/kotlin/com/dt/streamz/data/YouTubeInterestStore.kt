package com.dt.streamz.data

import android.content.Context

/**
 * YouTube-ONLY interest model — the on-device signal behind the genuinely
 * personalised YouTube "Recommended" grid.
 *
 * Deliberately separate from [InterestStore] (which blends every kind of
 * in-app activity for the per-tab "For You" rows). This store is fed *only*
 * by what you do on YouTube:
 *   - searches you run in the YouTube tab ([recordSearch]) — explicit intent,
 *   - YouTube videos you actually open ([recordWatch]) — recorded as bare
 *     11-char video IDs so the grid can pull YouTube's OWN watch-next graph
 *     (`relatedVideos`) for them, which is the real personalisation, no login
 *     required.
 *
 * Movies / shows / anime never reach this store, so they can't drift the
 * YouTube recommendations or anything else on YouTube.
 *
 * Everything stays in SharedPreferences on the box; there's no account and it
 * can be turned off or wiped from Settings (shares the one personalisation
 * toggle with [InterestStore]). Search signals are time-decayed (14-day
 * half-life) so the grid tracks what you're into lately; watch IDs are kept in
 * recency order so the freshest watches drive the strongest recommendations.
 */
class YouTubeInterestStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasData(): Boolean =
        !prefs.getString(KEY_SEARCHES, null).isNullOrBlank() ||
            !prefs.getString(KEY_WATCHES, null).isNullOrBlank()

    fun clear() {
        prefs.edit().remove(KEY_SEARCHES).remove(KEY_WATCHES).apply()
    }

    /** A search the user ran in the YouTube tab. */
    fun recordSearch(query: String) {
        if (!isEnabled()) return
        val clean = query.replace('\t', ' ').replace('\n', ' ').trim()
        if (clean.length < 2) return
        val rows = (readRows(KEY_SEARCHES) + Row(System.currentTimeMillis(), clean)).takeLast(MAX_SEARCHES)
        write(KEY_SEARCHES, rows)
    }

    /** A YouTube video the user opened. [videoId] is the 11-char watch ID. */
    fun recordWatch(videoId: String) {
        if (!isEnabled()) return
        val clean = videoId.trim()
        if (clean.length < 6) return
        // Drop any prior occurrence so the newest watch floats to the end and
        // the list stays distinct without growing unbounded on re-watches.
        val rows = (readRows(KEY_WATCHES).filter { it.text != clean } + Row(System.currentTimeMillis(), clean))
            .takeLast(MAX_WATCHES)
        write(KEY_WATCHES, rows)
    }

    /**
     * Recent distinct YouTube search phrases, most-relevant first (time-decayed,
     * deduped). Empty when personalisation is off or there's no history yet
     * (caller falls back to the generic seeds).
     */
    fun topSearchTerms(n: Int): List<String> {
        if (!isEnabled() || n <= 0) return emptyList()
        val rows = readRows(KEY_SEARCHES)
        if (rows.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val score = LinkedHashMap<String, Double>()
        for (r in rows) {
            val p = r.text.lowercase().trim()
            if (p.length !in 2..60) continue
            val d = Math.pow(0.5, (now - r.ts).toDouble() / HALF_LIFE_MS)
            score[p] = (score[p] ?: 0.0) + d
        }
        return score.entries.sortedByDescending { it.value }.map { it.key }.take(n)
    }

    /**
     * Recent distinct watched video IDs, newest first — seeds for YouTube's
     * related-video graph. Empty when personalisation is off / no history.
     */
    fun recentWatchIds(n: Int): List<String> {
        if (!isEnabled() || n <= 0) return emptyList()
        return readRows(KEY_WATCHES).map { it.text }.asReversed().distinct().take(n)
    }

    private data class Row(val ts: Long, val text: String)

    private fun readRows(key: String): List<Row> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
            Row(ts, parts[1])
        }
    }

    private fun write(key: String, rows: List<Row>) {
        prefs.edit().putString(key, rows.joinToString("\n") { "${it.ts}\t${it.text}" }).apply()
    }

    companion object {
        private const val PREFS = "yt_interests"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SEARCHES = "searches"
        private const val KEY_WATCHES = "watches"
        private const val MAX_SEARCHES = 100
        private const val MAX_WATCHES = 40
        private const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    }
}
