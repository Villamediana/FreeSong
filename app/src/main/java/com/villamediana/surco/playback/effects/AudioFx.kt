package com.villamediana.surco.playback.effects

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import kotlin.math.roundToInt

/** What a phone's audio effects offer on a session. */
internal data class EffectCaps(
    /** Centre of each equalizer band, in Hz, in band order. Empty = no equalizer. */
    val centersHz: List<Int>,
    val minMb: Int,
    val maxMb: Int,
    val bassBoost: Boolean,
    val virtualizer: Boolean,
) {
    val hasEqualizer: Boolean get() = centersHz.isNotEmpty()
}

/** Everything the effects should be doing right now. */
internal data class EffectConfig(
    val enabled: Boolean,
    /** One level per band (mB), already clamped. */
    val levelsMb: List<Int>,
    val bassBoost: Int,
    val virtualizer: Int,
)

/** The effects alive on one audio session. */
internal interface EffectChain {
    val caps: EffectCaps
    /** Pushes [config]; values already applied are skipped unless [force]. Never throws. */
    fun apply(config: EffectConfig, force: Boolean = false)
    fun release()
}

internal interface EffectFactory {
    /**
     * Equalizer, bass boost and virtualizer on [sessionId] — whichever the phone offers; null
     * when it offers none. [onControlRegained] runs when another app hands control back.
     */
    fun open(sessionId: Int, onControlRegained: () -> Unit): EffectChain?

    /** What the phone offers, learned on a throwaway session before anything plays. */
    fun probe(): EffectCaps?
}

/**
 * android.media.audiofx implementation. Every call is guarded: some phones lack an effect, some
 * throw when another app (a system "sound enhancer") holds control, some fail natively.
 */
internal class AudioFxFactory(private val context: Context) : EffectFactory {

    override fun open(sessionId: Int, onControlRegained: () -> Unit): EffectChain? {
        if (sessionId <= 0) return null
        val eq = guard { Equalizer(PRIORITY, sessionId) }
        val bands = eq?.let { readBands(it) }
        val equalizer = if (bands != null) eq else {
            eq?.let { guard { it.release() } }
            null
        }
        val bass = guard { BassBoost(PRIORITY, sessionId) }?.let { keepIf(it) { it.strengthSupported } }
        val virtualizer = guard { Virtualizer(PRIORITY, sessionId) }?.let { keepIf(it) { it.strengthSupported } }
        if (equalizer == null && bass == null && virtualizer == null) return null
        val caps = EffectCaps(
            centersHz = bands?.centersHz.orEmpty(),
            minMb = bands?.minMb ?: 0,
            maxMb = bands?.maxMb ?: 0,
            bassBoost = bass != null,
            virtualizer = virtualizer != null,
        )
        return AudioFxChain(caps, equalizer, bass, virtualizer, onControlRegained)
    }

    override fun probe(): EffectCaps? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val session = guard { audio.generateAudioSessionId() }?.takeIf { it > 0 } ?: return null
        val chain = open(session) {} ?: return null
        return chain.caps.also { chain.release() }
    }

    private class Bands(val centersHz: List<Int>, val minMb: Int, val maxMb: Int)

    private fun readBands(eq: Equalizer): Bands? = guard {
        val count = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        if (count <= 0 || range.size < 2 || range[0] >= range[1]) return@guard null
        val centers = (0 until count).map { band -> (eq.getCenterFreq(band.toShort()) / 1000.0).roundToInt() }
        if (centers.any { it <= 0 }) return@guard null
        Bands(centers, range[0].toInt(), range[1].toInt())
    }

    private fun <E : AudioEffect> keepIf(effect: E, check: (E) -> Boolean): E? =
        if (guard { check(effect) } == true) effect else {
            guard { effect.release() }
            null
        }

    private companion object {
        /** Normal priority: a system-wide enhancer can still take over (and we re-apply when it lets go). */
        const val PRIORITY = 0
    }
}

private class AudioFxChain(
    override val caps: EffectCaps,
    private val eq: Equalizer?,
    private val bass: BassBoost?,
    private val virtualizer: Virtualizer?,
    onControlRegained: () -> Unit,
) : EffectChain {
    private val appliedLevels = arrayOfNulls<Int>(caps.centersHz.size)
    private var eqOn: Boolean? = null
    private var bassOn: Boolean? = null
    private var bassStrength: Int? = null
    private var virtualizerOn: Boolean? = null
    private var virtualizerStrength: Int? = null

    init {
        val listener = AudioEffect.OnControlStatusChangeListener { _, granted -> if (granted) onControlRegained() }
        listOfNotNull<AudioEffect>(eq, bass, virtualizer).forEach { effect -> guard { effect.setControlStatusListener(listener) } }
    }

    override fun apply(config: EffectConfig, force: Boolean) {
        if (force) forget()
        if (eq != null) {
            if (config.enabled) {
                config.levelsMb.forEachIndexed { band, mb ->
                    if (band < appliedLevels.size && appliedLevels[band] != mb) {
                        if (guard { eq.setBandLevel(band.toShort(), mb.toShort()) } != null) appliedLevels[band] = mb
                    }
                }
            }
            if (eqOn != config.enabled && enable(eq, config.enabled)) eqOn = config.enabled
        }
        if (bass != null) {
            val on = config.enabled && config.bassBoost > 0
            if (on && bassStrength != config.bassBoost && guard { bass.setStrength(config.bassBoost.toShort()) } != null) {
                bassStrength = config.bassBoost
            }
            if (bassOn != on && enable(bass, on)) bassOn = on
        }
        if (virtualizer != null) {
            val on = config.enabled && config.virtualizer > 0
            if (on && virtualizerStrength != config.virtualizer && guard { virtualizer.setStrength(config.virtualizer.toShort()) } != null) {
                virtualizerStrength = config.virtualizer
            }
            if (virtualizerOn != on && enable(virtualizer, on)) virtualizerOn = on
        }
    }

    private fun forget() {
        appliedLevels.fill(null)
        eqOn = null
        bassOn = null
        bassStrength = null
        virtualizerOn = null
        virtualizerStrength = null
    }

    override fun release() {
        listOfNotNull<AudioEffect>(eq, bass, virtualizer).forEach { effect ->
            guard { effect.setControlStatusListener(null) }
            enable(effect, false)
            guard { effect.release() }
        }
    }
}

private fun enable(effect: AudioEffect, on: Boolean): Boolean =
    guard { effect.setEnabled(on) } == AudioEffect.SUCCESS

/** Runs a platform audio-effect call; any failure (exception or native error) becomes null. */
private inline fun <T> guard(block: () -> T): T? =
    try {
        block()
    } catch (_: Throwable) {
        null
    }
