package com.dt.streamz.diag

import android.content.Context
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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

    private const val TAG = "Telemetry"
}
