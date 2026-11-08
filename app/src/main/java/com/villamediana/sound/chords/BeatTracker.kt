package com.villamediana.sound.chords

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the beats fall in a recording, so chords change on the beat the band plays (not on a grid
 * that drifts from it). Ellis' dynamic-programming beat tracker, the one librosa ships:
 *
 * 1. **Onset strength** — how much the sound grows from one frame to the next, band by band on a
 *    mel scale, in decibels: drums, strums and plucks stand out.
 * 2. **Tempo** — the period at which that strength repeats best (autocorrelation), leaning
 *    towards 120 BPM when two periods fit (a song in eighths is not twice as fast).
 * 3. **Beats** — the sequence of strong onsets spaced about one period apart that scores best,
 *    each step paying for straying from the period.
 */
object BeatTracker {
    const val RATE = 22_050
    const val HOP = 256
    private const val N_FFT = 1024
    private const val MELS = 64
    private const val FMIN = 30.0
    private const val FMAX = 8_000.0
    private const val TOP_DB = 80.0
    private const val TIGHTNESS = 100.0
    private const val MIN_BPM = 50.0
    private const val MAX_BPM = 220.0
    private const val PRIOR_BPM = 120.0

    /** Frames per second of the onset envelope. */
    const val FPS = RATE.toDouble() / HOP

    data class Beats(val timesMs: LongArray, val bpm: Double) {
        val periodMs: Double get() = 60_000.0 / bpm
        override fun equals(other: Any?) = other is Beats && other.bpm == bpm && other.timesMs.contentEquals(timesMs)
        override fun hashCode() = timesMs.contentHashCode() * 31 + bpm.hashCode()
    }

    /** The beats of [audio] (mono, [RATE] Hz). */
    fun track(audio: FloatArray, isActive: () -> Boolean = { true }): Beats {
        val env = onsetEnvelope(audio, isActive)
        val bpm = tempo(env)
        val frames = beats(env, bpm)
        return Beats(LongArray(frames.size) { (frames[it] / FPS * 1000).roundToInt().toLong() }, bpm)
    }

    // ------------------------------------------------------------- onsets --

