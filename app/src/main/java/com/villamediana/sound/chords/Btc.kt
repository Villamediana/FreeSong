package com.villamediana.sound.chords

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * BTC, the Bi-directional Transformer for Chord recognition (Park et al., ISMIR 2019; MIT,
 * github.com/jayg996/BTC-ISMIR19), large vocabulary: from the [Cqt] spectrum, frame by frame, the
 * chances of 170 chords — 12 roots × 14 kinds (min, maj, dim, aug, min6, maj6, min7, minmaj7, maj7,
 * 7, dim7, hdim7, sus2, sus4), "unknown" and "no chord". Written out in plain Kotlin from the model's
 * PyTorch code (8 layers, each a forward and a backward masked self-attention block over 108 frames),
 * on its weights in float16 (assets/chords/btc_voca.bin, tools/chords/bench/export_btc.py).
 *
 * On the benchmark (53 Billboard songs no part of its training): 77% of the time on the right root
 * and major/minor, 61% with the seventh — the app's earlier notes-and-rules hearing was 64% and 47%.
 */
internal class Btc(bytes: ByteBuffer) {
    private val mean: Float
    private val std: Float
    private val embedding: FloatArray // [D][144]
    private val layers: List<Layer>
    private val finalGamma: FloatArray
    private val finalBeta: FloatArray
    private val outW: FloatArray // [CLASSES][D]
    private val outB: FloatArray

    /** The convolutions are kept as three [out][in] matrices, one per tap (PyTorch keeps [out][in][tap]). */
    private class Block(
        val q: FloatArray, val k: FloatArray, val v: FloatArray, val o: FloatArray,
        val conv1: Array<FloatArray>, val conv1b: FloatArray, val conv2: Array<FloatArray>, val conv2b: FloatArray,
        val g1: FloatArray, val b1: FloatArray, val g2: FloatArray, val b2: FloatArray,
    )

    private class Layer(val forward: Block, val backward: Block, val linear: FloatArray, val linearB: FloatArray)

