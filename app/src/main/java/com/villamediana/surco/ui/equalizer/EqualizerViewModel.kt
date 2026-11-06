package com.villamediana.surco.ui.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.playback.AudioEffectsController
import com.villamediana.surco.playback.EqPreset
import com.villamediana.surco.playback.EqState
import com.villamediana.surco.playback.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class EqualizerViewModel(
    private val effects: AudioEffectsController,
    player: PlayerConnection,
) : ViewModel() {

    val state: StateFlow<EqState> = effects.state

    /** Something is loaded in the player — then "no equalizer" means the phone has none. */
    val hasMedia: StateFlow<Boolean> = player.state
        .map { it.hasMedia }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), player.state.value.hasMedia)

    fun setEnabled(enabled: Boolean) = effects.setEnabled(enabled)
    fun selectPreset(preset: EqPreset) = effects.selectPreset(preset)
    fun selectCustom() = effects.selectCustom()
    fun setBand(band: Int, levelMb: Int) = effects.setBandLevel(band, levelMb)
    fun setBassBoost(strength: Int) = effects.setBassBoost(strength)
    fun setVirtualizer(strength: Int) = effects.setVirtualizer(strength)
    fun reset() = effects.reset()

    companion object {
        fun create(): EqualizerViewModel = SurcoApplication.graph.let { EqualizerViewModel(it.effects, it.player) }
    }
}
