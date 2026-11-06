package com.villamediana.sound.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.villamediana.sound.SoundApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Thin WorkManager shell around [DownloadEngine.runQueue]: runs as a dataSync foreground service
 * when the system allows it (keeps going as a plain job when it does not), keeps the progress
 * notification fresh, posts the summary at the end, and asks to be retried when the network dropped.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val controller = SoundApplication.graph.downloads as? WorkDownloadController ?: return Result.failure()
        val engine = controller.engine
        val notifier = DownloadNotifier(applicationContext)
        val receiver = notifier.registerCancelReceiver { controller.cancelAllInBackground() }
        try {
            val report = coroutineScope {
                val ticker = launch { keepNotificationFresh(engine, notifier) }
                try {
                    engine.runQueue()
                } finally {
                    ticker.cancel()
                }
            }
            notifier.clearProgress()
            if (report.networkLost) return Result.retry()
            notifier.showSummary(report)
            return Result.success()
        } finally {
            notifier.unregister(receiver)
            notifier.clearProgress()
            engine.idle()
        }
    }

    private suspend fun keepNotificationFresh(engine: DownloadEngine, notifier: DownloadNotifier) {
        var foreground: Boolean? = null // null = not tried yet
        while (true) {
            val snapshot = engine.snapshot()
            if (snapshot.hasWork) {
                val notification = notifier.progress(snapshot)
                if (foreground == null) {
                    foreground = try {
                        setForeground(notifier.foregroundInfo(notification))
                        true
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        // Android 12+ refuses foreground services started from the background:
                        // download as a regular job instead.
                        Log.i(TAG, "foreground not allowed, continuing without it: $e")
                        false
                    }
                    if (foreground == false) notifier.showProgress(notification)
                } else {
                    notifier.showProgress(notification)
                }
            }
            delay(UPDATE_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "SoundDownload"
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}
