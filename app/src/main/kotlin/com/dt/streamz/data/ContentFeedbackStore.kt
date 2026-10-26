package com.dt.streamz.data

import android.content.Context

/**
 * Small on-device preference model for explicit catalog feedback.
 *
 * The app never sends these choices anywhere. Keys are provider:id pairs so a
 * title dismissed on one source does not hide an unrelated title with the
 * same display name on another source.
 */
class ContentFeedbackStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDismissed(providerId: String, titleId: String): Boolean =
        key(providerId, titleId) in stringSet(KEY_DISMISSED)

    fun isLiked(providerId: String, titleId: String): Boolean =
        key(providerId, titleId) in stringSet(KEY_LIKED)

    fun dismissedKeys(): Set<String> = stringSet(KEY_DISMISSED)

    /** A stable score used by For You ranking. Dismissal wins over a prior like. */
    fun score(providerId: String, titleId: String): Int = when {
        isDismissed(providerId, titleId) -> -100
        isLiked(providerId, titleId) -> 10
        else -> 0
    }

    fun markMoreLikeThis(providerId: String, titleId: String) {
        val clean = key(providerId, titleId)
        val liked = stringSet(KEY_LIKED).toMutableSet().apply { add(clean) }
        val dismissed = stringSet(KEY_DISMISSED).toMutableSet().apply { remove(clean) }
        prefs.edit()
            .putStringSet(KEY_LIKED, liked)
            .putStringSet(KEY_DISMISSED, dismissed)
            .apply()
    }

    fun markNotInterested(providerId: String, titleId: String) {
        val clean = key(providerId, titleId)
        val dismissed = stringSet(KEY_DISMISSED).toMutableSet().apply { add(clean) }
        val liked = stringSet(KEY_LIKED).toMutableSet().apply { remove(clean) }
        prefs.edit()
            .putStringSet(KEY_DISMISSED, dismissed)
            .putStringSet(KEY_LIKED, liked)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DISMISSED).remove(KEY_LIKED).apply()
    }

    private fun stringSet(name: String): Set<String> =
        prefs.getStringSet(name, emptySet()).orEmpty().toSet()

    private fun key(providerId: String, titleId: String): String =
        "${providerId.trim()}:${titleId.trim()}"

    companion object {
        private const val PREFS = "content_feedback"
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_LIKED = "liked"
    }
}
