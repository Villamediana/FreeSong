package com.villamediana.sound.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputsTest {
    private val speaker = Outputs.Raw(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-S918B")
    private val earpiece = Outputs.Raw(2, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "SM-S918B")
    private val philips = Outputs.Raw(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Philips TAT1209")
    private val philipsLe = Outputs.Raw(8, AudioDeviceInfo.TYPE_BLE_HEADSET, "Philips TAT1209")
    private val sco = Outputs.Raw(9, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Philips TAT1209")
    private val wired = Outputs.Raw(4, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "h2w")
    private val dac = Outputs.Raw(5, AudioDeviceInfo.TYPE_USB_HEADSET, " DAC ")

    @Test fun theChoicesAreWhatPlaysMusicOncePerDeviceEarphonesFirst() {
        val options = Outputs.options(listOf(earpiece, speaker, sco, philips, philipsLe, wired, dac))
        assertEquals(
            listOf(
                Outputs.Option(7, Outputs.Kind.BLUETOOTH, "Philips TAT1209"),
                Outputs.Option(4, Outputs.Kind.WIRED, null),
                Outputs.Option(5, Outputs.Kind.USB, "DAC"),
                // The phone's "product name" is its model: not shown.
                Outputs.Option(1, Outputs.Kind.PHONE, null),
            ),
            options,
        )
    }

    @Test fun whatPlaysIsThePickedOneElseAndroidsElseTheUsualOrder() {
        val options = Outputs.options(listOf(speaker, philips))
        assertEquals(1, Outputs.current(options, preferred = 1, systemDefault = 7)?.id)
        assertEquals(7, Outputs.current(options, preferred = null, systemDefault = 7)?.id)
        // A picked device that is gone, an unknown default: Bluetooth before the phone.
        assertEquals(7, Outputs.current(options, preferred = 42, systemDefault = null)?.id)
        assertNull(Outputs.current(emptyList(), preferred = null, systemDefault = null))
    }

    @Test fun pickingWhereAndroidPlaysAnywayLeavesNoPreference() {
        val options = Outputs.options(listOf(speaker, philips))
        val phone = options.first { it.kind == Outputs.Kind.PHONE }
        val earphones = options.first { it.kind == Outputs.Kind.BLUETOOTH }
        // Earphones on: the phone's speaker is a choice of its own; the earphones are Android's.
        assertEquals(1, Outputs.preference(phone, options, systemDefault = 7))
        assertNull(Outputs.preference(earphones, options, systemDefault = 7))
        // Before Android 13 (no default said): the usual order decides the same.
        assertNull(Outputs.preference(earphones, options, systemDefault = null))
    }
}
