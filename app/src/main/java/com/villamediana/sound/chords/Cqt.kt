package com.villamediana.sound.chords

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The constant-Q spectrum [Btc] listens to, computed as librosa.cqt does for it (sr 22050, hop 2048,
 * 144 bins from C1, 24 a octave, Hann wavelets, 1% sparsity, scaled by √length), octave by octave on
 * the signal halved each time — with a plain windowed-sinc halving filter where librosa uses soxr.
 * tools/chords/bench/cqt_proto.py is the same thing in numpy; on the benchmark both give the model
 * the same accuracy as librosa's own.
 *
 * Like BTC's training, the song is cut in 10-second pieces and each is transformed on its own:
 * 108 frames a piece, 10/108 s each.
 */
internal object Cqt {
    const val RATE = 22_050
    const val BINS = 144
    const val FRAMES_PER_PIECE = 108
    const val PIECE = RATE * 10
    const val FRAME_SECONDS = 10.0 / FRAMES_PER_PIECE

    private const val HOP = 2048
    private const val BPO = 24
    private const val OCTAVES = BINS / BPO
    private const val FMIN = 32.70319566257483 // C1

    private val freqs = DoubleArray(BINS) { FMIN * 2.0.pow(it.toDouble() / BPO) }
    private val q: Double = run {
        val r = 2.0.pow(2.0 / BPO)
        (r + 1) / (r - 1)
    }

    /** After the one early halving (the top filter's cutoff is far below 11 kHz). */
    private const val SR_EARLY = RATE / 2.0
    private val lengths = DoubleArray(BINS) { q * SR_EARLY / freqs[it] }

    /** One octave's filters in the frequency domain, sparse: per bin, the FFT bins it uses and their weights. */
    private class Bank(val nFft: Int, val index: Array<IntArray>, val re: Array<DoubleArray>, val im: Array<DoubleArray>)

    /** Built once: the same for every piece (octave i runs at 11025 / 2^i Hz). */
    private val banks: List<Bank> by lazy { List(OCTAVES) { i -> bank(i, SR_EARLY / (1 shl i)) } }

    private val halving: DoubleArray = run {
        val taps = 65
        val beta = 7.86
        val h = DoubleArray(taps) { k ->
            val n = k - (taps - 1) / 2.0
            val x = 0.5 * n
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = 2.0 * k / (taps - 1) - 1
            0.5 * sinc * bessel0(beta * sqrt(1 - r * r)) / bessel0(beta)
        }
        val sum = h.sum()
        DoubleArray(taps) { h[it] / sum }
    }

    /** log |CQT| of [audio] (mono, [RATE] Hz): one row of [BINS] per frame, BTC's input before it is standardised. */
    fun features(audio: FloatArray, isActive: () -> Boolean = { true }): Array<FloatArray> {
        val rows = ArrayList<FloatArray>(audio.size / HOP + FRAMES_PER_PIECE)
        var at = 0
        while (true) {
            if (!isActive()) throw kotlinx.coroutines.CancellationException()
            // Whole pieces while more than one is left; the rest, however long, last.
            val last = audio.size <= at + PIECE
            val end = if (last) audio.size else at + PIECE
            rows += piece(audio, at, end)
            if (last) break
            at = end
        }
        return rows.toTypedArray()
    }

    private fun piece(audio: FloatArray, from: Int, to: Int): List<FloatArray> {
        var y = halve(DoubleArray(to - from) { audio[from + it].toDouble() })
        var hop = HOP / 2
        val out = arrayOfNulls<Array<DoubleArray>>(OCTAVES) // per octave: [bin][frame] magnitudes
        for (i in 0 until OCTAVES) {
            out[i] = response(y, hop, banks[i])
            if (i < OCTAVES - 1) {
                hop /= 2
                y = halve(y)
            }
        }
        val frames = out.minOf { it!![0].size }
        return List(frames) { t ->
            FloatArray(BINS) { b ->
                val octave = OCTAVES - 1 - b / BPO // octave 0 is the top one
                val v = out[octave]!![b % BPO][t] / sqrt(lengths[b])
                ln(v + 1e-6).toFloat()
            }
        }
    }

