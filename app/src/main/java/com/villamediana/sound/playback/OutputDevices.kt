package com.villamediana.sound.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.villamediana.sound.R

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

        /** The output a device the listener picked is ([Outputs.Kind] of its type). */
        fun of(kind: Outputs.Kind, name: String?): AudioOutput = when (kind) {
            Outputs.Kind.PHONE -> Phone
            Outputs.Kind.BLUETOOTH -> Bluetooth(name)
            Outputs.Kind.WIRED, Outputs.Kind.USB -> Headphones
        }
    }
}

/**
 * Watches audio outputs coming and going (headphones plugged, Bluetooth connected) and reports the
 * current one by name: the output the listener picked ([preferred], see [OutputChoice]) while it is
 * connected, else Android's. A picked output that goes away is [forgotten][onPreferredGone].
 */
internal class OutputDeviceMonitor(
    private val context: Context,
    private val preferred: () -> Int? = { null },
    private val onPreferredGone: () -> Unit = {},
    private val onChange: (String) -> Unit,
) {
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
        val am = audio ?: return context.getString(R.string.playback_output_phone)
        val picked = OutputChoice.device(am, preferred())
        val out = if (picked != null) {
            Outputs.kindOf(picked.type)?.let { AudioOutput.of(it, picked.productName?.toString()?.trim()?.takeIf(String::isNotEmpty)) }
        } else {
            null
        } ?: AudioOutput.choose(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type to it.productName?.toString() })
        return when (out) {
            is AudioOutput.Bluetooth -> out.name ?: context.getString(R.string.playback_output_bluetooth)
            AudioOutput.Headphones -> context.getString(R.string.playback_output_headphones)
            AudioOutput.Phone -> context.getString(R.string.playback_output_phone)
        }
    }

    /** Says the current output again (the listener picked another one). */
    fun publish() {
        val am = audio
        if (am != null && preferred() != null && OutputChoice.device(am, preferred()) == null) onPreferredGone()
        val name = currentName()
        if (name != last) {
            last = name
            onChange(name)
        }
    }
}
