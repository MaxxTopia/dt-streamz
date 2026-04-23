package com.dt.streamz.ui.webplayer

import android.annotation.SuppressLint
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dt.streamz.DtApplication
import com.dt.streamz.adblock.HostBlocker
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import org.json.JSONObject

private sealed interface LoadState {
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Failed(val reason: String) : LoadState
}

private const val LOAD_TIMEOUT_MS = 20_000L

/**
 * Renders an embed (iframe-style) URL inside a WebView so episodes from
 * providers that only surface an opaque embed link (gogoanime.by ->
 * 9animetv.be/histream player, any future DirectEmbed source) can play in
 * the app without a per-host Kotlin extractor.
 *
 * Shows a retry/back overlay if the page doesn't finish loading in
 * [LOAD_TIMEOUT_MS] or if the main frame reports a network error — WebView
 * otherwise just sits blank which looks indistinguishable from buffering.
 */
@Composable
fun WebPlayerScreen(embedUrl: String, onExit: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? DtApplication
    val blocker = app?.hostBlocker
    val monitor = app?.networkMonitor

    var loadState by remember(embedUrl) { mutableStateOf<LoadState>(LoadState.Loading) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var attempt by remember(embedUrl) { mutableStateOf(0) }

    DisposableEffect(embedUrl) {
        monitor?.setActiveHost(embedUrl)
        onDispose { monitor?.setActiveHost(null) }
    }

    LaunchedEffect(embedUrl, attempt) {
        if (loadState !is LoadState.Loading) loadState = LoadState.Loading
        delay(LOAD_TIMEOUT_MS)
        if (loadState is LoadState.Loading) {
            Log.w(TAG, "embed load exceeded ${LOAD_TIMEOUT_MS}ms: $embedUrl")
            loadState = LoadState.Failed("Stream didn't load — embed may be blocked or offline.")
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
                        onMainFrameFinished = { loadState = LoadState.Loaded },
                        onMainFrameError = { reason ->
                            loadState = LoadState.Failed(reason)
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
                    // Many embed hosts refuse to render without a referer
                    // pointing at the parent gogoanime.by site — mimic the
                    // natural click-through.
                    loadUrl(embedUrl, mapOf("Referer" to "https://gogoanime.by/"))
                }
                webViewRef = webView
                webView
            },
            onRelease = { wv ->
                webViewRef = null
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            },
        )

        if (loadState is LoadState.Failed) {
            ErrorOverlay(
                message = (loadState as LoadState.Failed).reason,
                onRetry = {
                    webViewRef?.let { wv ->
                        wv.stopLoading()
                        wv.loadUrl(embedUrl, mapOf("Referer" to "https://gogoanime.by/"))
                        loadState = LoadState.Loading
                        attempt += 1
                    }
                },
                onBack = onExit,
            )
        } else if (loadState is LoadState.Loaded) {
            SkipIntroButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                onClick = { webViewRef?.let(::fireSkip90s) },
            )
        }
    }
}

private fun fireSkip90s(webView: WebView) {
    val js = """
        (function(){
          var v = document.querySelector('video');
          if (v) { try { v.currentTime = Math.min((v.duration || 1e9), (v.currentTime || 0) + 90); return 'ok'; } catch(e) { return 'err:'+e.message; } }
          var frames = document.querySelectorAll('iframe');
          for (var i = 0; i < frames.length; i++) {
            try {
              var doc = frames[i].contentDocument;
              var vv = doc && doc.querySelector('video');
              if (vv) { vv.currentTime = (vv.currentTime || 0) + 90; return 'ok-iframe'; }
            } catch (e) {}
          }
          return 'no-video';
        })();
    """.trimIndent()
    webView.evaluateJavascript(js) { result ->
        Log.i(TAG, "skip-intro result: $result")
    }
}

@Composable
private fun SkipIntroButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.65f)),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                text = "▶▶ +90s",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
    private val onMainFrameFinished: () -> Unit,
    private val onMainFrameError: (String) -> Unit,
) : WebViewClient() {

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
        onMainFrameError("Embed failed to load: ${error.description}")
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url ?: return false
        val scheme = url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return true
        if (blocker?.isBlocked(url.host) == true) {
            Log.d(TAG, "blocked navigation to ${url.host}")
            return true
        }
        view.loadUrl(url.toString())
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val host = request.url?.host
        if (blocker?.isBlocked(host) == true) {
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

private const val TAG = "WebPlayer"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
