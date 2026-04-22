package com.dt.streamz.networkmonitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Samples network latency every [SAMPLE_INTERVAL_MS] via a TCP :443 connect
 * (proxy for ICMP, which needs root). Rolls the last [WINDOW] samples into
 * a single [NetworkState] green/yellow/red signal that a Compose overlay
 * consumes.
 *
 * Target host defaults to 1.1.1.1 (baseline internet health). While a
 * stream is playing, callers can swap the target to the stream's CDN so
 * the indicator reflects the *actual* pipe the user is watching — call
 * [setActiveHost] from Player/WebPlayer screen enter/exit.
 *
 * Connectivity drops are reported instantly via ConnectivityManager's
 * default network callback, not the sampling loop — waiting up to 3s to
 * notice the wifi died is bad UX.
 */
class NetworkMonitor(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appCtx = context.applicationContext
    private val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val activeHost = AtomicReference(DEFAULT_HOST)
    private val samples = ArrayDeque<SampleResult>(WINDOW + 1)

    private val _state = MutableStateFlow(NetworkState(Tier.Unknown, null, connected = true))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var loopJob: Job? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _state.value = _state.value.copy(connected = true)
        }
        override fun onLost(network: Network) {
            _state.value = NetworkState(Tier.Red, null, connected = false)
        }
    }

    fun start() {
        if (loopJob?.isActive == true) return
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "network callback register failed", it) }
        loopJob = scope.launch {
            while (true) {
                val host = activeHost.get()
                val ms = tcpConnect(host, 443, TIMEOUT_MS)
                recordSample(host, ms)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
    }

    /**
     * Switch the latency target — a stream CDN host while playing, null
     * to fall back to [DEFAULT_HOST]. Trailing slashes / schemes tolerated.
     */
    fun setActiveHost(host: String?) {
        val cleaned = host
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HOST
        activeHost.set(cleaned)
    }

    private fun recordSample(host: String, ms: Long?) {
        synchronized(samples) {
            samples.addLast(SampleResult(host, ms))
            while (samples.size > WINDOW) samples.removeFirst()
            val failures = samples.count { it.ms == null }
            val successMs = samples.mapNotNull { it.ms }
            val avg = if (successMs.isEmpty()) null else successMs.average().toLong()
            val lossPct = failures.toFloat() / samples.size
            val tier = when {
                !_state.value.connected -> Tier.Red
                successMs.isEmpty() -> Tier.Red
                lossPct > PACKET_LOSS_RED -> Tier.Red
                avg == null -> Tier.Unknown
                avg <= GREEN_MS -> Tier.Green
                avg <= YELLOW_MS -> Tier.Yellow
                else -> Tier.Red
            }
            _state.value = NetworkState(
                tier = tier,
                latencyMs = avg,
                connected = _state.value.connected,
            )
        }
    }

    private suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), timeoutMs)
                }
                (System.nanoTime() - start) / 1_000_000L
            }.getOrNull()
        }

    private data class SampleResult(val host: String, val ms: Long?)

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val SAMPLE_INTERVAL_MS = 3_000L
        private const val TIMEOUT_MS = 2_000
        private const val WINDOW = 10
        private const val GREEN_MS = 80L
        private const val YELLOW_MS = 250L
        private const val PACKET_LOSS_RED = 0.02f

        const val DEFAULT_HOST = "1.1.1.1"
    }
}

enum class Tier { Green, Yellow, Red, Unknown }

data class NetworkState(
    val tier: Tier,
    val latencyMs: Long?,
    val connected: Boolean,
)
