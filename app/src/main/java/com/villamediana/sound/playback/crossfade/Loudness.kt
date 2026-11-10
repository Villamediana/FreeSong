package com.villamediana.sound.playback.crossfade

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/*
 * How loud a stretch of a song sounds, the way the ear hears it: ITU-R BS.1770 (LUFS), the measure
 * streaming services level songs by. The audio goes through the K filter (a shelf that lifts the
 * highs, a high-pass that drops the rumble), then its power is summed over 100 ms blocks.
 */

/** One channel's K filter (BS.1770 stage 1 shelf + stage 2 high-pass), coefficients for any rate. */
internal class KWeighting(sampleRate: Int) {
    private val sb0: Double
    private val sb1: Double
    private val sb2: Double
    private val sa1: Double
    private val sa2: Double
    private val ha1: Double
    private val ha2: Double

    private var s1 = 0.0
    private var s2 = 0.0
    private var h1 = 0.0
    private var h2 = 0.0

    init {
        // Stage 1: the head's shelf (+4 dB above ~1.7 kHz).
        val f0 = 1681.974450955533
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        sb0 = (vh + vb * k / q + k * k) / a0
        sb1 = 2.0 * (k * k - vh) / a0
        sb2 = (vh - vb * k / q + k * k) / a0
        sa1 = 2.0 * (k * k - 1.0) / a0
        sa2 = (1.0 - k / q + k * k) / a0
        // Stage 2: the high-pass (~38 Hz).
        val hf = 38.13547087602444
        val hq = 0.5003270373238773
        val hk = tan(PI * hf / sampleRate)
        val ha0 = 1.0 + hk / hq + hk * hk
        ha1 = 2.0 * (hk * hk - 1.0) / ha0
        ha2 = (1.0 - hk / hq + hk * hk) / ha0
    }

    /** The weighted sample (transposed direct form II, both stages). */
    fun filter(x: Double): Double {
        val y1 = sb0 * x + s1
        s1 = sb1 * x - sa1 * y1 + s2
        s2 = sb2 * x - sa2 * y1
        // High-pass numerator is 1, -2, 1.
        val y2 = y1 + h1
        h1 = -2.0 * y1 - ha1 * y2 + h2
        h2 = y1 - ha2 * y2
        return y2
    }
}

/**
 * A stretch of a song as its loudness in 100 ms blocks: [power] holds each block's K-weighted mean
 * power summed over the channels (BS.1770's "z"), the first block starting at [startUs] of the song.
 */
class Envelope(val startUs: Long, val power: FloatArray) {
    val endUs: Long get() = startUs + power.size * BLOCK_US

    private fun indexOf(us: Long): Int = ((us - startUs) / BLOCK_US).toInt()

    /**
     * How loud [fromUs]..[toUs] sounds, gated as BS.1770 does (silence and the quietest moments
     * don't pull it down), in LUFS; null when nothing in there sounds.
     */
    fun loudness(fromUs: Long = startUs, toUs: Long = endUs): Float? {
        val from = indexOf(fromUs).coerceAtLeast(0)
        val to = indexOf(toUs).coerceAtMost(power.size)
        if (to - from < MOMENTARY_BLOCKS) return null
        val windows = (from..to - MOMENTARY_BLOCKS).map { momentaryPower(it) }
        val absolute = windows.filter { it > ABSOLUTE_GATE_POWER }
        if (absolute.isEmpty()) return null
        val relativeGate = absolute.average() * RELATIVE_GATE
        val gated = absolute.filter { it > relativeGate }
        return lufs(gated.average())
    }

    /** Loudness of the 400 ms window starting at block [i] (power). */
    private fun momentaryPower(i: Int): Double {
        var sum = 0.0
        for (k in i until i + MOMENTARY_BLOCKS) sum += power[k]
        return sum / MOMENTARY_BLOCKS
    }

    /**
     * Where the sound ends: after the last block louder than [thresholdLufs] that closes a 400 ms
     * stretch louder than it too (a lone click doesn't count); null when none.
     */
    fun lastSoundUs(thresholdLufs: Float): Long? {
        val gate = powerOf(thresholdLufs)
        for (i in power.size - 1 downTo MOMENTARY_BLOCKS - 1) {
            if (power[i] > gate && momentaryPower(i - MOMENTARY_BLOCKS + 1) > gate) return startUs + (i + 1) * BLOCK_US
        }
        return null
    }

    /**
     * Where the sound starts: the first block louder than [thresholdLufs] that opens a 400 ms
     * stretch louder than it too; null when none.
     */
    fun firstSoundUs(thresholdLufs: Float): Long? {
        val gate = powerOf(thresholdLufs)
        for (i in 0..power.size - MOMENTARY_BLOCKS) {
            if (power[i] > gate && momentaryPower(i) > gate) return startUs + i * BLOCK_US
        }
        return null
    }

    companion object {
        const val BLOCK_US = 100_000L

        /** BS.1770's momentary window: 400 ms. */
        const val MOMENTARY_BLOCKS = 4

        private const val ABSOLUTE_GATE_LUFS = -70f
        private val ABSOLUTE_GATE_POWER = powerOf(ABSOLUTE_GATE_LUFS)

        /** −10 LU below the absolute-gated level. */
        private val RELATIVE_GATE = 10.0.pow(-1.0)

        fun lufs(power: Double): Float = (-0.691 + 10.0 * log10(power)).toFloat()

        fun powerOf(lufs: Float): Double = 10.0.pow((lufs + 0.691) / 10.0)
    }
}

/**
 * Builds an [Envelope] from interleaved PCM (16-bit or float) handed in as it is decoded. Audio
 * before [fromUs] only warms the filters up; audio from [toUs] on is not kept.
 */
class LoudnessMeter(
    private val sampleRate: Int,
    private val channels: Int,
    private val fromUs: Long,
    private val toUs: Long,
) {
    private val filters = Array(channels) { KWeighting(sampleRate) }
    private val blockFrames = (sampleRate * Envelope.BLOCK_US / 1_000_000L).toInt()
    private val blocks = ArrayList<Float>()
    private var startUs = -1L
    private var blockSum = 0.0
    private var blockFill = 0

    /** True once audio reached [toUs]: nothing more is needed. */
    var full = false
        private set

    /** Interleaved PCM whose first frame plays at [ptsUs]; [buffer] is read, not kept. */
    fun feed(buffer: ByteBuffer, ptsUs: Long, float: Boolean) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerFrame = channels * if (float) 4 else 2
        val frames = b.remaining() / bytesPerFrame
        for (f in 0 until frames) {
            val us = ptsUs + f * 1_000_000L / sampleRate
            var sum = 0.0
            for (c in 0 until channels) {
                val x = if (float) b.float.toDouble() else b.short / 32768.0
                val y = filters[c].filter(x)
                sum += y * y
            }
            if (us < fromUs) continue
            if (us >= toUs) {
                full = true
                return
            }
            if (startUs < 0) startUs = us
            blockSum += sum
            if (++blockFill == blockFrames) {
                blocks += (blockSum / blockFrames).toFloat()
                blockSum = 0.0
                blockFill = 0
            }
        }
    }

    /** What was measured; null when not even one block. */
    fun envelope(): Envelope? = if (blocks.isEmpty() || startUs < 0) null else Envelope(startUs, blocks.toFloatArray())
}
