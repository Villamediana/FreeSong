package com.villamediana.sound.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** What queued downloads wait for, from the network and the "Baixar só no Wi-Fi" setting. */
class DownloadWaitTest {
    @Test fun offlineTheyWaitForAConnection() {
        assertEquals(DownloadWait.NETWORK, DownloadWait.of(online = false, unmetered = false, wifiOnly = false))
        assertEquals(DownloadWait.NETWORK, DownloadWait.of(online = false, unmetered = false, wifiOnly = true))
    }

    @Test fun onMobileDataWithWifiOnlyTheyWaitForTheWifi() {
        assertEquals(DownloadWait.WIFI, DownloadWait.of(online = true, unmetered = false, wifiOnly = true))
        assertEquals(DownloadWait.NONE, DownloadWait.of(online = true, unmetered = false, wifiOnly = false))
    }

    @Test fun onWifiTheyRun() {
        assertEquals(DownloadWait.NONE, DownloadWait.of(online = true, unmetered = true, wifiOnly = true))
    }
}