    init {
        val buf = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.US_ASCII) == "BTC1") { "not a BTC model" }
        mean = buf.float
        std = buf.float
        val half = buf.asShortBuffer()
        fun take(n: Int) = FloatArray(n) { halfToFloat(half.get()) }
        embedding = take(D * FEATURES)
        layers = List(LAYERS) {
            fun taps(w: FloatArray) = Array(3) { j -> FloatArray(D * D) { idx -> w[idx * 3 + j] } }
            fun block() = Block(
                take(D * D), take(D * D), take(D * D), take(D * D),
                taps(take(D * D * 3)), take(D), taps(take(D * D * 3)), take(D),
                take(D), take(D), take(D), take(D),
            )
            val f = block()
            val b = block()
            Layer(f, b, take(D * 2 * D), take(D))
        }
        finalGamma = take(D)
        finalBeta = take(D)
        outW = take(CLASSES * D)
        outB = take(CLASSES)
        check(!half.hasRemaining()) { "BTC weights: ${half.remaining()} values left over" }
    }

    /**
     * Log-probabilities of the [CLASSES] chords for each frame of [features] (log |CQT| rows from
     * [Cqt.features]): standardised, cut in 108-frame windows (the last padded with zeros), as BTC's
     * own test does.
     */
    fun logProbs(
        features: Array<FloatArray>,
        isActive: () -> Boolean = { true },
        progress: (Float) -> Unit = {},
        threads: Int = DEFAULT_THREADS,
    ): Array<FloatArray> {
        val n = features.size
        val windows = (n + T - 1) / T
        val out = arrayOfNulls<FloatArray>(n)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        // The windows are independent: a few threads take them in turn.
        val work = Runnable {
            while (isActive()) {
                val w = next.getAndIncrement()
                if (w >= windows) break
                val at = w * T
                val x = FloatArray(T * FEATURES)
                for (t in 0 until T) {
                    val row = features.getOrNull(at + t) ?: continue
                    for (f in 0 until FEATURES) x[t * FEATURES + f] = (row[f] - mean) / std
                }
                val probs = window(x)
                for (t in 0 until minOf(T, n - at)) out[at + t] = probs[t]
                progress(done.incrementAndGet().toFloat() / windows)
            }
        }
        val helpers = List(minOf(threads, windows) - 1) { i ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                work.run()
            }, "chords-btc-$i").apply { start() }
        }
        work.run()
        helpers.forEach { it.join() }
        if (!isActive()) throw kotlinx.coroutines.CancellationException()
        @Suppress("UNCHECKED_CAST")
        return out as Array<FloatArray>
    }

    private fun window(input: FloatArray): Array<FloatArray> {
        var x = linear(input, T, FEATURES, embedding, D, null)
        for (t in 0 until T) for (c in 0 until D) x[t * D + c] += TIMING[t * D + c]
        for (layer in layers) {
            val f = block(x, layer.forward, backward = false)
            val b = block(x, layer.backward, backward = true)
            val both = FloatArray(T * 2 * D)
            for (t in 0 until T) {
                System.arraycopy(f, t * D, both, t * 2 * D, D)
                System.arraycopy(b, t * D, both, t * 2 * D + D, D)
            }
            x = linear(both, T, 2 * D, layer.linear, D, layer.linearB)
        }
        val y = layerNorm(x, finalGamma, finalBeta)
        val logits = linear(y, T, D, outW, CLASSES, outB)
        return Array(T) { t ->
            var max = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) max = maxOf(max, logits[t * CLASSES + c])
            var sum = 0.0
            for (c in 0 until CLASSES) sum += exp((logits[t * CLASSES + c] - max).toDouble())
            val lse = max + ln(sum).toFloat()
            FloatArray(CLASSES) { c -> logits[t * CLASSES + c] - lse }
        }
    }

    /** Layer norm, masked multi-head self-attention, residual; layer norm, two convolutions, residual. */
    private fun block(x: FloatArray, w: Block, backward: Boolean): FloatArray {
        val xn = layerNorm(x, w.g1, w.b1)
        val q = linear(xn, T, D, w.q, D, null)
        val k = linear(xn, T, D, w.k, D, null)
        val v = linear(xn, T, D, w.v, D, null)
        val ctx = FloatArray(T * D)
        val scale = 1f / sqrt(HEAD.toFloat())
        val logits = FloatArray(T)
        for (h in 0 until HEADS) {
            val off = h * HEAD
            for (i in 0 until T) {
                // Forward block: frame i sees frames up to i; backward block: from i on.
                val from = if (backward) i else 0
                val to = if (backward) T - 1 else i
                var max = Float.NEGATIVE_INFINITY
                for (j in from..to) {
                    var s = 0f
                    val qi = i * D + off
                    val kj = j * D + off
                    for (c in 0 until HEAD) s += q[qi + c] * k[kj + c]
                    s *= scale
                    logits[j] = s
                    if (s > max) max = s
                }
                var sum = 0f
                for (j in from..to) {
                    val e = exp(logits[j] - max)
                    logits[j] = e
                    sum += e
                }
                val ci = i * D + off
                for (j in from..to) {
                    val a = logits[j] / sum
                    val vj = j * D + off
                    for (c in 0 until HEAD) ctx[ci + c] += a * v[vj + c]
                }
            }
        }
        val y = linear(ctx, T, D, w.o, D, null)
        for (i in y.indices) y[i] += x[i]
        val yn = layerNorm(y, w.g2, w.b2)
        val h1 = conv(yn, w.conv1, w.conv1b)
        val h2 = conv(h1, w.conv2, w.conv2b)
        for (i in h2.indices) h2[i] += y[i]
        return h2
    }

    /** Conv1d over time, kernel 3, one frame of zeros each side, then ReLU (BTC applies it after both). */
    private fun conv(x: FloatArray, w: Array<FloatArray>, b: FloatArray): FloatArray {
        val padded = FloatArray((T + 2) * D)
        System.arraycopy(x, 0, padded, D, T * D)
        val out = FloatArray(T * D)
        for (t in 0 until T) System.arraycopy(b, 0, out, t * D, D)
        // Tap j reads frame t + j − 1, which is row t + j of the padded input.
        for (j in 0 until 3) accumulate(padded, j, w[j], out)
        for (i in out.indices) if (out[i] < 0f) out[i] = 0f
        return out
    }

    /** gamma · (x − mean) / (std + 1e-6) + beta per frame, std with n − 1 as PyTorch's x.std(). */
    private fun layerNorm(x: FloatArray, gamma: FloatArray, beta: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        for (t in 0 until T) {
            val o = t * D
            var m = 0f
            for (c in 0 until D) m += x[o + c]
            m /= D
            var v = 0f
            for (c in 0 until D) { val d = x[o + c] - m; v += d * d }
            val sd = sqrt(v / (D - 1))
            for (c in 0 until D) out[o + c] = gamma[c] * (x[o + c] - m) / (sd + 1e-6f) + beta[c]
        }
        return out
    }

    companion object {
        const val CLASSES = 170
        const val NO_CHORD = 169
        const val UNKNOWN = 168
        private const val T = Cqt.FRAMES_PER_PIECE
        private const val FEATURES = Cqt.BINS
        private const val D = 128
        private const val HEADS = 4
        private const val HEAD = D / HEADS
        private const val LAYERS = 8

        /** Threads for the windows: a few, at background priority, while the music plays. */
        val DEFAULT_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** The chord of class [index] (null for "no chord" and "unknown"). */
        fun chord(index: Int): Chord? {
            if (index >= UNKNOWN) return null
            val quality = when (index % 14) {
                0 -> ChordQuality.MINOR
                1 -> ChordQuality.MAJOR
                2 -> ChordQuality.DIM
                3 -> ChordQuality.AUG
                4 -> ChordQuality.MIN6
                5 -> ChordQuality.SIX
                6 -> ChordQuality.MIN7
                7 -> ChordQuality.MIN_MAJ7
                8 -> ChordQuality.MAJ7
                9 -> ChordQuality.DOM7
                10 -> ChordQuality.DIM7
                11 -> ChordQuality.HALF_DIM
                12 -> ChordQuality.SUS2
                else -> ChordQuality.SUS4
            }
            return Chord(index / 14, quality)
        }

        /** y[t][o] = Σ x[t][i]·W[o][i] (+ bias[o]): W row-major [outDim][inDim], as PyTorch keeps it. */
        private fun linear(x: FloatArray, rows: Int, inDim: Int, w: FloatArray, outDim: Int, bias: FloatArray?): FloatArray {
            val y = FloatArray(rows * outDim)
            if (bias != null) for (t in 0 until rows) System.arraycopy(bias, 0, y, t * outDim, outDim)
            dots(x, 0, rows, inDim, w, outDim, y)
            return y
        }

        /** out[t][o] += Σ x[t + rowOffset][i]·W[o][i], for the [T] frames, D × D. */
        private fun accumulate(x: FloatArray, rowOffset: Int, w: FloatArray, out: FloatArray) =
            dots(x, rowOffset, T, D, w, D, out)

        /**
         * The products, four frames at a time: each weight is read once for four sums, and the four
         * sums are independent (a plain dot product waits on itself every step). Several times faster.
         */
        private fun dots(x: FloatArray, rowOffset: Int, rows: Int, inDim: Int, w: FloatArray, outDim: Int, y: FloatArray) {
            var t = 0
            while (t + 4 <= rows) {
                val x0 = (t + rowOffset) * inDim
                val x1 = x0 + inDim
                val x2 = x1 + inDim
                val x3 = x2 + inDim
                for (o in 0 until outDim) {
                    val wo = o * inDim
                    var s0 = 0f
                    var s1 = 0f
                    var s2 = 0f
                    var s3 = 0f
                    for (i in 0 until inDim) {
                        val wi = w[wo + i]
                        s0 += x[x0 + i] * wi
                        s1 += x[x1 + i] * wi
                        s2 += x[x2 + i] * wi
                        s3 += x[x3 + i] * wi
                    }
                    y[t * outDim + o] += s0
                    y[(t + 1) * outDim + o] += s1
                    y[(t + 2) * outDim + o] += s2
                    y[(t + 3) * outDim + o] += s3
                }
                t += 4
            }
            while (t < rows) {
                val xo = (t + rowOffset) * inDim
                for (o in 0 until outDim) {
                    val wo = o * inDim
                    var s = 0f
                    for (i in 0 until inDim) s += x[xo + i] * w[wo + i]
                    y[t * outDim + o] += s
                }
                t++
            }
        }

        /** The sinusoid position signal added to the input (tensor2tensor's, 108 × 128). */
        private val TIMING: FloatArray = run {
            val half = D / 2
            val inc = ln(1.0e4) / (half - 1)
            FloatArray(T * D) { idx ->
                val p = idx / D
                val c = idx % D
                val inv = exp(-(c % half) * inc)
                (if (c < half) kotlin.math.sin(p * inv) else kotlin.math.cos(p * inv)).toFloat()
            }
        }

        private fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = bits ushr 15
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x3FF
            val f = when (exp) {
                0 -> if (mant == 0) 0f else mant / 1024f * 2f.pow(-14)
                0x1F -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1 + mant / 1024f) * 2f.pow(exp - 15)
            }
            return if (sign == 1) -f else f
        }
    }
}