    /** Onset strength per frame (frame t at t·HOP samples), normalised to unit deviation. */
    fun onsetEnvelope(audio: FloatArray, isActive: () -> Boolean = { true }): DoubleArray {
        val frames = audio.size / HOP + 1
        if (audio.isEmpty()) return DoubleArray(0)
        val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }
        val bank = melBank()
        val fft = Fft(N_FFT)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = DoubleArray(N_FFT / 2 + 1)
        val mel = Array(frames) { DoubleArray(MELS) }
        var peak = Double.NEGATIVE_INFINITY
        for (t in 0 until frames) {
            if (t % 512 == 0 && !isActive()) throw kotlinx.coroutines.CancellationException()
            // Centred frames, as librosa does: frame t is about sample t·HOP.
            val start = t * HOP - N_FFT / 2
            for (i in 0 until N_FFT) {
                val s = start + i
                re[i] = if (s in audio.indices) audio[s] * window[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            val row = mel[t]
            for (b in 0 until MELS) {
                var s = 0.0
                val (from, weights) = bank[b]
                for (j in weights.indices) s += weights[j] * power[from + j]
                val db = 10 * log10(max(s, 1e-10))
                row[b] = db
                if (db > peak) peak = db
            }
        }
        val floor = peak - TOP_DB
        val env = DoubleArray(frames)
        for (t in 1 until frames) {
            var s = 0.0
            for (b in 0 until MELS) s += max(0.0, max(mel[t][b], floor) - max(mel[t - 1][b], floor))
            env[t] = s / MELS
        }
        val mean = env.average()
        val sd = sqrt(env.sumOf { (it - mean) * (it - mean) } / max(1, env.size - 1))
        if (sd > 0) for (i in env.indices) env[i] /= sd
        return env
    }

    /** Triangular mel filters over the FFT bins: (first bin, weights) per band. */
    private fun melBank(): List<Pair<Int, DoubleArray>> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1 + hz / 700.0)
        fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1)
        val lo = hzToMel(FMIN)
        val hi = hzToMel(FMAX)
        val edges = DoubleArray(MELS + 2) { melToHz(lo + (hi - lo) * it / (MELS + 1)) }
        val binHz = RATE.toDouble() / N_FFT
        return (0 until MELS).map { b ->
            val (l, c, r) = Triple(edges[b], edges[b + 1], edges[b + 2])
            val first = (l / binHz).toInt().coerceAtLeast(0)
            val last = (r / binHz).toInt().coerceAtMost(N_FFT / 2)
            val w = DoubleArray(last - first + 1) { j ->
                val f = (first + j) * binHz
                when {
                    f <= l || f >= r -> 0.0
                    f <= c -> (f - l) / (c - l)
                    else -> (r - f) / (r - c)
                }
            }
            // Slaney-style area normalisation: wide high bands don't outweigh narrow low ones.
            val norm = 2.0 / (r - l)
            for (j in w.indices) w[j] *= norm
            first to w
        }
    }

    // -------------------------------------------------------------- tempo --

    /** Beats per minute: the autocorrelation peak of [env], weighed by a prior around 120. */
    fun tempo(env: DoubleArray): Double {
        if (env.size < 8) return PRIOR_BPM
        val minLag = (60.0 * FPS / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = min((60.0 * FPS / MIN_BPM).toInt() + 1, env.size - 1)
        if (maxLag <= minLag + 2) return PRIOR_BPM
        val mean = env.average()
        val x = DoubleArray(env.size) { env[it] - mean }
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            if (lag <= 0 || lag >= x.size) continue
            var s = 0.0
            for (i in 0 until x.size - lag) s += x[i] * x[i + lag]
            ac[lag] = s / (x.size - lag)
        }
        var best = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * FPS / lag
            val prior = exp(-0.5 * (log2(bpm / PRIOR_BPM) / 1.0).pow(2))
            val score = ac[lag] * prior
            if (score > bestScore) { bestScore = score; best = lag }
        }
        // Between frames: the parabola through the peak and its neighbours.
        val a = ac[best - 1]
        val b = ac[best]
        val c = ac[best + 1]
        val denom = a - 2 * b + c
        val shift = if (denom < 0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
        return 60.0 * FPS / (best + shift)
    }

    // -------------------------------------------------------------- beats --

    /** Beat frames: the dynamic program over [env] at [bpm], weak beats at either end trimmed. */
    fun beats(env: DoubleArray, bpm: Double): IntArray {
        if (env.isEmpty() || env.all { it <= 0.0 }) return IntArray(0)
        val period = 60.0 * FPS / bpm
        // A little smoothing at the scale of the period.
        val half = period.roundToInt()
        val kernel = DoubleArray(2 * half + 1) { val d = (it - half) * 32.0 / period; exp(-0.5 * d * d) }
        val local = DoubleArray(env.size)
        for (i in env.indices) {
            var s = 0.0
            for (k in kernel.indices) {
                val j = i + k - half
                if (j in env.indices) s += kernel[k] * env[j]
            }
            local[i] = s
        }
        val cum = DoubleArray(env.size)
        val back = IntArray(env.size) { -1 }
        val from = (2 * period).roundToInt()
        val to = (period / 2).roundToInt().coerceAtLeast(1)
        val txwt = DoubleArray(from - to + 1) { val gap = from - it; -TIGHTNESS * ln(gap / period).pow(2) }
        var first = true
        val threshold = 0.01 * local.max()
        for (i in env.indices) {
            var best = Double.NEGATIVE_INFINITY
            var arg = -1
            for (k in txwt.indices) {
                val j = i - (from - k)
                if (j < 0) continue
                val s = cum[j] + txwt[k]
                if (s > best) { best = s; arg = j }
            }
            // Until the first real onset, nothing to chain from.
            cum[i] = if (first || arg < 0) local[i] else local[i] + best
            back[i] = if (first || arg < 0) -1 else arg
            if (first && local[i] >= threshold) first = false
        }
        // The last beat: the last peak of the running score worth half the typical peak.
        val peaks = (1 until cum.size - 1).filter { cum[it] > cum[it - 1] && cum[it] >= cum[it + 1] }
        if (peaks.isEmpty()) return IntArray(0)
        val median = peaks.map { cum[it] }.sorted()[peaks.size / 2]
        var i = peaks.last { cum[it] >= 0.5 * median }
        val out = ArrayList<Int>()
        while (i >= 0) {
            out += i
            i = back[i]
        }
        out.reverse()
        return trim(out, local).toIntArray()
    }

    /** Beats at the very start or end with hardly any onset under them (silence, a fade) go. */
    private fun trim(beats: List<Int>, local: DoubleArray): List<Int> {
        if (beats.size < 3) return beats
        val width = 5
        val smooth = beats.map { b ->
            var s = 0.0
            var w = 0.0
            for (d in -width..width) {
                val j = b + d
                if (j !in local.indices) continue
                val h = 0.5 + 0.5 * cos(PI * d / (width + 1))
                s += h * local[j]
                w += h
            }
            s / max(w, 1e-9)
        }
        val threshold = 0.5 * sqrt(smooth.sumOf { it * it } / smooth.size)
        var a = 0
        var b = beats.size - 1
        while (a < b && smooth[a] <= threshold) a++
        while (b > a && smooth[b] <= threshold) b--
        return beats.subList(a, b + 1)
    }
}

/** An in-place radix-2 complex FFT of a fixed size. */
internal class Fft(private val n: Int) {
    private val levels = 31 - Integer.numberOfLeadingZeros(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n > 1 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + halfSize) {
                    val l = j + halfSize
                    val tr = re[l] * cosT[k] + im[l] * sinT[k]
                    val ti = -re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
