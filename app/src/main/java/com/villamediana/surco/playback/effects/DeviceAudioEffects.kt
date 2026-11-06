package com.villamediana.surco.playback.effects

import android.content.Context
import com.villamediana.surco.playback.AudioEffectsController
import com.villamediana.surco.playback.EqPreset
import com.villamediana.surco.playback.EqState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** android.media.audiofx Equalizer/BassBoost/Virtualizer on the player's session. (Stub.) */
class DeviceAudioEffects(private val context: Context, private val scope: CoroutineScope) : AudioEffectsController {
    override val state: StateFlow<EqState> = MutableStateFlow(EqState())
    override fun setEnabled(enabled: Boolean) {}
    override fun selectPreset(preset: EqPreset) {}
    override fun setBandLevel(band: Int, levelMb: Int) {}
    override fun setBassBoost(strength: Int) {}
    override fun setVirtualizer(strength: Int) {}
    override fun reset() {}
    override fun attach(audioSessionId: Int) {}
    override fun release() {}
}
