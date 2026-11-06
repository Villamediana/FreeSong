package com.villamediana.surco.download

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.villamediana.surco.Notifications
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.DownloadTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DownloadNotifierTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notifier get() = DownloadNotifier(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        Notifications.createChannels(context)
    }

    private fun grant() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun task(i: Int, batch: String? = "Doble Vida", error: String? = null) = DownloadTask(
        id = i.toLong(), videoId = "v$i", url = "u", title = "Song $i", artist = "Soda Stereo", batchTitle = batch,
        state = if (error == null) DownloadState.DONE else DownloadState.FAILED, error = error,
    )

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun progressNotification() {
        val n = notifier.progress(RunSnapshot(finished = 2, queued = 8, running = listOf(RunningItem("De Música Ligera", "Soda Stereo", 0.5f), RunningItem("Persiana Americana", "Soda Stereo", 0f))))
        assertEquals("Baixando 3 de 12", n.title())
        assertEquals("De Música Ligera e mais 1", n.text())
        assertEquals(Notifications.CHANNEL_DOWNLOADS, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, n.actions.size)
        assertEquals("Cancelar", n.actions[0].title.toString())
        val intent = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals("${context.packageName}.download.CANCEL_ALL", intent.action)
        assertEquals(context.packageName, intent.`package`)

        val single = notifier.progress(RunSnapshot(finished = 0, queued = 0, running = listOf(RunningItem("Signos", "Soda Stereo", 0.2f))))
        assertEquals("Baixando música", single.title())
        assertEquals("Signos · Soda Stereo", single.text())
        assertEquals("Preparando downloads", notifier.progress(RunSnapshot(0, 3, emptyList())).title())
    }

    @Test fun summaryOfAnAlbum() {
        val report = RunReport().apply {
            done += (1..12).map { task(it) }
            failed += (13..14).map { task(it, error = "Vídeo indisponível") }
        }
        val n = notifier.summary(report)!!
        assertEquals("12 músicas baixadas", n.title())
        assertEquals("Doble Vida · 2 falharam", n.text())
        assertEquals(Notifications.CHANNEL_DOWNLOADS_DONE, n.channelId)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(
            "com.villamediana.surco.MainActivity",
            shadowOf(n.contentIntent).savedIntent.component!!.className,
        )
    }

    @Test fun summaryOfOneSongAndOfFailures() {
        val one = notifier.summary(RunReport().apply { done += task(1, batch = null) })!!
        assertEquals("1 música baixada", one.title())
        assertEquals("Song 1 · Soda Stereo", one.text())

        val failedOne = notifier.summary(RunReport().apply { failed += task(2, batch = null, error = "Vídeo indisponível") })!!
        assertEquals("1 download falhou", failedOne.title())
        assertEquals("Song 2 · Soda Stereo · Vídeo indisponível", failedOne.text())

        val mixed = notifier.summary(RunReport().apply { done += task(3, batch = null); done += task(4, batch = "X") })!!
        assertEquals("2 músicas baixadas", mixed.title())
        assertNull(mixed.text())

        assertNull(notifier.summary(RunReport()))
    }

    @Test fun nothingIsPostedWithoutPermission() {
        val report = RunReport().apply { done += task(1) }
        notifier.showSummary(report) // denied by default on API 33+: must not crash
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        grant()
        notifier.showSummary(report)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun cancelButtonReachesTheReceiver() {
        var canceled = 0
        val receiver = notifier.registerCancelReceiver { canceled++ }
        context.sendBroadcast(shadowOf(notifier.progress(RunSnapshot(0, 1, emptyList())).actions[0].actionIntent).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, canceled)
        notifier.unregister(receiver)
        notifier.unregister(receiver) // twice is harmless
    }

    @Test fun schedulerUsesTheNetworkConstraintOfTheSettings() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val wm = WorkManager.getInstance(context)

        WorkScheduler(context) { Settings(wifiOnly = false) }.schedule(SchedulePolicy.REPLACE)
        var infos = wm.getWorkInfosForUniqueWork(WorkScheduler.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)

        WorkScheduler(context) { Settings(wifiOnly = true) }.schedule(SchedulePolicy.REPLACE)
        infos = wm.getWorkInfosForUniqueWork(WorkScheduler.WORK_NAME).get()
        val live = infos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, live.size)
        assertEquals(NetworkType.UNMETERED, live.single().constraints.requiredNetworkType)
    }

    @Test fun workerWithAnEmptyQueueSucceedsQuietly() = runBlocking {
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
