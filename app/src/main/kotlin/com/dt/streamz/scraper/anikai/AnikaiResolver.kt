package com.dt.streamz.scraper.anikai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hidden-WebView harvester for anikai.to. The site's episode list and
 * stream URLs are rendered via heavily-obfuscated client JS that static
 * scraping can't reach. Instead we load the page inside an off-screen
 * WebView, let the site's own JS do its thing, and collect:
 *
 *   1. The final rendered HTML (for the episode list) via
 *      `document.documentElement.outerHTML`, ~3s after onPageFinished
 *      so XHRs have time to settle.
 *   2. Any network request URL matching a stream pattern (`.m3u8`,
 *      `megacloud/megaup embed-*`) via shouldInterceptRequest.
 *
 * All WebView operations are serialized with a mutex and run on the
 * main thread; the public API is `suspend` and safe to call from IO.
 *
 * This is deliberately a best-effort scraper — anikai changes its JS
 * periodically and this will break. Both resolve methods return null
 * on failure so the provider returns empty details/streams (current
 * graceful behavior) rather than throwing.
 */
class AnikaiResolver(context: Context) {

    private val appCtx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    /**
     * Loads [watchUrl], waits for the JS to settle, returns the rendered
     * HTML. Null on timeout or error.
     */
    suspend fun renderHtml(watchUrl: String, settleMs: Long = 3_000L): String? =
        mutex.withLock {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                captureHtml(watchUrl, settleMs)
            }
        }

    /**
     * Loads [watchUrl] and captures the first network request URL
     * matching [patterns] (e.g. a master m3u8). Null on timeout.
     */
    suspend fun captureStreamUrl(
        watchUrl: String,
        patterns: List<Regex> = DEFAULT_STREAM_PATTERNS,
    ): String? =
        mutex.withLock {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                captureRequest(watchUrl, patterns)
            }
        }

    // --- internals below; everything runs on Main ---

    private suspend fun captureHtml(url: String, settleMs: Long): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = freshWebView()
                var done = false
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loaded: String?) {
                        main.postDelayed({
                            if (done) return@postDelayed
                            view.evaluateJavascript(
                                "(function(){return document.documentElement.outerHTML;})();",
                            ) { result ->
                                if (done) return@evaluateJavascript
                                done = true
                                val html = unquoteJsString(result)
                                destroyAsync(view)
                                if (cont.isActive) cont.resume(html)
                            }
                        }, settleMs)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (!done && request?.isForMainFrame == true) {
                            done = true
                            destroyAsync(view)
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    if (!done) {
                        done = true
                        destroyAsync(webView)
                    }
                }
                webView.loadUrl(url)
            }
        }

    private suspend fun captureRequest(url: String, patterns: List<Regex>): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = freshWebView()
                var done = false
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url?.toString() ?: return null
                        if (done) return null
                        if (patterns.any { it.containsMatchIn(url) }) {
                            done = true
                            destroyAsync(view)
                            if (cont.isActive) cont.resume(url)
                        }
                        return null
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (!done && request?.isForMainFrame == true) {
                            done = true
                            destroyAsync(view)
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    if (!done) {
                        done = true
                        destroyAsync(webView)
                    }
                }
                webView.loadUrl(url)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun freshWebView(): WebView {
        val wv = WebView(appCtx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = Http.DESKTOP_UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        return wv
    }

    private fun destroyAsync(view: WebView) {
        main.post {
            runCatching {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.removeAllViews()
                view.destroy()
            }.onFailure { Log.w(TAG, "webview destroy failed", it) }
        }
    }

    private fun unquoteJsString(jsResult: String?): String? {
        if (jsResult == null || jsResult == "null") return null
        // evaluateJavascript returns a JSON-encoded value; when the JS
        // returned a string, the result is the string *including surrounding
        // double-quotes* and backslash-escaped internals.
        return runCatching {
            org.json.JSONArray("[${jsResult}]").getString(0)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AnikaiResolver"
        private const val TOTAL_TIMEOUT_MS = 15_000L

        val DEFAULT_STREAM_PATTERNS = listOf(
            Regex("""\.m3u8""", RegexOption.IGNORE_CASE),
            Regex("""megacloud\.\w+/embed-""", RegexOption.IGNORE_CASE),
            Regex("""megaup\.\w+/embed-""", RegexOption.IGNORE_CASE),
        )
    }
}
