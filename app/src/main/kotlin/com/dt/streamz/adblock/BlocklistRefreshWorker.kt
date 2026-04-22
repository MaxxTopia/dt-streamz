package com.dt.streamz.adblock

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dt.streamz.DtApplication
import java.util.concurrent.TimeUnit

/**
 * Refreshes the ad-host blocklist from the upstream URL on a weekly cadence
 * while on unmetered wifi. Replaces the per-launch refresh — that was fine
 * when we were kicking the tires but hammered GitHub on every cold start.
 */
class BlocklistRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? DtApplication
            ?: return Result.failure()
        val ok = app.hostBlocker.refreshFromUrl(HostBlocker.UPSTREAM_HOSTS_URL)
        return if (ok) {
            Log.i(TAG, "weekly blocklist refresh OK (total hosts now ${app.hostBlocker.size()})")
            Result.success()
        } else {
            Log.w(TAG, "weekly blocklist refresh failed; will retry on next window")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BlocklistWorker"
        private const val UNIQUE_NAME = "adblock-hosts-refresh"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val req = PeriodicWorkRequestBuilder<BlocklistRefreshWorker>(
                7, TimeUnit.DAYS,
                1, TimeUnit.DAYS,  // flex: run any time in the last day of each 7-day window
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req,
                )
        }
    }
}
