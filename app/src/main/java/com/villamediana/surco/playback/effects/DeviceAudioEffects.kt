package com.villamediana.surco.playback.effects

import android.content.Context
import com.villamediana.surco.playback.AudioEffectsController
import com.villamediana.surco.playback.EqBand
import com.villamediana.surco.playback.EqPreset
import com.villamediana.surco.playback.EqState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Equalizer, bass boost and virtualizer (android.media.audiofx) on the player's audio session.
 *
 * The listener's choices ([EffectsSettings]) live in their own DataStore and are independent of
 * the phone; the phone's bands are learned from the live session — or, before anything plays,
 * from a throwaway session — and every change is pushed to the live effects right away. Safe to
 * call from any thread; works before [attach] (the state reflects the saved choices).
 */
@OptIn(FlowPreview::class)
class DeviceAudioEffects internal constructor(
    private val store: EffectsPersistence,
    private val factory: EffectFactory,
    private val scope: CoroutineScope,
    probeOnStart: Boolean,
) : AudioEffectsController {

    constructor(context: Context, scope: CoroutineScope) :
        this(EffectsStore(context), AudioFxFactory(context.applicationContext), scope, probeOnStart = true)

    private val lock = Any()
    private var settings = EffectsSettings()
    private var loaded = false
    /** Changes made before the saved settings were read; replayed on top of them. */
    private val early = mutableListOf<(EffectsSettings, EffectCaps?) -> EffectsSettings>()
    private var chain: EffectChain? = null
    private var sessionId = 0
    /** Caps learned from the probe or from the last live session — used while nothing is attached. */
    private var knownCaps: EffectCaps? = null

    private val _state = MutableStateFlow(EqState())
    override val state: StateFlow<EqState> = _state.asStateFlow()

    private val toSave = MutableStateFlow<EffectsSettings?>(null)

    init {
        scope.launch {
            val saved = store.load()
            synchronized(lock) {
                val caps = currentCaps()
                settings = early.fold(saved) { s, change -> change(s, caps) }
                if (early.isNotEmpty()) toSave.value = settings
                early.clear()
                loaded = true
                applyLocked()
            }
            if (probeOnStart && synchronized(lock) { currentCaps() } == null) {
                val probed = factory.probe()
                synchronized(lock) {
                    if (probed != null && knownCaps == null) knownCaps = probed
                    applyLocked()
                }
            }
        }
        scope.launch {
            toSave.filterNotNull().debounce(SAVE_DEBOUNCE_MS).collect { runCatching { store.save(it) } }
        }
    }

    override fun setEnabled(enabled: Boolean) = mutate { s, _ -> s.copy(enabled = enabled) }

    override fun selectPreset(preset: EqPreset) = mutate { s, _ -> s.copy(preset = preset) }

    override fun setBandLevel(band: Int, levelMb: Int) = mutate { s, caps ->
        if (caps == null || band !in caps.centersHz.indices) return@mutate s
        val levels = levelsFor(s, caps).toMutableList()
        levels[band] = EqCurves.clampLevel(levelMb, caps.minMb, caps.maxMb)
        s.copy(preset = null, customLevels = EqCurves.levelsByFrequency(caps.centersHz, levels))
    }

    override fun setBassBoost(strength: Int) = mutate { s, _ -> s.copy(bassBoost = strength.coerceIn(0, STRENGTH_MAX)) }

    override fun setVirtualizer(strength: Int) = mutate { s, _ -> s.copy(virtualizer = strength.coerceIn(0, STRENGTH_MAX)) }

    /** Flat curve, no bass boost, no virtualizer (on/off stays as it is). */
    override fun reset() = mutate { s, _ -> s.copy(preset = EqPreset.FLAT, customLevels = emptyMap(), bassBoost = 0, virtualizer = 0) }

    override fun attach(audioSessionId: Int) {
        synchronized(lock) {
            if (audioSessionId > 0 && audioSessionId == sessionId && chain != null) {
                applyLocked(force = true)
                return
            }
            chain?.release()
            chain = null
            sessionId = 0
            if (audioSessionId > 0) {
                val opened = factory.open(audioSessionId) { onControlRegained(audioSessionId) }
                if (opened != null) {
                    chain = opened
                    sessionId = audioSessionId
                    if (opened.caps.hasEqualizer || knownCaps == null) knownCaps = opened.caps
                }
            }
            applyLocked(force = true)
        }
    }

    override fun release() {
        synchronized(lock) {
            chain?.release()
            chain = null
            sessionId = 0
            applyLocked()
        }
        toSave.value?.let { pending -> scope.launch { runCatching { store.save(pending) } } }
    }

    private fun onControlRegained(session: Int) {
        synchronized(lock) {
            if (session == sessionId) applyLocked(force = true)
        }
    }

    private fun mutate(change: (EffectsSettings, EffectCaps?) -> EffectsSettings) {
        synchronized(lock) {
            if (!loaded) early += change
            val next = change(settings, currentCaps())
            if (next == settings) return
            settings = next
            applyLocked()
            if (loaded) toSave.value = next
        }
    }

    private fun currentCaps(): EffectCaps? = chain?.caps ?: knownCaps

    private fun applyLocked(force: Boolean = false) {
        val caps = currentCaps()
        val levels = levelsFor(settings, caps)
        chain?.apply(EffectConfig(settings.enabled, levels, settings.bassBoost, settings.virtualizer), force)
        _state.value = stateOf(settings, caps, levels)
    }

    internal companion object {
        const val SAVE_DEBOUNCE_MS = 150L

        /** The band levels [s] asks for on a phone with [caps] (empty without an equalizer). */
        fun levelsFor(s: EffectsSettings, caps: EffectCaps?): List<Int> {
            if (caps == null || !caps.hasEqualizer) return emptyList()
            val preset = s.preset
            return if (preset != null) {
                EqCurves.presetLevels(preset, caps.centersHz, caps.minMb, caps.maxMb)
            } else {
                EqCurves.levelsFromFrequencies(s.customLevels, caps.centersHz, caps.minMb, caps.maxMb)
            }
        }

        fun stateOf(s: EffectsSettings, caps: EffectCaps?, levels: List<Int>): EqState {
            val eq = caps?.takeIf { it.hasEqualizer }
            return EqState(
                available = eq != null,
                enabled = s.enabled,
                bands = eq?.centersHz?.mapIndexed { i, hz -> EqBand(i, hz, levels.getOrElse(i) { 0 }) }.orEmpty(),
                minLevelMb = eq?.minMb ?: EqState().minLevelMb,
                maxLevelMb = eq?.maxMb ?: EqState().maxLevelMb,
                preset = s.preset,
                bassBoost = s.bassBoost,
                bassBoostAvailable = caps?.bassBoost == true,
                virtualizer = s.virtualizer,
                virtualizerAvailable = caps?.virtualizer == true,
            )
        }
    }
}
