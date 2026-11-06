package com.villamediana.surco.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.villamediana.surco.data.Settings
import java.util.concurrent.TimeUnit

/**
 * One unique work ("surco-downloads") runs [DownloadWorker]. It needs a connection — an unmetered
 * one when "only on Wi-Fi" is on — and is retried with a linear backoff when the network drops.
 */
internal class WorkScheduler(
    private val context: Context,
    private val settings: suspend () -> Settings,
) : DownloadScheduler {
    override suspend fun schedule(policy: SchedulePolicy) {
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WorkManager is not initialized; downloads stay queued", e)
            return
        }
        val wifiOnly = settings().wifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_NAME)
            .build()
        val existing = when (policy) {
            SchedulePolicy.REPLACE -> ExistingWorkPolicy.REPLACE
            SchedulePolicy.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        workManager.enqueueUniqueWork(WORK_NAME, existing, request)
    }

    companion object {
        const val WORK_NAME = "surco-downloads"
        private const val BACKOFF_SECONDS = 15L
        private const val TAG = "SurcoDownload"
    }
}
