package com.villamediana.surco.download

import com.villamediana.surco.data.Settings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
}
