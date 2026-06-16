package com.dt.streamz.data

import android.content.Context

/**
 * On-device interest model — the local "algorithm" behind the personalised
 * YouTube grid and the per-tab "For You" rows.
 *
 * It records lightweight signals (searches you run, titles you play) entirely
 * in SharedPreferences. Nothing leaves the box, there's no account, and it can
 * be turned off or wiped from Settings. Signals are time-decayed (14-day
 * half-life) so the model tracks what you're into *lately*, then distilled into
 * a ranked list of interest terms via [topTerms].
 *
 * Storage is a bounded, newline-delimited log of `ts \t weight \t isQuery \t
 * text` rows (newest last, capped at [MAX_EVENTS]) — small, sync to read, and
 * trivially serialisable without a schema.
 */
class InterestStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasData(): Boolean = prefs.getString(KEY_EVENTS, null).isNullOrBlank().not()

    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }

    /** A search the user ran — the strongest explicit-intent signal. */
    fun recordSearch(query: String) = record(query, WEIGHT_SEARCH, isQuery = true)

    /** A title the user actually played. */
    fun recordWatch(title: String) = record(title, WEIGHT_WATCH, isQuery = false)

    private fun record(text: String, weight: Int, isQuery: Boolean) {
        if (!isEnabled()) return
        val clean = text.replace('\t', ' ').replace('\n', ' ').trim()
        if (clean.length < 2) return
        val ev = Event(System.currentTimeMillis(), weight, isQuery, clean)
        val events = (readEvents() + ev).takeLast(MAX_EVENTS)
        prefs.edit().putString(KEY_EVENTS, encode(events)).apply()
    }

    /**
     * Top interest terms, most-relevant first. Blends recent distinct search
     * phrases (high intent — used verbatim as search seeds) with the
     * highest-weighted single tokens across all signals, time-decayed. Returns
     * at most [n]. Empty when personalization is off or there's no history yet
     * (callers treat that as "fall back to the non-personalized behavior").
     */
    fun topTerms(n: Int): List<String> {
        if (!isEnabled() || n <= 0) return emptyList()
        val events = readEvents()
        if (events.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        fun decay(ts: Long): Double = Math.pow(0.5, (now - ts).toDouble() / HALF_LIFE_MS)

        val phraseScore = LinkedHashMap<String, Double>()
        val tokenScore = HashMap<String, Double>()
        for (e in events) {
            val d = decay(e.ts) * e.weight
            if (e.isQuery) {
                val p = e.text.lowercase().trim()
                if (p.length in 2..40) phraseScore[p] = (phraseScore[p] ?: 0.0) + d * 1.5
            }
            for (tok in tokenize(e.text)) {
                tokenScore[tok] = (tokenScore[tok] ?: 0.0) + d
            }
        }
        val topPhrases = phraseScore.entries.sortedByDescending { it.value }.map { it.key }
        val topTokens = tokenScore.entries.sortedByDescending { it.value }.map { it.key }

        // Lead with explicit search phrases, then fill with strong tokens that
        // aren't already covered by a chosen phrase.
        val out = LinkedHashSet<String>()
        for (p in topPhrases) {
            out.add(p)
            if (out.size >= n) return out.toList()
        }
        for (t in topTokens) {
            if (out.any { it.contains(t) }) continue
            out.add(t)
            if (out.size >= n) break
        }
        return out.toList().take(n)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(TOKEN_SPLIT)
            .filter { it.length >= 3 && it !in STOPWORDS && it.any { c -> !c.isDigit() } }

    private data class Event(val ts: Long, val weight: Int, val isQuery: Boolean, val text: String)

    private fun readEvents(): List<Event> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
            val w = parts[1].toIntOrNull() ?: return@mapNotNull null
            Event(ts, w, parts[2] == "1", parts[3])
        }
    }

    private fun encode(events: List<Event>): String =
        events.joinToString("\n") {
            "${it.ts}\t${it.weight}\t${if (it.isQuery) 1 else 0}\t${it.text}"
        }

    companion object {
        private const val PREFS = "interests"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 200
        private const val WEIGHT_SEARCH = 3
        private const val WEIGHT_WATCH = 2
        private const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
        private val TOKEN_SPLIT = Regex("[^a-z0-9]+")

        // Generic words that carry no taste signal — dropped from tokenization
        // so we key on the meaningful terms (titles, creators, franchises).
        private val STOPWORDS = setOf(
            "the", "and", "for", "you", "your", "with", "this", "that", "from",
            "official", "video", "full", "episode", "season", "watch", "movie",
            "movies", "trailer", "new", "best", "how", "what", "why", "who",
            "feat", "vs", "part", "live", "stream", "hd", "free", "online",
        )
    }
}
