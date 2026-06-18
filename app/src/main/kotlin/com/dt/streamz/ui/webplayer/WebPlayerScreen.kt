package com.dt.streamz.ui.webplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dt.streamz.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.DtApplication
import com.dt.streamz.adblock.HostBlocker
import com.dt.streamz.data.StreamSource
import com.dt.streamz.diag.DebugLog
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private sealed interface LoadState {
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Failed(val reason: String, val errorCode: Int = 0) : LoadState
}

// Adaptive per-mirror timeouts. The first mirror is the reliability-ranked
// best, so it gets a generous window to load + start; every fallback after it
// is a less-likely server we want to fail FAST through. These shorter windows
// (plus best-first ordering + the dead-host skip) are what make a working
// title load quickly and a broken one give up in seconds instead of ~20s.
//
// FIRST_* = mirror 0 (best server); NEXT_* = any fallback mirror.
private const val FIRST_LOAD_TIMEOUT_MS = 12_000L
private const val NEXT_LOAD_TIMEOUT_MS = 6_000L

/**
 * After page-finished, how long to wait for actual media traffic
 * (.m3u8 / .ts / .mp4 / etc.) before deciding the embed is a dead wrapper.
 * The best server gets the full window to fetch player JS, decode the URL and
 * start the first segment; fallbacks are abandoned quickly.
 */
private const val FIRST_MEDIA_CHECK_MS = 12_000L
private const val NEXT_MEDIA_CHECK_MS = 6_000L

/**
 * If no player element of any kind shows up within this window after
 * page-finish, treat the mirror as a blank/dead wrapper and walk on —
 * instead of waiting the full media-check window.
 */
private const val FIRST_BLANK_CUTOFF_MS = 6_000L
private const val NEXT_BLANK_CUTOFF_MS = 3_500L

/**
 * WebView error codes that indicate the embed host itself is unreachable
 * (TCP reset, DNS failure, TLS handshake refused, etc.). When we hit one
 * of these AND we have a [WebPlayerScreen.fallbacks] list, walking to the
 * next mirror is almost always the right move — the user shouldn't have
 * to back out and re-pick because vidsrc.to's CDN got banned that hour.
 *
 * 200-class errors (HTTP 4xx/5xx delivered as content) won't reach
 * onReceivedError as main-frame errors, so we don't need to enumerate
 * those here.
 */
private val TRANSPORT_ERROR_CODES = setOf(
    android.webkit.WebViewClient.ERROR_HOST_LOOKUP,
    android.webkit.WebViewClient.ERROR_CONNECT,
    android.webkit.WebViewClient.ERROR_TIMEOUT,
    android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
    android.webkit.WebViewClient.ERROR_PROXY_AUTHENTICATION,
    android.webkit.WebViewClient.ERROR_IO,
    android.webkit.WebViewClient.ERROR_REDIRECT_LOOP,
    android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME,
    android.webkit.WebViewClient.ERROR_BAD_URL,
    android.webkit.WebViewClient.ERROR_UNKNOWN,
)

/**
 * Renders an embed (iframe-style) URL inside a WebView so episodes from
 * providers that surface an opaque embed link (vidsrc, 2embed, megacloud
 * etc.) can play without a per-host Kotlin extractor.
 *
 * Headers are per-source: each `StreamSource.headers` flows in via
 * [Route.WebPlayer] -> here. If no `Referer` is supplied, we default to
 * the embed's own origin, which is the safe baseline most embeds expect.
 *
 * Shows a retry/back overlay if the page doesn't finish loading in time
 * or if the main frame reports a network error — WebView
 * otherwise just sits blank, indistinguishable from buffering.
 */
