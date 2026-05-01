package com.dt.streamz.ui.webplayer

import android.annotation.SuppressLint
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
 * After page-finished, how long to wait for a <video> element or playable
 * iframe to appear in the DOM before declaring the embed dead and walking
 * to the next mirror. vidsrc/2embed normally inject the player within
 * 1-3 seconds; 5s gives them headroom on a slow box.
 */
private const val CONTENT_CHECK_MS = 5_000L

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

    var loadState by remember(activeUrl) { mutableStateOf<LoadState>(LoadState.Loading) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var attempt by remember(activeUrl) { mutableStateOf(0) }
    val blockedHosts = remember(activeUrl) { mutableStateListOf<String>() }
    var mainFrameHost by remember(activeUrl) { mutableStateOf(hostOf(activeUrl)) }

    DisposableEffect(activeUrl) {
        monitor?.setActiveHost(activeUrl)
        onDispose { monitor?.setActiveHost(null) }
    }

    LaunchedEffect(activeUrl, attempt) {
        if (loadState !is LoadState.Loading) loadState = LoadState.Loading
        delay(LOAD_TIMEOUT_MS)
        if (loadState is LoadState.Loading) {
            Log.w(TAG, "embed load exceeded ${LOAD_TIMEOUT_MS}ms: $activeUrl")
            // Treat a slow mirror like a dead one — try the next.
            if (mirrorIndex + 1 < totalMirrors) {
                Log.i(TAG, "timeout on mirror $mirrorIndex; advancing to ${mirrorIndex + 1}")
                mirrorIndex += 1
            } else {
                loadState = LoadState.Failed(
                    "Stream didn't load — embed may be blocked or offline.",
                )
            }
        }
    }

    // Content-gate: WebView reports "loaded" for HTTP 200 even when the
    // embed body is an HTML error page (no <video>, no iframe). Probe the
    // DOM for a real player element after page-finish; if nothing playable
    // shows up within CONTENT_CHECK_MS, advance to the next mirror.
    LaunchedEffect(activeUrl, attempt, loadState) {
        if (loadState !is LoadState.Loaded) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + CONTENT_CHECK_MS
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val wv = webViewRef ?: return@LaunchedEffect
            val found = probeForPlayer(wv)
            if (found) return@LaunchedEffect
        }
        // No <video> or playable iframe surfaced. Walk to next mirror,
        // or surface a friendly error if we've exhausted them.
        if (mirrorIndex + 1 < totalMirrors) {
            Log.i(
                TAG,
                "no playable element on $activeUrl after ${CONTENT_CHECK_MS}ms; advancing",
            )
            mirrorIndex += 1
        } else {
            loadState = LoadState.Failed(
                "All mirrors loaded but none surfaced a video — the title may be unavailable.",
                0,
            )
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
                        onMainFrameError = { code, reason ->
                            val transport = code in TRANSPORT_ERROR_CODES
                            if (transport && mirrorIndex + 1 < totalMirrors) {
                                Log.i(
                                    TAG,
                                    "transport error $code on mirror $mirrorIndex ($activeUrl); advancing",
                                )
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
                    webChromeClient = FullscreenChromeClient()
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
 * Looks for a `<video>` element in the page or any of its same-origin
 * iframes. Cross-origin iframes are treated as "playable" because we
 * can't introspect them (security model) — those are the typical cases
 * where vidsrc embeds itself inside a sub-iframe like cloudnestra.
 *
 * Returns true if anything resembling a player is present.
 */
private suspend fun probeForPlayer(webView: WebView): Boolean {
    val js = """
        (function(){
          if (document.querySelector('video')) return 'video';
          var frames = document.querySelectorAll('iframe');
          for (var i = 0; i < frames.length; i++) {
            var src = frames[i].getAttribute('src') || '';
            if (!src) continue;
            // Same-origin iframes can be introspected.
            try {
              var doc = frames[i].contentDocument;
              if (doc && doc.querySelector('video')) return 'iframe-video';
            } catch (e) {
              // Cross-origin — assume the iframe is the player.
              if (src.indexOf('about:') !== 0) return 'iframe-cross-origin';
            }
          }
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
    val unwrapped = result.removeSurrounding("\"")
    return unwrapped != "none"
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
                Button(onClick = onRetry) {
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
            onMainFrameStarted(url)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // Only the top document's onPageFinished fires with the WebView's own URL.
        if (url != null && url != "about:blank" && url == view.url) {
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
        Log.w(TAG, "main-frame error ${error.errorCode}: ${error.description}")
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
            Log.d(TAG, "blocked $host (path=$path) — main=$main")
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

private class FullscreenChromeClient : WebChromeClient() {
    private var customView: View? = null
    private var callback: CustomViewCallback? = null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        customView?.let { previous ->
            (previous.parent as? ViewGroup)?.removeView(previous)
        }
        customView = view
        this.callback = callback
    }

    override fun onHideCustomView() {
        customView = null
        callback?.onCustomViewHidden()
        callback = null
    }

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
