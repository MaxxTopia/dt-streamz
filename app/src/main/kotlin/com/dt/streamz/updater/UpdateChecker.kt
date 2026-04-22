package com.dt.streamz.updater

import android.util.Log
import com.dt.streamz.BuildConfig
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Polls GitHub Releases for the `dt-streamz` repo and exposes the
 * newest tag vs the currently running build. Anonymous (unauth'd)
 * API calls are rate-limited to 60/hour/IP which is plenty for a
 * once-per-launch check.
 */
class UpdateChecker(
    private val repoOwner: String = DEFAULT_OWNER,
    private val repoName: String = DEFAULT_REPO,
) {

    data class Update(
        val tagName: String,
        val apkUrl: String,
        val releaseNotes: String?,
    )

    suspend fun checkForUpdate(): Update? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
        val body = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "dt-streamz/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "releases/latest -> HTTP ${resp.code}")
                    return@use null
                }
                resp.body?.string()
            }
        }.onFailure { Log.w(TAG, "releases check failed", it) }.getOrNull()
            ?: return@withContext null

        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext null
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val notes = obj["body"]?.jsonPrimitive?.contentOrNull
        val assets = obj["assets"] as? JsonArray ?: return@withContext null
        val apkAsset = assets.firstOrNull {
            (it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "")
                .endsWith(".apk", ignoreCase = true)
        } ?: return@withContext null
        val apkUrl = apkAsset.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext null

        val latest = normalize(tag)
        val current = normalize(BuildConfig.VERSION_NAME)
        if (compare(latest, current) > 0) {
            Log.i(TAG, "update available: $tag (current ${BuildConfig.VERSION_NAME})")
            Update(tagName = tag, apkUrl = apkUrl, releaseNotes = notes)
        } else {
            Log.i(TAG, "up to date ($tag vs ${BuildConfig.VERSION_NAME})")
            null
        }
    }

    private fun normalize(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-').split('.')
            .mapNotNull { it.toIntOrNull() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    companion object {
        private const val TAG = "UpdateChecker"
        // TODO user: set these to the real private repo. Until then,
        // the check no-ops cleanly on 404.
        private const val DEFAULT_OWNER = "dtman-gif"
        private const val DEFAULT_REPO = "dt-streamz"
    }
}
