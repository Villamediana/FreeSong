package com.villamediana.sound.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.villamediana.sound.MainActivity
import com.villamediana.sound.Notifications
import com.villamediana.sound.R
import com.villamediana.sound.data.db.DownloadTask
import java.text.NumberFormat

/**
 * The two download notifications: the ongoing one while a run is active ("Baixando 3 de 12",
 * current song, overall progress, Cancel) and the summary at the end ("12 músicas baixadas").
 * Posting is skipped quietly when notifications are off or POST_NOTIFICATIONS was denied.
 */
internal class DownloadNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val cancelAction = "${context.packageName}.download.CANCEL_ALL"

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    fun progress(s: RunSnapshot): Notification {
        val first = s.running.firstOrNull()
        val title = when {
            first == null -> context.getString(R.string.download_notif_preparing)
            s.total <= 1 -> context.getString(R.string.download_notif_single)
            else -> context.getString(R.string.download_notif_progress_title, s.position, s.total)
        }
        val text = when {
            first == null -> null
            s.running.size == 1 -> context.getString(R.string.download_notif_track, first.title, first.artist)
            else -> context.getString(R.string.download_notif_more, first.title, s.running.size - 1)
        }
        val indeterminate = first == null
        return NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (indeterminate) null else NumberFormat.getPercentInstance().format(s.fraction.toDouble()))
            .setProgress(PROGRESS_MAX, (s.fraction * PROGRESS_MAX).toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.download_notif_cancel), cancelAllIntent())
            .build()
    }

    fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_DOWNLOADS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_DOWNLOADS, notification)
        }

    fun showProgress(notification: Notification) = post(Notifications.ID_DOWNLOADS, notification)

    fun clearProgress() = manager.cancel(Notifications.ID_DOWNLOADS)

    /** Posts the end-of-run summary; nothing when the run finished no task. */
    fun showSummary(report: RunReport) {
        summary(report)?.let { post(Notifications.ID_DOWNLOADS_DONE, it) }
    }

    fun summary(report: RunReport): Notification? {
        val done = report.done.toList()
        val failed = report.failed.toList()
        if (done.isEmpty() && failed.isEmpty()) return null
        val res = context.resources
        val batch = (done + failed).map { it.batchTitle }.distinct().singleOrNull()

        val title = if (done.isNotEmpty()) {
            res.getQuantityString(R.plurals.download_summary_done, done.size, done.size)
        } else {
            res.getQuantityString(R.plurals.download_summary_failed_title, failed.size, failed.size)
        }
        val parts = buildList {
            when {
                batch != null -> add(batch)
                done.size == 1 && failed.isEmpty() -> add(trackLine(done.single()))
                done.isEmpty() && failed.size == 1 -> add(trackLine(failed.single()))
            }
            if (done.isNotEmpty() && failed.isNotEmpty()) add(res.getQuantityString(R.plurals.download_summary_failed, failed.size, failed.size))
            if (done.isEmpty() && failed.size == 1) failed.single().error?.let { add(it) }
        }
        val text = parts.joinToString(context.getString(R.string.download_separator)).ifEmpty { null }
        return NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADS_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    /** The Cancel button of the progress notification lands here while a worker runs. */
    fun registerCancelReceiver(onCancel: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == cancelAction) onCancel()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(cancelAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    fun unregister(receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun trackLine(task: DownloadTask) = context.getString(R.string.download_notif_track, task.title, task.artist)

    @SuppressLint("MissingPermission") // checked in canPost()
    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelAllIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CANCEL,
        Intent(cancelAction).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val PROGRESS_MAX = 1000
        const val REQUEST_OPEN = 2101
        const val REQUEST_CANCEL = 2102
    }
}
