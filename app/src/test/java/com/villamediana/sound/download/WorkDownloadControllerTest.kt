package com.villamediana.sound.download

import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WorkDownloadControllerTest {

    @Test fun flippingWifiOnlyReplacesTheWorkEvenWhileItRuns() = runBlocking {
        val settings = MutableStateFlow(Settings(wifiOnly = false))
        var pending = true
        val scheduled = CopyOnWriteArrayList<SchedulePolicy>()
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            followWifiOnly(settings, hasPending = { pending }, scheduler = DownloadScheduler { scheduled += it })
        }
        // The current value is not a change.
        assertEquals(emptyList<SchedulePolicy>(), scheduled)

        // Turned on in the middle of a run (on Wi-Fi or not): the running request still says CONNECTED.
        settings.value = Settings(wifiOnly = true)
        yield()
        assertEquals(listOf(SchedulePolicy.REPLACE), scheduled)

        // Turned off again: the request waiting for an unmetered network is replaced as well.
        settings.value = Settings(wifiOnly = false)
        yield()
        assertEquals(listOf(SchedulePolicy.REPLACE, SchedulePolicy.REPLACE), scheduled)

        // Other settings do not touch the work; nothing pending, nothing to schedule.
        settings.value = Settings(wifiOnly = false, parallelDownloads = 4)
        pending = false
        settings.value = Settings(wifiOnly = true)
        yield()
        assertEquals(2, scheduled.size)
        watcher.cancel()
    }

    @Test fun runningRowsShowTheLiveProgress() {
        fun row(id: Long, state: DownloadState, progress: Float) =
            DownloadTask(id = id, videoId = "v$id", url = "u", title = "t", artist = "a", state = state, progress = progress, bytesDone = 10, bytesTotal = 100, createdAt = 0, updatedAt = 0)
        val rows = listOf(row(1, DownloadState.RUNNING, 0.1f), row(2, DownloadState.DONE, 1f), row(3, DownloadState.RUNNING, 0.3f))
        assertSame(rows, withLiveProgress(rows, emptyMap()))

        val live = mapOf(1L to TaskProgress(0.6f, 60, 100), 2L to TaskProgress(0.9f, 90, 100))
        val merged = withLiveProgress(rows, live)
        assertEquals(row(1, DownloadState.RUNNING, 0.6f).copy(bytesDone = 60), merged[0])
        // A row that already finished keeps what the table says (the live value is a leftover).
        assertEquals(rows[1], merged[1])
        // Nothing live yet: the table's value stays.
        assertEquals(rows[2], merged[2])
    }
}
