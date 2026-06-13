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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dt.streamz.DtApplication
import com.dt.streamz.adblock.HostBlocker
import com.dt.streamz.data.StreamSource
import com.dt.streamz.diag.DebugLog
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import org.json.JSONObject

private sealed interface LoadState {
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Failed(val reason: String, val errorCode: Int = 0) : LoadState
}

private const val LOAD_TIMEOUT_MS = 20_000L

/**
 * After page-finished, how long to wait for actual media traffic
 * (.m3u8 / .ts / .mp4 / etc.) before deciding the embed is a dead
 * wrapper. 12s lets a slow box fetch the player JS, decode the
 * obfuscated URL, and start the first segment request. If we never
 * see media traffic in that window, the embed is confirmed dead and
 * we walk to the next mirror — no more 20s sit-and-wait for a
 * cross-origin iframe wrapper that's actually empty inside.
 */
private const val MEDIA_CHECK_MS = 18_000L

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
 * Shows a retry/back overlay if the page doesn't finish loading in
 * [LOAD_TIMEOUT_MS] or if the main frame reports a network error — WebView
 * otherwise just sits blank, indistinguishable from buffering.
 */
@Composable
fun WebPlayerScreen(
    embedUrl: String,
    headers: Map<String, String> = emptyMap(),
    fallbacks: List<StreamSource> = emptyList(),
    onExit: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? DtApplication
    val blocker = app?.hostBlocker
    val monitor = app?.networkMonitor

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
    }

    // Auto-report a fully-failed playback (all mirrors exhausted) so dead
    // embeds surface without the user screenshotting the debug log.
    LaunchedEffect(loadState) {
        val f = loadState
        if (f is LoadState.Failed) {
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
        // fired late even after Loaded was set.
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            if (loadState !is LoadState.Loading) return@LaunchedEffect
        }
        if (loadState is LoadState.Loading) {
            DebugLog.w(TAG, "timeout ${LOAD_TIMEOUT_MS}ms mirror=$mirrorIndex url=${truncUrl(activeUrl)}")
            DeadHostRegistry.markIfHost(activeUrl)
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
        val deadline = System.currentTimeMillis() + MEDIA_CHECK_MS
        var lastSignal = "none"
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val wv = webViewRef ?: return@LaunchedEffect
            val signal = probeForPlayer(wv)
            lastSignal = signal
            if (signal == "media" || signal == "video" || signal == "iframe-video") {
                DebugLog.i(TAG, "play confirmed mirror=$mirrorIndex via $signal")
                return@LaunchedEffect
            }
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
        if (mirrorIndex + 1 < totalMirrors) {
            DebugLog.i(TAG, "blank after ${MEDIA_CHECK_MS}ms (signal=$lastSignal) mirror=$mirrorIndex; advancing")
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
                        onMainFrameError = { code, reason ->
                            val transport = code in TRANSPORT_ERROR_CODES
                            DebugLog.w(TAG, "main-frame err code=$code reason=$reason mirror=$mirrorIndex")
                            if (transport) {
                                DeadHostRegistry.markIfHost(activeUrl)
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
                    if (BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    loadUrl(activeUrl, effectiveHeaders)
                }
                webViewRef = webView
                webView
            },
            update = { wv ->
                // Re-load when the active mirror changes (auto-advance after
                // transport error). Without this, AndroidView keeps the old
                // page since factory only runs once.
                if (wv.url != activeUrl && wv.url != "about:blank") {
                    wv.stopLoading()
                    wv.loadUrl(activeUrl, effectiveHeaders)
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
                onRetry = {
                    // Restart the walk from the original embed.
                    webViewRef?.let { wv ->
                        wv.stopLoading()
                        blockedHosts.clear()
                        mirrorIndex = 0
                        loadState = LoadState.Loading
                        attempt += 1
                        wv.loadUrl(embedUrl, headers + ("Referer" to defaultReferer(embedUrl)))
                    }
                },
                onBack = onExit,
            )
        } else if (loadState is LoadState.Loaded) {
            SkipForwardChip(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                onClick = { webViewRef?.let(::fireSkip10s) },
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

private fun fireSkip10s(webView: WebView) {
    val js = """
        (function(){
          var v = document.querySelector('video');
          if (v) { try { v.currentTime = Math.min((v.duration || 1e9), (v.currentTime || 0) + 10); return 'ok'; } catch(e) { return 'err:'+e.message; } }
          var frames = document.querySelectorAll('iframe');
          for (var i = 0; i < frames.length; i++) {
            try {
              var doc = frames[i].contentDocument;
              var vv = doc && doc.querySelector('video');
              if (vv) { vv.currentTime = (vv.currentTime || 0) + 10; return 'ok-iframe'; }
            } catch (e) {}
          }
          return 'no-video';
        })();
    """.trimIndent()
    webView.evaluateJavascript(js) { result ->
        Log.i(TAG, "skip +10s result: $result")
    }
}

@Composable
private fun SkipForwardChip(modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
    ) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused },
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Black.copy(alpha = 0.55f),
            ),
        ) {
            Text(
                text = "+10s",
                color = if (focused) Color.White else Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    // The WebView under us holds focus while playing. When the overlay
    // shows up, the remote keeps sending events to the WebView and the
    // Retry / Back buttons feel unclickable. Request focus on Retry as
    // soon as the overlay enters composition so D-pad lands on it.
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
                Button(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocus),
                ) {
                    Text("Retry", modifier = Modifier.padding(horizontal = 8.dp))
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
    private val onMainFrameError: (Int, String) -> Unit,
    private val onResourceBlocked: (String) -> Unit,
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
        onMainFrameError(error.errorCode, "Embed failed to load: ${error.description}")
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url ?: return false
        val scheme = url.scheme
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
