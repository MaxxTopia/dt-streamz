package com.dt.streamz.ui.webplayer

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dt.streamz.DtApplication
import com.dt.streamz.adblock.HostBlocker
import java.io.ByteArrayInputStream

/**
 * Renders an embed (iframe-style) URL inside a WebView so episodes from
 * providers that only surface an opaque embed link (gogoanime.by ->
 * megavid.buzz, any future DirectEmbed source) can actually play in the
 * app without a per-host Kotlin extractor.
 *
 * The site's own player JS handles ad-blocking is *not* wired yet —
 * Phase 5 proper adds AdblockAndroid's shouldInterceptRequest filter.
 * Until then expect pre-roll ads from the embed host.
 */
@Composable
fun WebPlayerScreen(embedUrl: String, onExit: () -> Unit = {}) {
    val ctx = LocalContext.current
    val blocker = (ctx.applicationContext as? DtApplication)?.hostBlocker

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
                    webViewClient = EmbedWebViewClient(blocker)
                    webChromeClient = FullscreenChromeClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    loadUrl(embedUrl)
                }
                webView
            },
            onRelease = { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            },
        )
    }

    DisposableEffect(Unit) { onDispose {} }
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

private class EmbedWebViewClient(private val blocker: HostBlocker?) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        // Keep navigation inside this WebView — cancel any new-tab pops that
        // embed hosts often trigger on first interaction (ad redirects).
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
            // Return a 403-style empty response so the fetch fails cleanly
            // without tying up a socket or invoking the ad host at all.
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

    companion object {
        private const val TAG = "WebPlayer"
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
        // The default behavior (show the custom video view) is sufficient
        // because our host Box already fills the screen — the WebView and
        // its custom view render full-bleed. If a host needs to project
        // the view into a separate overlay, add that here.
    }

    override fun onHideCustomView() {
        customView = null
        callback?.onCustomViewHidden()
        callback = null
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
