package com.villamediana.sound.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.sound.BuildConfig
import com.villamediana.sound.MainActivity
import com.villamediana.sound.Notifications
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's [Updates], wired to Android (PackageInstaller, files, the release build's identity). */
fun createUpdates(context: Context, http: OkHttpClient, scope: CoroutineScope): Updates {
    val app = context.applicationContext
    val agent = "FreeSong/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
    return Updates(
        scope = scope,
        source = ReleaseSource(http, agent),
        downloader = ApkDownloader(http, agent),
        installer = SessionInstaller(app),
        memory = UpdateMemoryFile(File(app.filesDir, "update.json")),
        apkDir = File(app.cacheDir, "update"),
        current = ApkInfo(app.packageName, BuildConfig.VERSION_CODE.toLong()),
        currentVersion = BuildConfig.VERSION_NAME,
        enabled = !BuildConfig.DEBUG,
    )
}

/**
 * Android's PackageInstaller: one session with the APK, committed with a status receiver. From
 * Android 12 on, once FreeSong installed itself the first time, the next updates skip the
 * "Atualizar este app?" screen.
 */
internal class SessionInstaller(private val context: Context) : ApkInstaller {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun inspect(apk: File): ApkInfo? {
        // The int-flags call: still there on every API level (the PackageInfoFlags one is only 33+).
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return null
        return ApkInfo(info.packageName, PackageInfoCompat.getLongVersionCode(info))
    }

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        // A session left open by an install that never finished.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val status = Intent(context, UpdateStatusReceiver::class.java).setAction(UpdateStatusReceiver.ACTION)
                // Mutable: Android writes the status into it. Explicit, so nobody else can fill it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** PackageInstaller's answer for the session committed by [SessionInstaller]. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val result = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallResult.NEEDS_CONFIRMATION
            PackageInstaller.STATUS_SUCCESS -> InstallResult.SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.CANCELLED
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallResult.CONFLICT
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.STORAGE
            else -> InstallResult.FAILED
        }
        val confirmation = if (result == InstallResult.NEEDS_CONFIRMATION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            null
        }
        SoundApplication.graph.updates.onInstallStatus(result, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirmation)
    }

    companion object {
        const val ACTION = "com.villamediana.sound.update.STATUS"
    }
}

/**
 * The new version, right after Android replaced the app (Android closes the app to install it).
 * When the update came from the app: it opens again by itself where Android lets it (Android 10+
 * usually holds back an app opening itself from the background), and "FreeSong atualizado · toque
 * pra abrir" is there either way (it goes away once the app is on screen).
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val version = SoundApplication.graph.updates.afterUpdate() ?: return
        UpdateNotifier.updated(context, version)
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** A check a day in the background; a new version is announced once, when the app is not on screen. */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val updates = SoundApplication.graph.updates
        if (!updates.enabled) return Result.success()
        updates.check(manual = false)
        val onScreen = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val release = updates.toAnnounce() ?: return Result.success()
        if (!onScreen) UpdateNotifier.available(applicationContext, release)
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The two update notifications: a new version is out, and the app was updated. */
object UpdateNotifier {
    /** Extra on the notification's intent: open the update sheet. */
    const val EXTRA_OPEN = "com.villamediana.sound.update.OPEN"

    fun available(context: Context, release: AppRelease) {
        post(
            context,
            Notifications.ID_UPDATE_AVAILABLE,
            context.getString(R.string.update_notif_available_title),
            context.getString(R.string.update_notif_available_text, release.version),
            openSheet = true,
        )
    }

    /** The app is on screen: "atualizado · toque pra abrir" has done its job. */
    fun clearUpdated(context: Context) {
        NotificationManagerCompat.from(context).cancel(Notifications.ID_UPDATED)
    }

    fun updated(context: Context, version: String) {
        NotificationManagerCompat.from(context).cancel(Notifications.ID_UPDATE_AVAILABLE)
        post(
            context,
            Notifications.ID_UPDATED,
            context.getString(R.string.update_notif_done_title),
            context.getString(R.string.update_notif_done_text, version),
            openSheet = false,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, text: String, openSheet: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !manager.areNotificationsEnabled()) return
        Notifications.createChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (openSheet) putExtra(EXTRA_OPEN, true) }
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(id, notification)
    }
}
