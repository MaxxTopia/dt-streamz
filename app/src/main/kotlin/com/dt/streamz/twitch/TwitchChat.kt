package com.dt.streamz.twitch

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Minimal read-only Twitch IRC client. Connects to irc.chat.twitch.tv:6697
 * over TLS as an anonymous `justinfan<N>` viewer (the canonical pattern for
 * read-only chat — no OAuth needed, no capability to send messages).
 *
 * Exposes the last [WINDOW] messages as a StateFlow so the overlay
 * composable can autoscroll without an explicit channel. Responds to the
 * server's PING to keep the connection alive.
 */
class TwitchChat(private val channel: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var activeSocket: Socket? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Closing the socket unblocks the blocking readLine() so the
        // coroutine actually winds down instead of dangling until the
        // server next sends a line.
        runCatching { activeSocket?.close() }
        activeSocket = null
    }

    /**
     * Connect-and-read loop with capped exponential backoff. Twitch IRC
     * drops idle/anonymous connections periodically; without reconnect the
     * overlay would silently freeze on the last message. A session that
     * survives 20s+ resets the backoff so a long watch doesn't accumulate
     * delay after one blip.
     */
    private suspend fun run() {
        val channelLc = channel.lowercase()
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            connectOnce(channelLc)
            if (!currentCoroutineContext().isActive) break
            val lasted = System.currentTimeMillis() - startedAt
            backoffMs = if (lasted > 20_000L) 1_000L else (backoffMs * 2).coerceAtMost(30_000L)
            Log.i(TAG, "chat dropped after ${lasted}ms; reconnecting in ${backoffMs}ms")
            delay(backoffMs)
        }
    }

    private fun connectOnce(channelLc: String) {
        val nick = "justinfan${Random.nextInt(10_000, 99_999)}"
        Log.i(TAG, "connecting as $nick, joining #$channelLc")

        runCatching {
            val socket = SSLSocketFactory.getDefault().createSocket(HOST, PORT)
            activeSocket = socket
            socket.use {
                val out = PrintWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8), true)
                val `in` = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))

                out.println("PASS SCHMOOPIIE")
                out.println("NICK $nick")
                out.println("JOIN #$channelLc")

                while (true) {
                    val line = `in`.readLine() ?: break
                    handleLine(line, out)
                }
            }
        }.onFailure { Log.w(TAG, "chat connection dropped", it) }
        activeSocket = null
    }

    private fun handleLine(line: String, out: PrintWriter) {
        if (line.startsWith("PING")) {
            out.println("PONG ${line.removePrefix("PING ")}")
            return
        }
        val m = PRIVMSG.matchEntire(line) ?: return
        val user = m.groupValues[1]
        val text = m.groupValues[2]
        val cur = _messages.value
        val next = (cur + ChatMessage(user, text)).takeLast(WINDOW)
        _messages.value = next
    }

    companion object {
        private const val TAG = "TwitchChat"
        private const val HOST = "irc.chat.twitch.tv"
        private const val PORT = 6697
        private const val WINDOW = 100

        // Captures "<nick>!...@... PRIVMSG #channel :message text"
        // Ignores IRCv3 tags for MVP (we'd prefix with @... first).
        private val PRIVMSG = Regex(
            """:(\w+)![^\s]+ PRIVMSG #\S+ :(.*)""",
        )
    }
}

data class ChatMessage(val user: String, val text: String)