@Composable
fun WebPlayerScreen(
    embedUrl: String,
    headers: Map<String, String> = emptyMap(),
    fallbacks: List<StreamSource> = emptyList(),
    // Related-video resolver for YouTube autoplay. Given the current videoId,
    // returns related IDs (most-relevant first). Only used for `ytembed://`
    // sources; null disables cycling.
    youtubeRelated: (suspend (String) -> List<String>)? = null,
    // Rich related videos (title + thumbnail) for the in-player "Up next"
    // rail the user opens with D-pad DOWN. YouTube-only; null hides the rail.
    youtubeRelatedResults: (suspend (String) -> List<com.dt.streamz.data.SearchResult>)? = null,
    // Episodic embed (TV/anime): show the D-pad control bar (press UP) with
    // Prev/Next-episode, and wire best-effort auto-play-next.
    showNextPrev: Boolean = false,
    onNext: () -> Unit = {},
    onPrev: () -> Unit = {},
    // Fired when a non-YouTube embed reports the video ended (best-effort,
    // via a postMessage listener). Used for auto-play-next on TV/anime.
    onEmbedEnded: () -> Unit = {},
    // Shown as a last-resort "Choose server" action on the failure overlay
    // when every ranked mirror failed. Null hides it (single source / YouTube).
    onPickServer: (() -> Unit)? = null,
    onExit: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? DtApplication
    val blocker = app?.hostBlocker
    val monitor = app?.networkMonitor
    val serverStats = app?.serverStats

    // Active mirror walks: 0 = original embedUrl, 1.. = fallbacks[i-1].
    var mirrorIndex by remember(embedUrl) { mutableStateOf(0) }
    val activeSource = remember(embedUrl, mirrorIndex, fallbacks) {
        if (mirrorIndex == 0) ActiveSource(embedUrl, headers)
        else fallbacks.getOrNull(mirrorIndex - 1)
            ?.let { ActiveSource(it.url, it.headers) }
            ?: ActiveSource(embedUrl, headers)
    }
    val activeUrl = activeSource.url
    val totalMirrors = 1 + fallbacks.size

    val effectiveHeaders = remember(activeUrl, activeSource.headers) {
        if (activeSource.headers.keys.any { it.equals("Referer", ignoreCase = true) })
            activeSource.headers
        else activeSource.headers + ("Referer" to defaultReferer(activeUrl))
    }

    // CRITICAL: these states must NOT be `remember(activeUrl)` because
    // the WebView factory captures their setters/getters in closures
    // (onMainFrameFinished, onResourceBlocked, onMainFrameStarted, etc.).
    // factory runs ONCE; on subsequent mirror advances the WebView is
    // reused and the closures still write to the original MutableState.
    // If we re-key these to activeUrl, every advance creates a *new*
    // MutableState that the closures can't see — Loaded never arrives,
    // mirror walker hits the 20s LOAD_TIMEOUT every time.
    // Reset these manually in LaunchedEffect(activeUrl) below instead.
    var loadState by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var chromeClientRef by remember { mutableStateOf<FullscreenChromeClient?>(null) }
    var attempt by remember(activeUrl) { mutableStateOf(0) }
    val blockedHosts = remember { mutableStateListOf<String>() }
    var mainFrameHost by remember { mutableStateOf<String?>(hostOf(activeUrl)) }

    // True while the active mirror is our hosted YouTube embed (`ytembed://`).
    // Drives the D-pad handler: on our own wrapper page we seek via the
    // IFrame API; on cross-origin embeds we can only let the key through.
    // Held as a State object (not a delegate) so the WebView's key listener —
    // captured once in the factory — reads the live value.
    val ytEmbedActive = remember { mutableStateOf(activeUrl.startsWith(YT_EMBED_SCHEME)) }
    // YouTube autoplay cycle bookkeeping: the video currently playing in the
    // embed (starts as the source's id, then follows each loadVideoById) and
    // the set already played this session so related-video walks don't repeat.
    val ytScope = rememberCoroutineScope()
    val ytCurrentId = remember { mutableStateOf<String?>(null) }
    val ytSeen = remember { mutableSetOf<String>() }
    // Tracks what URL/embed we've actually told the WebView to load. We can't
    // rely on `webView.url` for this: a `ytembed://` source is loaded via
    // loadDataWithBaseURL, after which webView.url is the base origin, not the
    // source key — so the naive `wv.url != activeUrl` reload guard would loop.
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }

    // In-player "Up next" rail (YouTube). `relatedList` is loaded for the
    // currently-playing video; `railVisible` is toggled by D-pad DOWN. The
    // rail is native Compose, so while it's focused the WebView's key
    // listener is dormant — Left/Right move between cards instead of seeking.
    var relatedList by remember { mutableStateOf<List<com.dt.streamz.data.SearchResult>>(emptyList()) }
    var railVisible by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }

    // Netflix-style D-pad control bar (Prev/Next episode + Back), opened with
    // DPAD_UP on episodic embeds. Native Compose, owns focus while shown.
    var controlsVisible by remember { mutableStateOf(false) }
    val controlsFocus = remember { FocusRequester() }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(60)
            runCatching { controlsFocus.requestFocus() }
        }
    }

    // Load related videos for whatever is currently playing in the YT embed.
    LaunchedEffect(ytCurrentId.value, ytEmbedActive.value) {
        if (!ytEmbedActive.value || youtubeRelatedResults == null) {
            relatedList = emptyList()
            return@LaunchedEffect
        }
        val vid = ytCurrentId.value ?: return@LaunchedEffect
        relatedList = runCatching { youtubeRelatedResults(vid) }.getOrNull().orEmpty()
    }

    // Bring the rail's focus into Compose once it's shown (the WebView holds
    // focus during playback; this hands it to the first card).
    LaunchedEffect(railVisible) {
        if (railVisible) {
            delay(60) // let the rail compose before requesting focus
            runCatching { railFocus.requestFocus() }
        }
    }

    // Feed the reliability model: did the mirror at [url] deliver video or
    // fail? Skips the YouTube embed (not a user-pickable server) and records
    // by host so the next pick tries the most reliable server first. Declared
    // here so the load/media-gate effects below can call it.
    fun reportMirror(url: String, success: Boolean) {
        if (ytEmbedActive.value) return
        val host = hostOf(url) ?: return
        if (success) serverStats?.recordSuccess(host) else serverStats?.recordFailure(host)
    }

    DisposableEffect(activeUrl) {
        monitor?.setActiveHost(activeUrl)
        onDispose { monitor?.setActiveHost(null) }
    }

    // Per-mirror state reset — replaces the `remember(activeUrl)` keying
    // we can't use (see above). Runs whenever the WebPlayer points at a
    // new mirror URL: clear the per-mirror book-keeping so the next
    // load starts clean.
    LaunchedEffect(activeUrl) {
        loadState = LoadState.Loading
        blockedHosts.clear()
        mainFrameHost = hostOf(activeUrl)
        ytEmbedActive.value = activeUrl.startsWith(YT_EMBED_SCHEME)
        // Re-evaluate the cursor for the new mirror (YT embed hides it; a
        // watch-page / cross-origin fallback restores a normal pointer).
        webViewRef?.pointerIcon = pointerIconFor(ctx, activeUrl.startsWith(YT_EMBED_SCHEME))
        if (activeUrl.startsWith(YT_EMBED_SCHEME)) {
            val vid = activeUrl.removePrefix(YT_EMBED_SCHEME)
            ytCurrentId.value = vid
            ytSeen.clear()
            ytSeen.add(vid)
        }
    }

    // Auto-report a fully-failed playback (all mirrors exhausted) so dead
    // embeds surface without the user screenshotting the debug log.
    LaunchedEffect(loadState) {
        val f = loadState
        if (f is LoadState.Failed) {
            // Release WebView focus so the error overlay's Retry/Back buttons
            // can take it — otherwise the WebView keeps eating D-pad input.
            railVisible = false
            controlsVisible = false
            webViewRef?.clearFocus()
            com.dt.streamz.diag.Telemetry.report(
                "playback_failed",
                mapOf(
                    "embed" to hostOf(embedUrl),
                    "mirrors" to totalMirrors,
                    "code" to f.errorCode,
                    "reason" to f.reason.take(140),
                    "blocked" to blockedHosts.joinToString(",").take(200).ifBlank { null },
                ),
            )
        }
    }

    // Pre-flight: if this mirror's host has been marked dead this session
    // (transport err on a prior pick), skip it without waiting for a
    // 20-second timeout to confirm the obvious. Walks forward until we
    // find a candidate that hasn't been marked, or exhaust the list.
    LaunchedEffect(mirrorIndex, fallbacks, embedUrl) {
        if (DeadHostRegistry.isDead(activeUrl) && mirrorIndex + 1 < totalMirrors) {
            DebugLog.i(TAG, "skip mirror=$mirrorIndex (host dead) -> ${mirrorIndex + 1}")
            mirrorIndex += 1
        }
    }

    LaunchedEffect(activeUrl, attempt) {
        DebugLog.i(TAG, "load mirror=$mirrorIndex/${totalMirrors - 1} url=${truncUrl(activeUrl)}")
        if (loadState !is LoadState.Loading) loadState = LoadState.Loading
        // Poll-based timeout instead of single delay — bail the moment
        // loadState transitions out of Loading (page-finish, transport
        // error, etc.). Single-delay had a race where the timeout branch
        // fired late even after Loaded was set. The best server (mirror 0)
        // gets a generous window; fallbacks fail fast.
        val loadTimeout = if (mirrorIndex == 0) FIRST_LOAD_TIMEOUT_MS else NEXT_LOAD_TIMEOUT_MS
        val deadline = System.currentTimeMillis() + loadTimeout
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            if (loadState !is LoadState.Loading) return@LaunchedEffect
        }
        if (loadState is LoadState.Loading) {
            DebugLog.w(TAG, "timeout ${loadTimeout}ms mirror=$mirrorIndex url=${truncUrl(activeUrl)}")
            DeadHostRegistry.markIfHost(activeUrl)
            reportMirror(activeUrl, success = false)
            if (mirrorIndex + 1 < totalMirrors) {
                DebugLog.i(TAG, "advance ${mirrorIndex} -> ${mirrorIndex + 1} (timeout)")
                mirrorIndex += 1
            } else {
                loadState = LoadState.Failed(
                    "Stream didn't load — embed may be blocked or offline.",
                )
            }
        }
    }

    // Media-traffic gate: WebView fires "page finished" even when the
    // embed body is just a wrapper that never starts playing — common
    // when ISP/system adblock kills the player iframe (vidsrc ->
    // cloudnestra) or the embed is rate-limited. We poll Resource Timing
    // every 500ms; the embed has to actually fetch a media segment
    // (m3u8/ts/mp4/...) within MEDIA_CHECK_MS for us to consider it
    // alive. No segment? Walk to the next mirror.
    LaunchedEffect(activeUrl, attempt, loadState) {
        if (loadState !is LoadState.Loaded) return@LaunchedEffect
        // The hosted YouTube embed manages its own lifecycle (autoplay +
        // onError -> watch-page fallback). Don't subject it to the blank-walk
        // probe: the IFrame API can take several seconds to spin up on the box,
        // and walking it to the watch page mid-init is exactly the slow
        // "failed to fetch, then click Watch" detour the user hit.
        if (ytEmbedActive.value) return@LaunchedEffect
        // Best server (mirror 0) gets the full window to start; fallbacks are
        // failed fast so a dud is abandoned in a few seconds, not ~18.
        val mediaCheck = if (mirrorIndex == 0) FIRST_MEDIA_CHECK_MS else NEXT_MEDIA_CHECK_MS
        val blankCutoff = if (mirrorIndex == 0) FIRST_BLANK_CUTOFF_MS else NEXT_BLANK_CUTOFF_MS
        val start = System.currentTimeMillis()
        val deadline = start + mediaCheck
        var lastSignal = "none"
        var sawPlayer = false
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val wv = webViewRef ?: return@LaunchedEffect
            val signal = probeForPlayer(wv)
            lastSignal = signal
            if (signal == "media" || signal == "video" || signal == "iframe-video") {
                DebugLog.i(TAG, "play confirmed mirror=$mirrorIndex via $signal")
                reportMirror(activeUrl, success = true)
                return@LaunchedEffect
            }
            if (signal == "iframe-cross-origin") sawPlayer = true
            // Fast-fail a dead wrapper: if NO player element of any kind has
            // appeared within BLANK_CUTOFF_MS, this mirror is blank — don't
            // burn the full 18s before walking to the next one. (Players that
            // showed an iframe get the full window in case they just need a
            // moment / a tap.)
            if (!sawPlayer && System.currentTimeMillis() - start >= blankCutoff) break
        }
        // Timed out without confirmed media traffic. IMPORTANT: a lot of
        // modern players (vidlink, vidsrc.cc, cloudnestra) either (a) proxy
        // the stream through their own domain with no .m3u8/.ts extension so
        // Resource Timing can't see it, or (b) wait for a tap before they
        // fetch anything. In both cases a *player iframe is present* even
        // though we saw no media bytes. Auto-walking away from those (the old
        // behavior) is exactly why nothing played. So:
        //   - player iframe present (iframe-cross-origin) -> KEEP it; the user
        //     can press play. Don't walk, don't error.
        //   - genuinely blank (none) -> walk to the next mirror; only error
        //     once every mirror came back blank.
        if (lastSignal == "iframe-cross-origin") {
            DebugLog.i(TAG, "keeping mirror=$mirrorIndex — player present, no autostart traffic (press play)")
            return@LaunchedEffect
        }
        reportMirror(activeUrl, success = false)
        if (mirrorIndex + 1 < totalMirrors) {
            DebugLog.i(TAG, "blank after ${mediaCheck}ms (signal=$lastSignal) mirror=$mirrorIndex; advancing")
            mirrorIndex += 1
        } else {
            DebugLog.w(TAG, "exhausted ${totalMirrors} mirrors — last signal=$lastSignal")
            DeadHostRegistry.markIfHost(activeUrl)
            loadState = LoadState.Failed(
                "Couldn't play this title — none of the mirrors delivered video. " +
                    "On this box that's almost always the network's DNS blocking the " +
                    "streaming domains (mirrors fail to resolve, and the one that loads " +
                    "can't reach its player CDN). Fix on the box: Settings → Network → " +
                    "Private DNS → Off, or point DNS at an unfiltered resolver (1.1.1.1 / " +
                    "8.8.8.8). The app can't override system DNS, and in-app 'Block ads in " +
                    "player' is NOT the cause.",
                0,
            )
        }
    }

    // Inner BACK handler: while in HTML5 fullscreen, BACK exits fullscreen
    // first instead of bubbling up to the route-level handler. This shadows
    // DtApp's `BackHandler { route = Route.Tabs }` only while a custom view
    // is showing, so normal navigation is unaffected.
    BackHandler(enabled = chromeClientRef?.isInCustomView() == true) {
        DebugLog.i(TAG, "BACK while in custom view — exiting fullscreen")
        chromeClientRef?.forceExit()
    }

    // BACK while the "Up next" rail is open closes the rail and returns focus
    // to the video, instead of exiting the player.
    BackHandler(enabled = railVisible) {
        railVisible = false
        webViewRef?.requestFocus()
    }

    // BACK while the control bar is open closes it (doesn't exit the player).
    BackHandler(enabled = controlsVisible) {
        controlsVisible = false
        webViewRef?.requestFocus()
    }

    // Single load entry-point. A `ytembed://<id>` source is expanded into our
    // hosted IFrame-API player and loaded via loadDataWithBaseURL (so the YT
    // API gets a real origin); everything else is a plain headered loadUrl.
    fun loadInto(wv: WebView, url: String, hdrs: Map<String, String>) {
        if (url.startsWith(YT_EMBED_SCHEME)) {
            val vid = url.removePrefix(YT_EMBED_SCHEME)
            DebugLog.i(TAG, "load yt-embed vid=$vid")
            wv.loadDataWithBaseURL(YT_EMBED_BASE, buildYtEmbedHtml(vid), "text/html", "utf-8", YT_EMBED_BASE)
        } else {
            wv.loadUrl(url, hdrs)
        }
    }

    // YouTube autoplay: when a video ends the wrapper pings `ytnext://`; we
    // resolve a related video we haven't played yet and load it in-place, so
    // playback cycles forever until the user backs out. No fresh related ->
    // we simply stop (the end screen stays).
    fun cycleToNextYouTube() {
        val resolver = youtubeRelated ?: return
        val cur = ytCurrentId.value ?: return
        ytScope.launch {
            val related = runCatching { resolver(cur) }.getOrDefault(emptyList())
            val next = related.firstOrNull { it !in ytSeen }
            if (next != null) {
                ytSeen.add(next)
                ytCurrentId.value = next
                DebugLog.i(TAG, "yt autoplay $cur -> $next")
                webViewRef?.evaluateJavascript("ytPlay('$next');", null)
            } else {
                DebugLog.i(TAG, "yt autoplay: no fresh related for $cur — stopping")
            }
        }
    }

    // Play a related video the user picked from the "Up next" rail, in-place
    // in the same embed (no reload, keeps fullscreen). Closes the rail and
    // hands focus back to the player so OK resumes pausing the new video.
    fun playYouTubeInPlace(id: String) {
        ytSeen.add(id)
        ytCurrentId.value = id
        DebugLog.i(TAG, "yt rail pick -> $id")
        webViewRef?.evaluateJavascript("ytPlay('$id');", null)
        railVisible = false
        webViewRef?.requestFocus()
    }

    // D-pad seek policy (the user's "double-press to seek"): a SINGLE Left/
    // Right is swallowed so you don't seek by accident while moving toward the
    // gear/volume; a DOUBLE-tap within DPAD_DOUBLE_MS seeks ±10s. On our own
    // YouTube wrapper (same-origin) we drive the seek through the IFrame API;
    // on a cross-origin embed we can't reach its player, so the second tap is
    // passed through to let the embed perform its own seek.
    val dpadState = remember { DpadSeekState() }
    val dpadListener = remember {
        android.view.View.OnKeyListener { _, keyCode, event ->
            // While the error overlay is up, let EVERY key through to Compose so
            // its Retry/Back buttons are reachable. Otherwise Left/Right keep
            // seeking the dead embed and the overlay is unusable (you had to
            // spam BACK to escape).
            if (loadState is LoadState.Failed) return@OnKeyListener false
            val isCenter = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            val isPlayPause = keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == android.view.KeyEvent.KEYCODE_SPACE
            // D-pad UP on an episodic embed opens the native control bar
            // (Prev/Next episode + Back). Consume so it doesn't reach the embed.
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                showNextPrev && !controlsVisible && !railVisible
            ) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    controlsVisible = true
                }
                return@OnKeyListener true
            }
            // D-pad DOWN on our YouTube embed opens the native "Up next" rail
            // (if we have related videos). Consume so the keystroke doesn't
            // reach the embed's own end-screen. While the rail is up, focus
            // lives in Compose and this listener is dormant.
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                ytEmbedActive.value && !railVisible && relatedList.isNotEmpty()
            ) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    railVisible = true
                }
                return@OnKeyListener true
            }
            // OK / Play-Pause on OUR YouTube embed: toggle playback through the
            // IFrame API so the video can be paused with the remote's OK button
            // — no need to flip the box into mouse-cursor mode. Guarded by
            // !railVisible so that pressing OK on a related-video card plays
            // that video (handled in Compose) instead of toggling pause. On a
            // cross-origin embed (vidsrc etc.) we can't reach the player, so
            // let the key fall through to that embed's own on-screen controls.
            if ((isCenter || isPlayPause) && ytEmbedActive.value && !railVisible) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    webViewRef?.evaluateJavascript("ytTogglePlay();", null)
                }
                return@OnKeyListener true // consume down + up so it never reaches the player
            }
            val isLR = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            if (!isLR) return@OnKeyListener false // up/down pass through
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener true
            if (event.repeatCount > 0) return@OnKeyListener true // ignore key-repeat
            val dir = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            val now = event.eventTime
            val isDouble = keyCode == dpadState.lastKey && now - dpadState.lastTime <= DPAD_DOUBLE_MS
            dpadState.lastKey = keyCode
            dpadState.lastTime = now
            if (!isDouble) return@OnKeyListener true // single tap: swallow
            dpadState.lastKey = 0 // consume the pair so a 3rd tap starts fresh
            if (ytEmbedActive.value) {
                webViewRef?.evaluateJavascript("ytSeek(${dir * 10});", null)
                true
            } else {
                false // cross-origin: let this 2nd tap reach the embed
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val webView = WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configureForEmbedPlayback()
                    val cosmeticCss = runCatching {
                        ctx.assets.open("filters/cosmetic_rules.css")
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrNull() ?: ""
                    webViewClient = EmbedWebViewClient(
                        blocker = blocker,
                        cosmeticCss = cosmeticCss,
                        mainFrameHost = { mainFrameHost },
                        onMainFrameStarted = { url ->
                            hostOf(url)?.let { mainFrameHost = it }
                        },
                        onMainFrameFinished = { loadState = LoadState.Loaded },
                        onMainFrameError = { code, reason, failedUrl ->
                            val transport = code in TRANSPORT_ERROR_CODES
                            DebugLog.w(TAG, "main-frame err code=$code reason=$reason mirror=$mirrorIndex")
                            if (transport) {
                                // Mark the URL that ACTUALLY failed, not the
                                // factory-captured (stale) activeUrl — otherwise
                                // an error on mirror N poisons mirror 0's host.
                                DeadHostRegistry.markIfHost(failedUrl ?: activeUrl)
                                reportMirror(failedUrl ?: activeUrl, success = false)
                            }
                            if (transport && mirrorIndex + 1 < totalMirrors) {
                                DebugLog.i(TAG, "advance ${mirrorIndex} -> ${mirrorIndex + 1} (transport err $code)")
                                mirrorIndex += 1
                            } else {
                                loadState = LoadState.Failed(reason, code)
                            }
                        },
                        onResourceBlocked = { host ->
                            // Bounded — we only need a hint for the error overlay.
                            if (blockedHosts.size < 16 && host !in blockedHosts) {
                                blockedHosts.add(host)
                            }
                        },
                        onEmbedBlocked = {
                            // YT IFrame API reported the uploader disabled
                            // embedding (error 101/150). Walk to the next
                            // mirror — for YouTube that's the watch page,
                            // which plays embed-blocked videos.
                            if (mirrorIndex + 1 < totalMirrors) {
                                DebugLog.i(TAG, "yt embed blocked -> watch-page fallback")
                                mirrorIndex += 1
                            } else {
                                loadState = LoadState.Failed(
                                    "This video can't be embedded and the fallback is unavailable.",
                                )
                            }
                        },
                        onVideoEnded = { cycleToNextYouTube() },
                        onEmbedEnded = onEmbedEnded,
                        injectEndedHook = showNextPrev,
                    )
                    val chrome = FullscreenChromeClient(
                        getActivity = { ctx.findActivity() },
                        getWebView = { webViewRef },
                    )
                    chromeClientRef = chrome
                    webChromeClient = chrome
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    setOnKeyListener(dpadListener)
                    // Hide the OS mouse pointer over our own YouTube embed.
                    // The box's remote can flip into air-mouse mode, and the
                    // pointer it draws then lingers over the player even after
                    // switching back to D-pad — ugly, and now unnecessary since
                    // OK pauses directly. Only suppressed for the YT embed;
                    // cross-origin embeds keep a normal cursor in case the user
                    // needs it to click their player's controls.
                    pointerIcon = pointerIconFor(ctx, activeUrl.startsWith(YT_EMBED_SCHEME))
                    if (BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    loadInto(this, activeUrl, effectiveHeaders)
                }
                lastLoadedUrl = activeUrl
                webViewRef = webView
                webView
            },
            update = { wv ->
                // Re-load when the active mirror changes (auto-advance after a
                // transport error, or the YT embed falling back to the watch
                // page). Compare against lastLoadedUrl, not wv.url, so the
                // loadDataWithBaseURL'd YT embed doesn't look like a stale page
                // and reload on every recomposition.
                if (activeUrl != lastLoadedUrl) {
                    lastLoadedUrl = activeUrl
                    wv.stopLoading()
                    loadInto(wv, activeUrl, effectiveHeaders)
                }
            },
            onRelease = { wv ->
                // Force-exit any in-progress HTML5 fullscreen BEFORE we
                // tear down the WebView. Without this, an embed that
                // entered fullscreen leaves the activity with a detached
                // video surface, immersive system-UI flags still applied,
                // and the WebView holding focus — that's the freeze that
                // required a hard reboot. Hiding the custom view restores
                // system UI, returns focus to Compose, and lets BACK work.
                chromeClientRef?.forceExit()
                chromeClientRef = null
                webViewRef = null
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            },
        )

        // Mirror-walk indicator while auto-advancing — gives a brief
        // "Trying mirror N of M" so the user knows the screen isn't dead.
        if (mirrorIndex > 0 && loadState is LoadState.Loading) {
            MirrorWalkChip(
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                index = mirrorIndex + 1,
                total = totalMirrors,
            )
        }

        if (loadState is LoadState.Failed) {
            val failed = loadState as LoadState.Failed
            val baseReason = failed.reason
            val codeSuffix = if (failed.errorCode != 0) " (code ${failed.errorCode})" else ""
            val mirrorSuffix = if (totalMirrors > 1)
                "\n\nTried ${totalMirrors} mirror" +
                    (if (totalMirrors == 1) "" else "s") +
                    " — all unreachable."
            else ""
            val reason = baseReason + codeSuffix + mirrorSuffix +
                if (blockedHosts.isEmpty()) ""
                else "\n\nAdblock blocked ${blockedHosts.size} request" +
                    (if (blockedHosts.size == 1) "" else "s") +
                    " (e.g. ${blockedHosts.last()}). Try Settings → 'Block ads in player' → off."
            ErrorOverlay(
                message = reason,
                onPickServer = onPickServer,
                onRetry = {
                    // Restart the walk from the original embed.
                    webViewRef?.let { wv ->
                        wv.stopLoading()
                        blockedHosts.clear()
                        mirrorIndex = 0
                        loadState = LoadState.Loading
                        attempt += 1
                        lastLoadedUrl = embedUrl
                        loadInto(wv, embedUrl, headers + ("Referer" to defaultReferer(embedUrl)))
                    }
                },
                onBack = onExit,
            )
        }

        // Netflix-style control bar — opened with D-pad UP on episodic embeds.
        // Prev/Next episode + Back; native Compose, owns focus while shown.
        if (controlsVisible && showNextPrev) {
            PlayerControlBar(
                firstFocus = controlsFocus,
                onPrev = {
                    controlsVisible = false
                    onPrev()
                },
                onNext = {
                    controlsVisible = false
                    onNext()
                },
                onBack = onExit,
                onDismiss = {
                    controlsVisible = false
                    webViewRef?.requestFocus()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // In-player "Up next" rail — opened with D-pad DOWN on the YouTube
        // embed. Native Compose, so it owns focus while open (Left/Right move
        // between cards, OK plays the picked video in-place).
        if (railVisible && ytEmbedActive.value && relatedList.isNotEmpty()) {
            RelatedRail(
                videos = relatedList,
                firstFocus = railFocus,
                onPlay = { id -> playYouTubeInPlace(id) },
                onDismiss = {
                    railVisible = false
                    webViewRef?.requestFocus()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private data class ActiveSource(val url: String, val headers: Map<String, String>)

@Composable
private fun MirrorWalkChip(modifier: Modifier, index: Int, total: Int) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Trying mirror $index of $total…",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Bottom "Up next" rail of related YouTube videos, opened with D-pad DOWN.
 * Left/Right scrolls between cards; OK plays the picked video in-place; UP
 * (or BACK) closes it and returns to the video.
 */
@Composable
private fun RelatedRail(
    videos: List<com.dt.streamz.data.SearchResult>,
    firstFocus: FocusRequester,
    onPlay: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                    onDismiss(); true
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                RelatedCard(
                    result = v,
                    focusRequester = if (i == 0) firstFocus else null,
                    onClick = { onPlay(v.id) },
                )
            }
        }
    }
}

@Composable
private fun RelatedCard(
    result: com.dt.streamz.data.SearchResult,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(200.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            onClick = onClick,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF1A1A1A),
                focusedContainerColor = Color(0xFF1A1A1A),
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .border(
                        width = if (focused) 2.dp else 0.dp,
                        color = if (focused) Color.White else Color.Transparent,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ),
            ) {
                if (result.poster != null) {
                    coil3.compose.AsyncImage(
                        model = result.poster,
                        contentDescription = result.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = result.title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (focused) 1f else 0.8f),
            maxLines = 2,
        )
    }
}

/**
 * Top control bar (Prev episode / Back / Next episode), opened with D-pad UP
 * on episodic embeds. D-pad DOWN closes it and returns to the video.
 */
@Composable
private fun PlayerControlBar(
    firstFocus: FocusRequester,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.95f), Color.Transparent),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                    onDismiss(); true
                } else false
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton("⏮  Prev", onClick = onPrev, modifier = Modifier.focusRequester(firstFocus))
        ControlButton("⟵  Back", onClick = onBack)
        ControlButton("Next  ▶|", onClick = onNext)
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.55f),
            focusedContainerColor = Color.White.copy(alpha = 0.92f),
        ),
        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * "Is the embed actually playing?" check, two-stage:
 *
 *  1) DOM probe — `<video>` in the main doc or same-origin iframes.
 *     Cross-origin iframes (where most embeds live) are noted but NOT
 *     trusted on their own — too easy to be tricked by an empty wrapper.
 *  2) Resource Timing API probe — has the page actually fetched any
 *     media bytes (.m3u8 / .ts / .mp4 / .m4s / .key / .webm)? Resource
 *     Timing entries are visible across origins (they don't expose
 *     content, just URLs/timings), so this works through the
 *     vidsrc -> vsembed -> cloudnestra iframe chain.
 *
 * Returns one of: "media" (real playback confirmed by network traffic),
 * "video" / "iframe-video" (DOM-side player present, traffic might catch
 * up), "iframe-cross-origin" (just a wrapper, weakest signal), "none".
 *
 * Caller decides what to accept. Strong gate = require "media".
 */
