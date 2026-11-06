package com.villamediana.sound.ui.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Kind of the device media is coming out of — picks the chip's icon. */
internal enum class OutputKind { PHONE, WIRED, BLUETOOTH, USB }

internal data class OutputRoute(val kind: OutputKind, val deviceName: String?)

internal object OutputRoutes {
    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
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

    /**
     * The route Android sends media to, from the connected outputs ([type] to product name):
     * Bluetooth audio wins over wired/USB, which win over the phone's own speaker — the same
     * precedence the system uses for media.
     */
    fun pick(outputs: List<Pair<Int, String?>>): OutputRoute {
        outputs.firstOrNull { it.first in BLUETOOTH }?.let { return OutputRoute(OutputKind.BLUETOOTH, it.second?.takeIf(String::isNotBlank)) }
        outputs.firstOrNull { it.first in WIRED }?.let { return OutputRoute(OutputKind.WIRED, null) }
        outputs.firstOrNull { it.first in USB }?.let { return OutputRoute(OutputKind.USB, it.second?.takeIf(String::isNotBlank)) }
        return OutputRoute(OutputKind.PHONE, null)
    }

    fun current(audio: AudioManager): OutputRoute =
        pick(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type to it.productName?.toString() })

    /** Opens the system output picker (Android 14+), else Bluetooth settings. */
    fun openSwitcher(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            val shown = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
            if (shown) return
        }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Live media output route; follows headphones/Bluetooth being plugged and unplugged. */
@Composable
internal fun rememberOutputRoute(): OutputRoute {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var route by remember(audio) { mutableStateOf(audio?.let(OutputRoutes::current) ?: OutputRoute(OutputKind.PHONE, null)) }
    DisposableEffect(audio) {
        if (audio == null) return@DisposableEffect onDispose { }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { route = OutputRoutes.current(audio) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { route = OutputRoutes.current(audio) }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return route
}
