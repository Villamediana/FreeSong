package com.villamediana.surco.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.villamediana.surco.R

/** Where music is coming out, for "Tocando em …" in the player. */
internal sealed interface AudioOutput {
    data class Bluetooth(val name: String?) : AudioOutput
    data object Headphones : AudioOutput
    data object Phone : AudioOutput

    companion object {
        private val BLUETOOTH = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        private val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        /**
         * Picks the output Android routes media to, from the connected outputs ([type] to product
         * name): Bluetooth first, then wired/USB headphones, else the phone's speaker.
         */
        fun choose(devices: List<Pair<Int, String?>>): AudioOutput {
            devices.firstOrNull { it.first in BLUETOOTH }?.let { return Bluetooth(it.second?.trim()?.takeIf(String::isNotEmpty)) }
            if (devices.any { it.first in WIRED }) return Headphones
            return Phone
        }
    }
}

/** Watches audio outputs coming and going (headphones plugged, Bluetooth connected) and reports the current one by name. */
internal class OutputDeviceMonitor(private val context: Context, private val onChange: (String) -> Unit) {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var last: String? = null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = publish()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = publish()
    }

    fun start() {
        audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        publish()
    }

    fun stop() {
        audio?.unregisterAudioDeviceCallback(callback)
    }

    fun currentName(): String {
        val devices = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .map { it.type to it.productName?.toString() }
        return when (val out = AudioOutput.choose(devices)) {
            is AudioOutput.Bluetooth -> out.name ?: context.getString(R.string.playback_output_bluetooth)
            AudioOutput.Headphones -> context.getString(R.string.playback_output_headphones)
            AudioOutput.Phone -> context.getString(R.string.playback_output_phone)
        }
    }

    private fun publish() {
        val name = currentName()
        if (name != last) {
            last = name
            onChange(name)
        }
    }
}
