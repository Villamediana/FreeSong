package com.villamediana.sound.social

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.sound.MainActivity
import com.villamediana.sound.Notifications
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import java.io.File
import java.util.concurrent.TimeUnit

/** Social notifications: a friend request, a friend accepted, a message from a friend, a jam invite. */
object SocialNotifier {
    /** Extra on the notification's intent: what to open ("friends", "friend:<id>", "chat:<id>", "jam"). */
    const val EXTRA_OPEN = "com.villamediana.sound.social.OPEN"

    fun request(context: Context, from: Profile) = post(
        context, Notifications.ID_SOCIAL_REQUEST,
        context.getString(R.string.social_notif_request_title),
        context.getString(R.string.social_notif_request_text, from.name),
        "friends",
    )

    fun accepted(context: Context, user: Profile) = post(
        context, Notifications.ID_SOCIAL_REQUEST,
        context.getString(R.string.social_notif_accepted_title, user.name),
        context.getString(R.string.social_notif_accepted_text),
        "friend:${user.id}",
    )

    /**
     * One notification per friend: a new message (a song or a text) replaces the last one from
     * them; tapping it opens the conversation. [quiet] while the app is open.
     */
    fun message(context: Context, from: Profile, d: Delivery, quiet: Boolean = false) {
        val song = d.track
        val (title, text) = when {
            d.kind == "text" && d.text != null -> from.name to d.text
            song != null -> context.getString(R.string.social_notif_song_title, from.name) to
                listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · ")
            else -> return
        }
        post(context, Notifications.ID_SOCIAL_SONG + (from.id.hashCode() and 0xffff), title, text, "chat:${from.id}", quiet = quiet)
    }

    fun jam(context: Context, from: Profile) = post(
        context, Notifications.ID_SOCIAL_JAM,
        context.getString(R.string.social_notif_jam_title, from.name),
        context.getString(R.string.social_notif_jam_text),
        "jam",
    )

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, text: String, open: String, quiet: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !manager.areNotificationsEnabled()) return
        Notifications.createChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN, open)
        val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            id,
            NotificationCompat.Builder(context, Notifications.CHANNEL_SOCIAL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setSilent(quiet)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .build(),
        )
    }
}

/**
 * With the app closed: once an hour (with internet) it fetches the songs friends sent and the
 * friend requests, and tells about the new ones.
 */
class SocialWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val hub = SoundApplication.graph.social
        if (hub.account.loaded() != AccountStatus.READY) return Result.success()
        val onScreen = hub.foreground.value
        for ((from, d) in hub.drainMailbox()) {
            if (!onScreen && from != null) SocialNotifier.message(applicationContext, from, d)
        }
        // The vault too: what this profile's other phones did (hearts, playlists, songs) comes in.
        runCatching { hub.vault.sync() }
        val page = hub.refreshFriends() ?: return Result.success()
        val seenFile = File(applicationContext.filesDir, "social-requests-notified.txt")
        val seen = runCatching { seenFile.readLines().toSet() }.getOrDefault(emptySet())
        val fresh = page.incoming.filter { it.requestId.toString() !in seen }
        if (!onScreen) fresh.firstOrNull()?.let { SocialNotifier.request(applicationContext, it.user) }
        runCatching { seenFile.writeText(page.incoming.joinToString("\n") { it.requestId.toString() }) }
        return Result.success()
    }

    companion object {
        private const val NAME = "social-check"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SocialWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
