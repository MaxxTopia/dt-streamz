package com.dt.streamz.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring-buffer log appender so soak-test failures on the box
 * can be screenshotted from Settings -> "View debug log" without ADB.
 *
 * Mirrors writes to android.util.Log so logcat keeps working for anyone
 * who does have cable access, and snapshots the same lines into a bounded
 * deque for the in-app viewer.
 */
object DebugLog {
    private const val MAX_LINES = 400

    private val buffer = ConcurrentLinkedDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(tag: String, message: String) {
        append("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        append("W", tag, suffixed(message, throwable))
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        append("E", tag, suffixed(message, throwable))
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    fun d(tag: String, message: String) {
        append("D", tag, message)
        Log.d(tag, message)
    }

    fun snapshot(): List<String> = buffer.toList()

    fun clear() {
        buffer.clear()
    }

    private fun suffixed(message: String, throwable: Throwable?): String =
        if (throwable == null) message else "$message · ${throwable.javaClass.simpleName}: ${throwable.message}"

    private fun append(level: String, tag: String, message: String) {
        val line = "${timeFmt.format(Date())} $level/$tag: $message"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.pollFirst()
    }
}
