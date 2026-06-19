package com.dt.streamz.data

import android.content.Context

/**
 * Small, synchronous SharedPreferences store for the user's playback
 * preferences — the things the player needs to read on every launch:
 *
 *   - [captionsOn]   remembered CC choice (null = no choice made yet, so the
 *                    player falls back to the per-content default; YouTube
 *                    starts OFF, never auto-on, per the product rule).
 *   - [qualityCap]   max video height the YouTube extractor will pick.
 *
 * Mirrors [InterestStore]'s pattern: plain prefs, no schema, read off the main
 * thread is cheap. Nothing here leaves the box.
 */
class PlaybackPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Captions (remembered CC choice) ---

    /** null = user hasn't chosen yet; true/false = their last explicit choice. */
    fun captionsOn(): Boolean? =
        if (prefs.contains(KEY_CAPTIONS)) prefs.getBoolean(KEY_CAPTIONS, false) else null

    fun setCaptionsOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_CAPTIONS, on).apply()
    }

    // --- Video quality ---

    fun quality(): Quality =
        runCatching { Quality.valueOf(prefs.getString(KEY_QUALITY, Quality.AUTO.name)!!) }
            .getOrDefault(Quality.AUTO)

    fun setQuality(q: Quality) {
        prefs.edit().putString(KEY_QUALITY, q.name).apply()
    }

    /** Max video height the extractor should pick, derived from [quality]. */
    fun qualityCap(): Int = quality().maxHeight

    companion object {
        private const val PREFS = "playback_prefs"
        private const val KEY_CAPTIONS = "captions_on"
        private const val KEY_QUALITY = "quality"
    }
}

/** Video-quality preference; [maxHeight] caps the extractor's track pick. */
enum class Quality(val maxHeight: Int, val label: String) {
    DATA_SAVER(480, "Data saver (≤480p)"),
    AUTO(1080, "Auto (≤1080p)"),
    MAX(2160, "Max (up to 4K)"),
}
