package com.villamediana.surco.playback

import android.media.AudioDeviceInfo
import com.villamediana.surco.playback.LibraryBrowser.Companion.page
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputTest {
    @Test
    fun `bluetooth wins, then wired headphones, else the phone`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Pixel"
        assertEquals(AudioOutput.Phone, AudioOutput.choose(listOf(speaker)))
        assertEquals(AudioOutput.Headphones, AudioOutput.choose(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES to null)))
        assertEquals(AudioOutput.Headphones, AudioOutput.choose(listOf(AudioDeviceInfo.TYPE_USB_HEADSET to "DAC")))
        assertEquals(
            AudioOutput.Bluetooth("WH-1000XM5"),
            AudioOutput.choose(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADSET to null, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to " WH-1000XM5 ")),
        )
        assertEquals(AudioOutput.Bluetooth("Buds"), AudioOutput.choose(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET to "Buds")))
        assertEquals(AudioOutput.Bluetooth(null), AudioOutput.choose(listOf(AudioDeviceInfo.TYPE_BLE_SPEAKER to "")))
        assertEquals(AudioOutput.Phone, AudioOutput.choose(emptyList()))
    }

    @Test
    fun `browse pages`() {
        val list = (0 until 10).toList()
        assertEquals(listOf(0, 1, 2, 3), list.page(0, 4))
        assertEquals(listOf(8, 9), list.page(2, 4))
        assertEquals(emptyList<Int>(), list.page(3, 4))
        assertEquals(list, list.page(0, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), list.page(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
