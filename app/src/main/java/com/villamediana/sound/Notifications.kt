package com.villamediana.sound

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_PLAYBACK = "playback"
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_DOWNLOADS_DONE = "downloads_done"
    const val CHANNEL_UPDATES = "updates"

    const val ID_PLAYBACK = 1001
    const val ID_DOWNLOADS = 2001
    const val ID_DOWNLOADS_DONE = 2002
    const val ID_UPDATE_AVAILABLE = 3001
    const val ID_UPDATED = 3002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PLAYBACK, context.getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_DOWNLOADS_DONE, context.getString(R.string.channel_downloads_done), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }
}
