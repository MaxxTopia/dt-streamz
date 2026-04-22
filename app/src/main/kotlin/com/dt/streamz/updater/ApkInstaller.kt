package com.dt.streamz.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Downloads an APK from a signed URL to the app's cache directory and
 * launches the system install prompt via ACTION_INSTALL_PACKAGE. The
 * user grants the one-time REQUEST_INSTALL_PACKAGES permission from
 * system Settings on Android 8+.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    suspend fun downloadAndInstall(context: Context, apkUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(cacheDir, "dt-streamz-update.apk")

            val ok = runCatching {
                val req = Request.Builder().url(apkUrl).build()
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "download failed: HTTP ${resp.code}")
                        return@use false
                    }
                    resp.body?.byteStream()?.use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                }
            }.onFailure { Log.w(TAG, "download error", it) }.getOrDefault(false)
            if (!ok) return@withContext false

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "startActivity(install) failed", it) }
                .isSuccess
        }
}
