package com.dt.streamz.data

import android.content.Context

/** A local, account-free YouTube resume row shown on the YouTube tab. */
data class YouTubeWatchEntry(
    val videoId: String,
    val title: String,
    val poster: String?,
    val timestamp: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * YouTube-ONLY interest model — the on-device signal behind the genuinely
 * personalised YouTube "Recommended" grid.
 *
 * Deliberately separate from [InterestStore] (which blends every kind of
 * in-app activity for the per-tab "For You" rows). This store is fed *only*
 * by what you do on YouTube:
 *   - searches you run in the YouTube tab ([recordSearch]) — explicit intent,
 *   - YouTube videos you actually open ([recordWatch]) — recorded as bare
 *     11-char video IDs plus a local title snapshot so the grid can pull
 *     YouTube's OWN watch-next graph (`relatedVideos`) and recover title-based
 *     context after a process restart, with no login required.
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
        prefs.edit()
            .remove(KEY_SEARCHES)
            .remove(KEY_WATCHES)
            .remove(KEY_WATCH_TITLES)
            .remove(KEY_WATCH_PROGRESS)
            .apply()
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
    fun recordWatch(videoId: String, title: String? = null, poster: String? = null) {
        if (!isEnabled()) return
        val clean = videoId.trim()
        if (clean.length < 6) return
        val now = System.currentTimeMillis()
        // Drop any prior occurrence so the newest watch floats to the end and
        // the list stays distinct without growing unbounded on re-watches.
        val rows = (readRows(KEY_WATCHES).filter { it.text != clean } + Row(now, clean))
            .takeLast(MAX_WATCHES)
        write(KEY_WATCHES, rows)
        val cleanTitle = title
            ?.replace('\t', ' ')
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(140)
            .orEmpty()
        if (cleanTitle.length >= 3) {
            val titleRows = (
                readRows(KEY_WATCH_TITLES).filter {
                    it.text.substringBefore('|') != clean
                } + Row(now, "$clean|$cleanTitle")
            ).takeLast(MAX_WATCH_TITLES)
            write(KEY_WATCH_TITLES, titleRows)
        }
        upsertWatchEntry(clean, cleanTitle, poster)
    }

    /** Recent watched videos with local thumbnails and resume positions. */
    fun watchEntries(n: Int = 8): List<YouTubeWatchEntry> {
        if (!isEnabled() || n <= 0) return emptyList()
        return readWatchEntries()
            .asSequence()
            .filter { it.title.length >= 3 }
            .filterNot { isFinished(it) }
            .sortedByDescending { it.timestamp }
            .take(n)
            .toList()
    }

    /** Resume position for a video before its next native/embed attempt. */
    fun resumePosition(videoId: String): Long =
        if (!isEnabled()) 0L else readWatchEntries()
            .firstOrNull { it.videoId == videoId.trim() }
            ?.takeUnless(::isFinished)
            ?.positionMs
            ?.coerceAtLeast(0L)
            ?: 0L

    /** Persist progress without changing the interest signal or row ordering. */
    fun updateWatchProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (!isEnabled()) return
        val clean = videoId.trim()
        if (clean.length < 6) return
        val current = readWatchEntries()
        var changed = false
        val updated = current.map { entry ->
            if (entry.videoId != clean) return@map entry
            changed = true
            entry.copy(
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = if (durationMs > 0) durationMs else entry.durationMs,
            )
        }
        if (changed) writeWatchEntries(updated)
    }

    /** Remove a row from the local resume shelf while retaining its interests. */
    fun removeWatch(videoId: String) {
        val clean = videoId.trim()
        val current = readWatchEntries()
        val filtered = current.filterNot { it.videoId == clean }
        if (filtered.size != current.size) writeWatchEntries(filtered)
    }

    /** A clean end removes the item from Continue Watching but not from history. */
    fun completeWatch(videoId: String) = removeWatch(videoId)

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

    /** Recent watched titles, newest first, for title-based recommendation
     * recovery after a process restart. */
    fun recentWatchTitles(n: Int): List<String> {
        if (!isEnabled() || n <= 0) return emptyList()
        return readRows(KEY_WATCH_TITLES)
            .asReversed()
            .mapNotNull { it.text.substringAfter('|', "").trim().takeIf { t -> t.length >= 3 } }
            .distinctBy { it.lowercase() }
            .take(n)
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

    private fun upsertWatchEntry(videoId: String, title: String, poster: String?) {
        val previous = readWatchEntries().firstOrNull { it.videoId == videoId }
        val entry = YouTubeWatchEntry(
            videoId = videoId,
            title = title.ifBlank { previous?.title.orEmpty() },
            poster = poster.cleanField().takeIf { it.isNotBlank() } ?: previous?.poster,
            timestamp = System.currentTimeMillis(),
            positionMs = previous?.takeUnless(::isFinished)?.positionMs ?: 0L,
            durationMs = previous?.takeUnless(::isFinished)?.durationMs ?: 0L,
        )
        val rest = readWatchEntries().filterNot { it.videoId == videoId }
        writeWatchEntries((listOf(entry) + rest).take(MAX_WATCH_PROGRESS_ROWS))
    }

    private fun readWatchEntries(): List<YouTubeWatchEntry> =
        prefs.getString(KEY_WATCH_PROGRESS, null)
            ?.split('\n')
            ?.mapNotNull { line ->
                val parts = line.split('\t', limit = 6)
                if (parts.size < 6) return@mapNotNull null
                val id = parts[0].trim()
                val timestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
                val position = parts[2].toLongOrNull() ?: 0L
                val duration = parts[3].toLongOrNull() ?: 0L
                if (id.length < 6) return@mapNotNull null
                YouTubeWatchEntry(
                    videoId = id,
                    title = parts[4].trim(),
                    poster = parts[5].trim().takeIf { it.isNotBlank() },
                    timestamp = timestamp,
                    positionMs = position.coerceAtLeast(0L),
                    durationMs = duration.coerceAtLeast(0L),
                )
            }
            .orEmpty()

    private fun writeWatchEntries(entries: List<YouTubeWatchEntry>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_WATCH_PROGRESS).apply()
            return
        }
        val raw = entries.joinToString("\n") { entry ->
            listOf(
                entry.videoId.cleanField(),
                entry.timestamp.toString(),
                entry.positionMs.toString(),
                entry.durationMs.toString(),
                entry.title.cleanField().take(140),
                entry.poster.cleanField().take(600),
            ).joinToString("\t")
        }
        prefs.edit().putString(KEY_WATCH_PROGRESS, raw).apply()
    }

    private fun isFinished(entry: YouTubeWatchEntry): Boolean =
        entry.durationMs > 0 && entry.positionMs >=
            (entry.durationMs - FINISHED_END_GUARD_MS).coerceAtLeast(0L)

    private fun String?.cleanField(): String =
        this.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    companion object {
        private const val PREFS = "yt_interests"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SEARCHES = "searches"
        private const val KEY_WATCHES = "watches"
        private const val KEY_WATCH_TITLES = "watch_titles"
        private const val KEY_WATCH_PROGRESS = "watch_progress"
        private const val MAX_SEARCHES = 100
        private const val MAX_WATCHES = 40
        private const val MAX_WATCH_TITLES = 40
        private const val MAX_WATCH_PROGRESS_ROWS = 20
        private const val FINISHED_END_GUARD_MS = 20_000L
        private const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    }
}