private suspend fun probeForPlayer(webView: WebView): String {
    val js = """
        (function(){
          // Resource Timing tells us if any media segment has been fetched.
          // Cross-origin entries are visible by URL — perfect for the
          // vidsrc -> cloudnestra iframe chain where we can't poke into
          // contentDocument but CAN see the network it fired.
          try {
            var entries = (performance.getEntriesByType('resource') || []);
            for (var i = 0; i < entries.length; i++) {
              var name = entries[i].name || '';
              if (/\.(m3u8|ts|mp4|m4s|webm|aac|key)(\?|$)/i.test(name)) {
                return 'media';
              }
            }
          } catch (e) {}

          if (document.querySelector('video')) return 'video';
          var frames = document.querySelectorAll('iframe');
          var sawCrossOrigin = false;
          for (var i = 0; i < frames.length; i++) {
            var src = frames[i].getAttribute('src') || '';
            if (!src || src.indexOf('about:') === 0) continue;
            try {
              var doc = frames[i].contentDocument;
              if (doc && doc.querySelector('video')) return 'iframe-video';
            } catch (e) {
              sawCrossOrigin = true;
            }
          }
          if (sawCrossOrigin) return 'iframe-cross-origin';
          return 'none';
        })();
    """.trimIndent()
    val result = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
        webView.post {
            webView.evaluateJavascript(js) { value ->
                cont.resumeWith(Result.success(value ?: "\"none\""))
            }
        }
    }
    return result.removeSurrounding("\"")
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPickServer: (() -> Unit)? = null,
) {
    // The WebView under us holds focus while playing. When the overlay
    // shows up, the remote keeps sending events to the WebView and the
    // buttons feel unclickable. Request focus on the first action as soon
    // as the overlay enters composition so D-pad lands on it.
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { retryFocus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(40.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onPickServer != null) {
                    Button(
                        onClick = onPickServer,
                        modifier = Modifier.focusRequester(retryFocus),
                    ) {
                        Text("Choose server", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Button(onClick = onRetry) {
                        Text("Retry", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                } else {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(retryFocus),
                    ) {
                        Text("Retry", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onBack) {
                    Text("Back", modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForEmbedPlayback() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        userAgentString = DESKTOP_UA
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
    }
}

private class EmbedWebViewClient(
    private val blocker: HostBlocker?,
    private val cosmeticCss: String,
    private val mainFrameHost: () -> String?,
    private val onMainFrameStarted: (String) -> Unit,
    private val onMainFrameFinished: () -> Unit,
    private val onMainFrameError: (Int, String, String?) -> Unit,
    private val onResourceBlocked: (String) -> Unit,
    private val onEmbedBlocked: () -> Unit = {},
    private val onVideoEnded: () -> Unit = {},
    // Best-effort "video ended" from a non-YouTube embed (postMessage hook).
    private val onEmbedEnded: () -> Unit = {},
    // Inject the embed-ended postMessage listener after page load.
    private val injectEndedHook: Boolean = false,
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null && url != "about:blank") {
            DebugLog.d(TAG, "page-start ${truncUrl(url)}")
            onMainFrameStarted(url)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // Only the top document's onPageFinished fires with the WebView's own URL.
        if (url != null && url != "about:blank" && url == view.url) {
            DebugLog.i(TAG, "page-finish ${truncUrl(url)}")
            onMainFrameFinished()
        }
        // NOTE: we deliberately do NOT intercept D-pad keys anymore. The
        // embed players (vidnest / vidlink / YouTube) have their own on-screen
        // control bars — play/pause, a volume control, a settings gear — that
        // you reach by navigating with the remote. Hijacking arrows for
        // ±10s seek trapped focus and made the gear/volume unreachable, so
        // keys now pass straight through to the player's native controls.

        // Best-effort auto-play-next: many embed players postMessage a
        // player-event to the top window when the video ends. We can't read
        // the cross-origin <video>, but we CAN catch that message. Conservative
        // match (only clear "ended" signals) + a 60s arm window so it can't
        // fire on an early ad/buffer event and skip mid-episode. If the embed
        // doesn't emit anything, the manual Next button (DPAD UP) covers it.
        if (injectEndedHook) {
            view.evaluateJavascript(ENDED_HOOK_JS, null)
        }

        if (cosmeticCss.isBlank()) return
        val jsLiteral = JSONObject.quote(cosmeticCss)
        val script = """
            (function () {
              var s = document.createElement('style');
              s.setAttribute('data-dt-streamz','cosmetic');
              s.textContent = $jsLiteral;
              document.head.appendChild(s);
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        DebugLog.w(TAG, "main-frame err code=${error.errorCode} desc=${error.description} url=${truncUrl(request.url?.toString().orEmpty())}")
        onMainFrameError(error.errorCode, "Embed failed to load: ${error.description}", request.url?.toString())
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url ?: return false
        val scheme = url.scheme
        // Sentinel from our YouTube embed wrapper: the IFrame API reported the
        // video can't be embedded. Trigger the watch-page fallback and consume.
        if (scheme == YT_FALLBACK_SCHEME) {
            onEmbedBlocked()
            return true
        }
        // Our YouTube wrapper pinged us that the video ended — queue the next
        // related one (autoplay cycle). Consume; nothing actually navigates.
        if (scheme == YT_NEXT_SCHEME) {
            onVideoEnded()
            return true
        }
        // Best-effort "video ended" from a non-YouTube embed's postMessage hook.
        if (scheme == DT_NEXT_SCHEME) {
            DebugLog.i(TAG, "embed reported ended -> auto-next")
            onEmbedEnded()
            return true
        }
        if (scheme != "http" && scheme != "https") return true
        // Let WebView handle navigations natively. Intercepting and calling
        // view.loadUrl() hoists iframe targets into the main frame, which
        // breaks nested embeds like vidsrc.to -> vsembed.ru -> cloudnestra.
        return false
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!HostBlocker.isEnabled()) return null
        val url = request.url ?: return null
        val host = url.host?.lowercase() ?: return null
        // 1. Never block the main frame — that just bricks the screen.
        if (request.isForMainFrame) return null
        // 2. Never block 1st-party requests of whatever the embed loaded.
        val main = mainFrameHost()
        if (main != null && shareEffectiveDomain(main, host)) return null
        // 3. Never block media-y file types — even if the host is on the
        //    list, killing chunks/manifests/keys turns the embed into a
        //    blank page with no actual ad value gained.
        val path = url.path.orEmpty()
        if (MEDIA_PATH.containsMatchIn(path)) return null
        // 4. Allow well-known stream CDNs by suffix.
        if (CDN_ALLOWLIST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return null

        if (blocker?.isBlocked(host) == true) {
            DebugLog.d(TAG, "blocked $host path=$path main=$main")
            onResourceBlocked(host)
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        return super.shouldInterceptRequest(view, request)
    }
}

/**
 * HTML5 fullscreen handler. The previous implementation just stored the
 * custom view without attaching it, which left the embed's `<video>`
 * reparented to an orphaned surface — the player thought it was
 * fullscreen, the WebView kept focus, and the activity ended up with no
 * way to recover (BACK never reached Compose). The Snowfall freeze that
 * needed a hard reboot was almost certainly this path.
 *
 * Correct behaviour:
 *   onShow  -> hide the WebView, attach `view` to the activity's content
 *              frame, set immersive system-UI flags
 *   onHide  -> reverse all of that, restore system-UI flags, return focus
 *              to the WebView
 */
private class FullscreenChromeClient(
    private val getActivity: () -> Activity?,
    private val getWebView: () -> WebView?,
) : WebChromeClient() {
    private var customView: View? = null
    private var callback: CustomViewCallback? = null
    private var savedSystemUi: Int = 0

    override fun onShowCustomView(view: View, cb: CustomViewCallback) {
        if (customView != null) {
            // Player tried to enter fullscreen twice without hiding —
            // dismiss the new request to avoid leaking an orphan view.
            cb.onCustomViewHidden()
            return
        }
        val activity = getActivity() ?: run {
            DebugLog.w(TAG, "onShowCustomView: no activity — dismissing")
            cb.onCustomViewHidden()
            return
        }
        customView = view
        callback = cb
        getWebView()?.visibility = View.GONE
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        @Suppress("DEPRECATION")
        run {
            savedSystemUi = activity.window.decorView.systemUiVisibility
            activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        val activity = getActivity()
        (view.parent as? ViewGroup)?.removeView(view)
        getWebView()?.let {
            it.visibility = View.VISIBLE
            it.requestFocus()
        }
        if (activity != null) {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = savedSystemUi
        }
        callback?.onCustomViewHidden()
        customView = null
        callback = null
    }

    /** Idempotent escape hatch for AndroidView.onRelease + BACK. */
    fun forceExit() {
        if (customView != null) onHideCustomView()
    }

    fun isInCustomView(): Boolean = customView != null

    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        val tag = "WebPlayer/js"
        val body = "${msg.message()} @${msg.sourceId()?.substringAfterLast('/')}:${msg.lineNumber()}"
        when (msg.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.w(tag, body)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, body)
            else -> Log.i(tag, body)
        }
        return true
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Approximate eTLD+1 comparison without the full Public Suffix List.
 * Takes the last two labels — works for typical streaming hosts (.com,
 * .net, .to, .ru, .xyz). Misses .co.uk-style TLDs but those are rare in
 * this corner of the web; false-positive direction is "let it through",
 * which is the safer bias for a player.
 */
private fun shareEffectiveDomain(a: String, b: String): Boolean {
    val ad = a.split('.').takeLast(2).joinToString(".")
    val bd = b.split('.').takeLast(2).joinToString(".")
    return ad.isNotBlank() && ad == bd
}

private fun hostOf(url: String): String? = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()

/**
 * Pointer icon for the player WebView. [hidden] = our own YouTube embed,
 * where we suppress the OS mouse cursor entirely (TYPE_NULL); otherwise a
 * normal arrow so cross-origin embeds stay clickable.
 */
private fun pointerIconFor(ctx: Context, hidden: Boolean): android.view.PointerIcon =
    android.view.PointerIcon.getSystemIcon(
        ctx,
        if (hidden) android.view.PointerIcon.TYPE_NULL else android.view.PointerIcon.TYPE_ARROW,
    )

/** Shortens a URL for the in-app debug log so screenshots stay readable. */
private fun truncUrl(url: String, max: Int = 90): String =
    if (url.length <= max) url else url.take(max - 1) + "…"

private fun defaultReferer(embedUrl: String): String {
    val uri = runCatching { Uri.parse(embedUrl) }.getOrNull()
    val scheme = uri?.scheme ?: "https"
    val host = uri?.host ?: return "https://www.google.com/"
    return "$scheme://$host/"
}

private val MEDIA_PATH = Regex(
    """\.(m3u8|m3u|mpd|ts|mp4|m4s|m4a|aac|flac|webm|ogg|opus|vtt|srt|ass|key)(?:$|\?)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Streaming CDN suffixes seen in vidsrc/megacloud/2embed playback paths.
 * Matched by exact host or as a parent-domain suffix. Keep tight — the
 * adblock list is the long tail; this is just the must-allow shortlist.
 */
private val CDN_ALLOWLIST_SUFFIXES = setOf(
    "akamaized.net",
    "akamaihd.net",
    "cloudfront.net",
    "edgesuite.net",
    "fastly.net",
    "googlevideo.com",
    "ytimg.com",
    // YouTube embed player (ytembed:// wrapper) core resources — keep these
    // off the adblock chopping block or the IFrame player can't initialise.
    "youtube.com",
    "youtube-nocookie.com",
    "ggpht.com",
    "gstatic.com",
    "cloudnestra.com",
    "vsembed.ru",
    "tmstr.shop",
    "tmstr2.shop",
    "megacloud.tv",
    "megacloud.club",
    "megacloud.blog",
    "megacloud.store",
    "megaup.cc",
    "megaup.site",
    "rabbitstream.net",
    "streamtape.com",
    "streamtape.net",
    "doodstream.com",
    "dood.la",
    "filemoon.sx",
    "filemoon.to",
    "vidplay.online",
    "vidplay.site",
    "mycloud.to",
    "mixdrop.co",
    "upcloud.to",
)

private const val TAG = "WebPlayer"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// --- YouTube embed player ---------------------------------------------------

/** Source-URL marker for a YouTube video to play via our hosted embed. */
private const val YT_EMBED_SCHEME = "ytembed://"
/** Base origin we host the embed under so the IFrame API has a real origin. */
private const val YT_EMBED_BASE = "https://www.youtube-nocookie.com"
/** Navigation sentinel the wrapper hits when the video blocks embedding. */
private const val YT_FALLBACK_SCHEME = "ytfallback"
/** Navigation sentinel the wrapper hits when the current video ends. */
private const val YT_NEXT_SCHEME = "ytnext"
/** Sentinel a non-YouTube embed's postMessage hook hits when the video ends. */
private const val DT_NEXT_SCHEME = "dtnext"

/**
 * Best-effort "video ended" listener injected into non-YouTube embeds. Many
 * players (vidsrc/vidlink/vidnest families) postMessage a player-event to the
 * top window; we can't read the cross-origin <video>, but we can catch that.
 * Conservative: only fires on a clear "ended" signal, only after a 60s arm
 * window (so an early ad/buffer event can't skip mid-episode), and only once.
 * If the embed emits nothing, the manual Next button (D-pad UP) covers it.
 */
private val ENDED_HOOK_JS = """
    (function(){
      if (window.__dtEndedHook) return; window.__dtEndedHook = 1;
      var armed = false, fired = false;
      setTimeout(function(){ armed = true; }, 60000);
      window.addEventListener('message', function(e){
        try {
          var d = e.data;
          var s = (typeof d === 'string') ? d : JSON.stringify(d || '');
          if (!s) return;
          // Clear end-of-video signals only (NOT generic "complete").
          if (/(^|[^a-z])ended([^a-z]|$)|videoended|playbackended|video[_-]?end\b/i.test(s)) {
            if (armed && !fired) { fired = true; window.location.href = 'dtnext://end'; }
          }
        } catch (x) {}
      }, false);
    })();
""".trimIndent()
/** Max gap between two Left/Right taps to count as a double (seek). */
private const val DPAD_DOUBLE_MS = 400L

/** Mutable holder for the double-tap detector (kept in remember{}). */
private class DpadSeekState {
    var lastKey: Int = 0
    var lastTime: Long = 0
}

/**
 * Full-bleed YouTube player page built around the IFrame Player API. It's our
 * own document (same-origin under [YT_EMBED_BASE]), so:
 *   - it autoplays the video and fills the screen (the clean, app-like player
 *     instead of the desktop watch page), and
 *   - native can drive it through `ytSeek()` (the double-press D-pad handler).
 * If the uploader disabled embedding, onError 101/150 navigates to the
 * [YT_FALLBACK_SCHEME] sentinel, which native turns into a watch-page fallback.
 */
private fun buildYtEmbedHtml(videoId: String): String {
    val safe = videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    return YT_EMBED_TEMPLATE.replace("__VID__", safe)
}

private val YT_EMBED_TEMPLATE = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
  html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
  #player,iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}
</style>
</head>
<body>
<div id="player"></div>
<script>
  var player = null;
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('player', {
      width: '100%', height: '100%',
      videoId: '__VID__',
      host: 'https://www.youtube-nocookie.com',
      playerVars: {
        autoplay: 1, controls: 1, rel: 0, fs: 1, modestbranding: 1,
        playsinline: 1, iv_load_policy: 3, origin: 'https://www.youtube-nocookie.com'
      },
      events: {
        onReady: function (e) { try { e.target.playVideo(); } catch (x) {} },
        onStateChange: function (e) {
          // ENDED (0): tell native to queue a related video so playback
          // cycles YouTube-style instead of stopping on the end screen.
          if (e.data === 0) { window.location.href = 'ytnext://end'; }
        },
        onError: function (e) {
          if (e.data == 101 || e.data == 150) {
            window.location.href = 'ytfallback://blocked';
          }
        }
      }
    });
  }
  // Native D-pad bridge (called via evaluateJavascript).
  function ytSeek(d) {
    try {
      if (player && player.getCurrentTime) {
        var t = player.getCurrentTime() || 0;
        player.seekTo(Math.max(0, t + d), true);
      }
    } catch (x) {}
  }
  // Native OK / Play-Pause bridge: toggle playback. State 1 = playing,
  // 3 = buffering -> pause; anything else (paused/ended/cued) -> play.
  // Pausing surfaces YouTube's own control bar, so the pause state is visible.
  function ytTogglePlay() {
    try {
      if (!player || !player.getPlayerState) return;
      var s = player.getPlayerState();
      if (s === 1 || s === 3) { player.pauseVideo(); } else { player.playVideo(); }
    } catch (x) {}
  }
  // Native autoplay bridge: load + play the next related video in-place
  // (no page reload, so the player keeps its fullscreen/controls state).
  function ytPlay(id) {
    try { if (player && player.loadVideoById) { player.loadVideoById(id); } } catch (x) {}
  }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body>
</html>
""".trimIndent()
