package com.dt.streamz.diag

import android.content.Context
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Fire-and-forget auto error reporting. When playback or stream resolution
 * fails, the app POSTs a small failure event to the telemetry Worker so dead
 * embed mirrors / scraper drift surface without the user screenshotting the
 * in-app debug log. Failures only — no viewing habits.
 *
 * Opt-out via Settings (default on). Never throws into the caller.
 */
object Telemetry {

    private const val URL = "https://dt-streamz-telemetry.maxxtopia.workers.dev/report"
    private const val PREFS = "telemetry"
    private const val KEY_ENABLED = "enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMedia = "application/json".toMediaType()

    @Volatile private var enabledFlag = true
    @Volatile private var appVersion = "?"

    fun init(context: Context, version: String) {
        appVersion = version
        enabledFlag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun isEnabled(): Boolean = enabledFlag

    fun setEnabled(context: Context, enabled: Boolean) {
        enabledFlag = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Report a failure event. Keys with null values are dropped. */
    fun report(kind: String, fields: Map<String, Any?>) {
        if (!enabledFlag) return
        scope.launch {
            runCatching {
                val obj = JSONObject()
                obj.put("kind", kind)
                obj.put("app", appVersion)
                for ((k, v) in fields) if (v != null) obj.put(k, v)
                val req = Request.Builder()
                    .url(URL)
                    .post(obj.toString().toRequestBody(jsonMedia))
                    .build()
                Http.client.newCall(req).execute().use { /* fire and forget */ }
            }.onFailure { DebugLog.d(TAG, "telemetry post failed: ${it.message}") }
        }
    }

    /**
     * Manually upload the full in-app debug log for remote diagnosis.
     * Triggered by a Settings button — NOT gated on [enabledFlag], because
     * the user explicitly asked to send it (the auto-report opt-out only
     * governs the silent failure pings). Stored as a single `debug_dump`
     * event the triage endpoint can return. Returns true on a 2xx.
     */
    suspend fun sendDebugLog(lines: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // The telemetry endpoint deliberately keeps POSTs small. A full
            // 400-line ring snapshot can exceed that limit and used to make
            // the Settings button look broken even though the network was
            // fine. Keep the newest, most diagnostic lines and fit the final
            // JSON below the endpoint's conservative request ceiling.
            val compact = lines
                .takeLast(MAX_DEBUG_UPLOAD_LINES)
                .map { it.takeLast(MAX_DEBUG_LINE_CHARS) }
                .toMutableList()
            val obj = JSONObject()
            obj.put("kind", "debug_dump")
            obj.put("app", appVersion)
            obj.put("count", lines.size)
            val arr = JSONArray()
            compact.forEach { arr.put(it) }
            obj.put("lines", arr)
            while (obj.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_DEBUG_UPLOAD_BYTES && compact.size > 1) {
                compact.removeAt(0)
                arr.remove(0)
            }
            obj.put("sent", compact.size)
            val payload = obj.toString()
            val req = Request.Builder()
                .url(URL)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(jsonMedia))
                .build()
            Http.client.newCall(req).execute().use {
                if (!it.isSuccessful) {
                    DebugLog.w(TAG, "debug log upload HTTP ${it.code}")
                }
                it.isSuccessful
            }
        }.onFailure { DebugLog.w(TAG, "debug log upload failed: ${it.message}") }
            .getOrDefault(false)
    }

    private const val TAG = "Telemetry"

    private const val MAX_DEBUG_UPLOAD_LINES = 36
    private const val MAX_DEBUG_LINE_CHARS = 140
    private const val MAX_DEBUG_UPLOAD_BYTES = 3_000
}
