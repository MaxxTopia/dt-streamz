package com.dt.streamz.scraper.youtube

import com.dt.streamz.scraper.Http
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * Bridges NewPipeExtractor's [Downloader] interface to the app's shared
 * OkHttp client, so YouTube scraping reuses the same connection pool +
 * timeouts as every other provider.
 */
internal class NewPipeOkHttpDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        for ((name, values) in request.headers()) {
            for (v in values) builder.addHeader(name, v)
        }
        // YouTube enforces user-agent sniffing on parts of the watch page;
        // borrow our desktop UA so the rendered HTML matches the
        // signature-cipher path NewPipeExtractor's parser expects.
        if (request.headers()["User-Agent"].isNullOrEmpty()) {
            builder.header("User-Agent", Http.DESKTOP_UA)
        }
        when (val method = request.httpMethod() ?: "GET") {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> {
                val data = request.dataToSend() ?: ByteArray(0)
                val mt = request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
                    ?: "application/octet-stream".toMediaTypeOrNull()
                builder.post(data.toRequestBody(mt))
            }
            else -> error("unsupported HTTP method $method")
        }
        val resp = Http.client.newCall(builder.build()).execute()
        val body = resp.body?.string() ?: ""
        val multimap: Map<String, List<String>> = resp.headers.toMultimap()
        return Response(
            resp.code,
            resp.message,
            multimap,
            body,
            resp.request.url.toString(),
        )
    }
}