    /** |filter bank · STFT| (window of ones, centred, zero padding) for one octave. */
    private fun response(y: DoubleArray, hop: Int, bank: Bank): Array<DoubleArray> {
        val n = bank.nFft
        val half = n / 2
        val frames = 1 + y.size / hop
        val fft = Fft(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val out = Array(BPO) { DoubleArray(frames) }
        for (t in 0 until frames) {
            val start = t * hop - half
            for (k in 0 until n) {
                val s = start + k
                re[k] = if (s >= 0 && s < y.size) y[s] else 0.0
                im[k] = 0.0
            }
            fft.transform(re, im)
            for (b in 0 until BPO) {
                val idx = bank.index[b]
                val br = bank.re[b]
                val bi = bank.im[b]
                var sr = 0.0
                var si = 0.0
                for (j in idx.indices) {
                    val f = idx[j]
                    sr += br[j] * re[f] - bi[j] * im[f]
                    si += br[j] * im[f] + bi[j] * re[f]
                }
                out[b][t] = sqrt(sr * sr + si * si)
            }
        }
        return out
    }

    /** librosa's __vqt_filter_fft for octave [octave] (0 = top) at [sr], times √(11025/sr). */
    private fun bank(octave: Int, sr: Double): Bank {
        val first = BINS - BPO * (octave + 1)
        val lens = DoubleArray(BPO) { q * sr / freqs[first + it] }
        val nFft = 2.0.pow(ceil(log2(lens.max()))).toInt()
        val fft = Fft(nFft)
        val gain = sqrt(SR_EARLY / sr)
        val index = arrayOfNulls<IntArray>(BPO)
        val reOut = arrayOfNulls<DoubleArray>(BPO)
        val imOut = arrayOfNulls<DoubleArray>(BPO)
        for (b in 0 until BPO) {
            val f = freqs[first + b]
            val ilen = lens[b]
            val k0 = floor(-ilen / 2).toLong()
            val k1 = floor(ilen / 2).toLong()
            val n = (k1 - k0).toInt()
            val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
            val norm = win.sum()
            val re = DoubleArray(nFft)
            val im = DoubleArray(nFft)
            val start = (nFft - n) / 2
            val scale = ilen / nFft / norm
            for (m in 0 until n) {
                val phase = (k0 + m) * 2 * PI * f / sr
                re[start + m] = cos(phase) * win[m] * scale
                im[start + m] = sin(phase) * win[m] * scale
            }
            fft.transform(re, im)
            val keep = nFft / 2 + 1
            val mags = DoubleArray(keep) { sqrt(re[it] * re[it] + im[it] * im[it]) }
            // Sparsify: drop the smallest coefficients making up 1% of the row's magnitude.
            val sorted = mags.sortedArray()
            val total = mags.sum()
            var cum = 0.0
            var threshold = sorted.last()
            for (v in sorted) {
                cum += v / total
                if (cum >= 0.01) { threshold = v; break }
            }
            val kept = (0 until keep).filter { mags[it] >= threshold }
            index[b] = kept.toIntArray()
            reOut[b] = DoubleArray(kept.size) { re[kept[it]] * gain }
            imOut[b] = DoubleArray(kept.size) { im[kept[it]] * gain }
        }
        @Suppress("UNCHECKED_CAST")
        return Bank(nFft, index as Array<IntArray>, reOut as Array<DoubleArray>, imOut as Array<DoubleArray>)
    }

    /** [y] at half its rate: low-passed below the new Nyquist, every other sample, ×√2 (librosa's scale=True). */
    private fun halve(y: DoubleArray): DoubleArray {
        val h = halving
        val pad = h.size / 2
        val n = (y.size + 1) / 2
        val root2 = sqrt(2.0)
        return DoubleArray(n) { j ->
            val c = 2 * j - pad
            var s = 0.0
            val kFrom = maxOf(0, -c)
            val kTo = minOf(h.size, y.size - c)
            for (k in kFrom until kTo) s += h[k] * y[c + k]
            s * root2
        }
    }

    /** The modified Bessel function I0 (for the Kaiser window). */
    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-16 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}
