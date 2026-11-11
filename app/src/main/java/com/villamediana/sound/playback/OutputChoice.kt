package com.villamediana.sound.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where FreeSong's sound should come out when the listener picked an output other than Android's
 * own — the phone's speaker with the Bluetooth earphones still on, say: that device's
 * [AudioDeviceInfo.getId]. null = wherever Android sends media. Only for this run of the app: the
 * device going away (or the app starting again) goes back to Android's choice. The music player,
 * its crossfade and the video follow it.
 */
class OutputChoice {
    private val _preferred = MutableStateFlow<Int?>(null)
    val preferred: StateFlow<Int?> = _preferred.asStateFlow()

    fun prefer(deviceId: Int?) {
        _preferred.value = deviceId
    }

    companion object {
        /** The connected output [deviceId] (null when it isn't connected, or for null). */
        fun device(audio: AudioManager, deviceId: Int?): AudioDeviceInfo? {
            deviceId ?: return null
            return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        }

        /** The connected outputs, as [Outputs] reads them. */
        fun connected(audio: AudioManager): List<Outputs.Raw> =
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { Outputs.Raw(it.id, it.type, it.productName?.toString()) }

        /** The output Android sends media to now (Android 13+ says it; before, the usual precedence). */
        fun systemDefault(audio: AudioManager): Int? {
            if (Build.VERSION.SDK_INT < 33) return null
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            return runCatching { audio.getAudioDevicesForAttributes(media).firstOrNull()?.id }.getOrNull()
        }
    }
}

/**
 * The outputs a listener can choose for music, from what the phone has connected: its own speaker,
 * Bluetooth audio, wired and USB earphones — not the earpiece, the call routes or the like. Pure.
 */
object Outputs {
    enum class Kind { PHONE, BLUETOOTH, WIRED, USB }

    /** An output as Android lists it: id, [AudioDeviceInfo] type, product name. */
    data class Raw(val id: Int, val type: Int, val name: String?)

    /** A choosable output; [name] is the device's own (Bluetooth, USB), null where the kind says it all. */
    data class Option(val id: Int, val kind: Kind, val name: String?)

    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
    )
    private val USB = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    fun kindOf(type: Int): Kind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.PHONE
        in BLUETOOTH -> Kind.BLUETOOTH
        in WIRED -> Kind.WIRED
        in USB -> Kind.USB
        else -> null
    }

    /**
     * What to offer, in a steady order: the connected earphones and speakers (Bluetooth, wired,
     * USB) and then the phone itself. One line per device (Android may list one twice, e.g. a
     * Bluetooth headset by two profiles).
     */
    fun options(raw: List<Raw>): List<Option> = raw
        .mapNotNull { r ->
            val kind = kindOf(r.type) ?: return@mapNotNull null
            // The phone's "product name" is its model, a wired jack has none worth showing.
            val named = kind == Kind.BLUETOOTH || kind == Kind.USB
            Option(r.id, kind, r.name?.trim()?.takeIf { named && it.isNotEmpty() })
        }
        .distinctBy { it.kind to (it.name ?: "") }
        .sortedBy { ORDER.indexOf(it.kind) }

    private val ORDER = listOf(Kind.BLUETOOTH, Kind.WIRED, Kind.USB, Kind.PHONE)

    /**
     * Where the music comes out: the [preferred] output while it is connected, else Android's
     * [systemDefault] when it is one of the [options], else the usual precedence (Bluetooth, then
     * wired, then USB, then the phone).
     */
    fun current(options: List<Option>, preferred: Int?, systemDefault: Int?): Option? =
        options.firstOrNull { it.id == preferred }
            ?: options.firstOrNull { it.id == systemDefault }
            ?: options.firstOrNull()

    /** The [preferred] output only while it differs from where Android would play anyway. */
    fun preference(chosen: Option, options: List<Option>, systemDefault: Int?): Int? =
        if (current(options, null, systemDefault)?.id == chosen.id) null else chosen.id
}
