package com.villamediana.sound.chords

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mono audio from [srcRate] to [dstRate], block by block as the decoder hands it over. Going down,
 * everything above the new Nyquist is filtered out first (an 8th-order Butterworth at 0.45 of the
 * new rate): picked straight from 44.1 or 48 kHz, cymbals and sibilants would fold down into the
 * notes' range and be heard as notes. Then linear interpolation between the source samples.
 */
internal class MonoResampler(srcRate: Int, private val dstRate: Int, private val antiAlias: Boolean = true) {
    var srcRate: Int = srcRate
        private set
    private var step = srcRate.toDouble() / dstRate
    private var filter = if (antiAlias) lowPass(srcRate, dstRate) else emptyList()
    private var srcIndex = 0L // global index of the first frame of the current block
    private var next = 0.0 // position, in source frames, of the next output sample
    private var prev = 0f

    /** The decoder said the rate changed: what comes next is at [rate]. */
    fun rate(rate: Int) {
        if (rate == srcRate) return
        // Carry on from where the output is, in the new rate's frames.
        next = ((next - srcIndex) * rate / srcRate).coerceAtLeast(-1.0)
        srcIndex = 0
        srcRate = rate
        step = rate.toDouble() / dstRate
        filter = if (antiAlias) lowPass(rate, dstRate) else emptyList()
    }

    /** The output samples for the first [frames] of [block] (overwritten by the filter). */
    fun push(block: FloatArray, frames: Int = block.size): FloatArray {
        if (frames <= 0) return FloatArray(0)
        for (section in filter) section.run(block, frames)
        val end = srcIndex + frames
        // An output sample needs the source samples on both sides: the last one of a block waits
        // for the next block's first (the previous block's last is kept as [prev]).
        var out = FloatArray(kotlin.math.ceil((end - 1 - next) / step).toInt().coerceAtLeast(0) + 2)
        var n = 0
        while (next < end - 1) {
            val local = next - srcIndex
            val a = kotlin.math.floor(local).toInt()
            val t = (local - a).toFloat()
            val s0 = if (a < 0) prev else block[a]
            val s1 = block[a + 1]
            if (n == out.size) out = out.copyOf(n * 2 + 4)
            out[n++] = s0 * (1 - t) + s1 * t
            next += step
        }
        prev = block[frames - 1]
        srcIndex = end
        return if (n == out.size) out else out.copyOf(n)
    }

    /** One second-order section (RBJ low-pass), transposed direct form II, in doubles. */
    private class Biquad(fc: Double, fs: Double, q: Double) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var z1 = 0.0
        private var z2 = 0.0

        init {
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            b0 = (1 - cos(w0)) / 2 / a0
            b1 = (1 - cos(w0)) / a0
            b2 = b0
            a1 = -2 * cos(w0) / a0
            a2 = (1 - alpha) / a0
        }

        fun run(x: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val input = x[i].toDouble()
                val y = b0 * input + z1
                z1 = b1 * input - a1 * y + z2
                z2 = b2 * input - a2 * y
                x[i] = y.toFloat()
            }
        }
    }

    private companion object {
        /** The Q of each section of an 8th-order Butterworth. */
        val BUTTERWORTH_8 = (1..4).map { k -> 1 / (2 * cos((2 * k - 1) * PI / 16)) }

        fun lowPass(srcRate: Int, dstRate: Int): List<Biquad> =
            if (srcRate <= dstRate) emptyList() else BUTTERWORTH_8.map { Biquad(0.45 * dstRate, srcRate.toDouble(), it) }
    }
}
