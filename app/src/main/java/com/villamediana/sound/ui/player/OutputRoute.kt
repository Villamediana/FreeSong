package com.villamediana.sound.ui.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.playback.OutputChoice
import com.villamediana.sound.playback.Outputs
import kotlinx.coroutines.flow.MutableStateFlow

/** Kind of the device media is coming out of — picks the chip's icon. */
internal enum class OutputKind { PHONE, WIRED, BLUETOOTH, USB }

/** Where the music comes out; [external] = earphones, a speaker… are connected (the player offers the choice). */
internal data class OutputRoute(val kind: OutputKind, val deviceName: String?, val external: Boolean = kind != OutputKind.PHONE)

internal fun Outputs.Kind.toRouteKind(): OutputKind = when (this) {
    Outputs.Kind.PHONE -> OutputKind.PHONE
    Outputs.Kind.BLUETOOTH -> OutputKind.BLUETOOTH
    Outputs.Kind.WIRED -> OutputKind.WIRED
    Outputs.Kind.USB -> OutputKind.USB
}

/** The outputs on offer, where the music comes out now ([current]) and where Android would send it ([systemDefault]). */
internal data class OutputsState(val options: List<Outputs.Option>, val current: Outputs.Option?, val systemDefault: Int?) {
    val route: OutputRoute
        get() = OutputRoute(current?.kind?.toRouteKind() ?: OutputKind.PHONE, current?.name, external = options.any { it.kind != Outputs.Kind.PHONE })
}

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

    /** "Conectar dispositivo": the phone's Bluetooth settings (pairing, connecting). */
    fun openBluetooth(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Live outputs: follows headphones/Bluetooth coming and going, and the output picked in the player. */
@Composable
internal fun rememberOutputs(): OutputsState {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val choice = remember { runCatching { SoundApplication.graph.outputChoice.preferred }.getOrElse { MutableStateFlow(null) } }
    val preferred by choice.collectAsState()
    var raw by remember(audio) { mutableStateOf(audio?.let(OutputChoice::connected).orEmpty()) }
    var systemDefault by remember(audio) { mutableStateOf(audio?.let(OutputChoice::systemDefault)) }
    DisposableEffect(audio) {
        if (audio == null) return@DisposableEffect onDispose { }
        val refresh = {
            raw = OutputChoice.connected(audio)
            systemDefault = OutputChoice.systemDefault(audio)
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    val options = remember(raw) { Outputs.options(raw) }
    return OutputsState(options, Outputs.current(options, preferred, systemDefault), systemDefault)
}

/** Live media output route (see [rememberOutputs]). */
@Composable
internal fun rememberOutputRoute(): OutputRoute = rememberOutputs().route
