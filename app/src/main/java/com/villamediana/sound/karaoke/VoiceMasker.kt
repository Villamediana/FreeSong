package com.villamediana.sound.karaoke

import com.villamediana.sound.playback.pitch.FloatFft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Turns the voice of a song down as it plays: the audio is taken apart in overlapping windows, each
 * band of each window turned down by what the song's masks say of that moment ([MaskSource]), and
 * the windows put back together. Where the masks say nothing (not worked out yet) or the voice is
 * wanted ([wanted] false), the audio comes out as it went in, to the sample.
 *
 * Frame n read is frame n written. Making it takes most of a window from after it, so the output
 * runs about [lookahead] frames behind the input. Interleaved floats in and out, like the
 * [com.villamediana.sound.playback.pitch.PitchShifter].
 */
internal class VoiceMasker(private val channels: Int, private val sampleRate: Int) {
    private val size = SIZE
    private val hop = size / 4
    private val half = size / 2
    private val fft = FloatFft(size)
    /** The square root of a Hann window, going in and coming out: the windows add up to the audio itself. */
    private val window = FloatArray(size) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }
    private val binHz = sampleRate.toDouble() / size

    /** The song's masks; null: none. */
    var masks: MaskSource? = null

    /** The voice is to be turned down (it goes and comes back over a few windows). */
    var wanted: Boolean = true

    val lookahead: Int get() = size

    /** Where in the song (frames at [sampleRate]) the first frame written after [reset] is. */
    private var origin = 0L

    // Input: per channel, frames [inBase, inBase + inLen) counted from the first one written;
    // before it, silence (or what [reset] was told came before).
    private var inBuf = Array(channels) { FloatArray(size * 4) }
    private var inBase = -(size - hop).toLong()
    private var inLen = size - hop
    private var written = 0L
    private var finished = false

    // Output: per channel, the windows summed, frames [outBase, outBase + outLen).
    private var outBuf = Array(channels) { FloatArray(size * 4) }
    private var outBase = -(size - hop).toLong()
    private var outLen = 0
    /** Frames below this one have every window they will get. */
    private var outDone = -(size - hop).toLong()

    private var frame = 0L
    private var produced = 0L
    /** How far the voice is down right now, 0..1, on its way to where [wanted] and the masks put it. */
    private var amount = 0f
    /** Windows in a row that went back untouched. */
    private var plain = 0

    /** Nothing still on its way out has the voice turned down: from here the audio is the audio written. */
    val settled: Boolean get() = plain >= 4

    private val re = Array(channels) { FloatArray(size) }
    private val im = Array(channels) { FloatArray(size) }
    private val gains = Array(2) { FloatArray(half + 1) }

    /** Forgets everything: what is written next is the song from [songFrame] on. */
    fun reset(songFrame: Long, startDown: Boolean = false) {
        origin = songFrame
        inBase = -(size - hop).toLong()
        inLen = size - hop
        for (c in 0 until channels) inBuf[c].fill(0f, 0, inLen)
        written = 0
        finished = false
        outBase = -(size - hop).toLong()
        outLen = 0
        outDone = outBase
        for (c in 0 until channels) outBuf[c].fill(0f)
        frame = 0
        produced = 0
        amount = if (startDown) 1f else 0f
        plain = 0
    }

    fun write(input: FloatArray, offset: Int, frames: Int) {
        if (frames <= 0 || finished) return
        append(frames) { c, i -> input[(offset + i) * channels + c] }
        written += frames
        windows()
    }

    /** No more audio is coming: everything written can now be read. */
    fun finish() {
        if (finished) return
        finished = true
        if (written == 0L) return
        var guard = 0
        while (outDone < written && guard++ < 8) {
            append(hop) { _, _ -> 0f }
            windows()
        }
    }

    fun read(output: FloatArray, offset: Int, maxFrames: Int): Int {
        val n = minOf(maxFrames.toLong(), minOf(outDone, written) - produced).toInt()
        if (n <= 0) return 0
        val from = (produced - outBase).toInt()
        for (c in 0 until channels) {
            val buf = outBuf[c]
            for (i in 0 until n) output[(offset + i) * channels + c] = buf[from + i]
        }
        produced += n
        // What was read is dropped once there is a good deal of it.
        val drop = (produced - outBase).toInt()
        if (drop >= size * 2) {
            for (c in 0 until channels) {
                val buf = outBuf[c]
                System.arraycopy(buf, drop, buf, 0, outLen - drop)
                buf.fill(0f, outLen - drop, outLen)
            }
            outBase += drop
            outLen -= drop
        }
        return n
    }

    private inline fun append(frames: Int, sample: (channel: Int, index: Int) -> Float) {
        val keepFrom = (frame * hop - (size - hop) - inBase).coerceIn(0L, inLen.toLong()).toInt()
        if (inLen + frames > inBuf[0].size) {
            val kept = inLen - keepFrom
            val capacity = maxOf(inBuf[0].size, (kept + frames) * 2)
            for (c in 0 until channels) {
                val from = inBuf[c]
                val to = if (capacity > from.size) FloatArray(capacity) else from
                System.arraycopy(from, keepFrom, to, 0, kept)
                inBuf[c] = to
            }
            inBase += keepFrom
            inLen = kept
        }
        for (c in 0 until channels) {
            val buf = inBuf[c]
            for (i in 0 until frames) buf[inLen + i] = sample(c, i)
        }
        inLen += frames
    }

    private fun windows() {
        while (frame * hop + hop <= inBase + inLen) window()
    }

    /** One window: its bands turned down by the masks of its moment, and added back in. */
    private fun window() {
        val start = frame * hop - (size - hop)
        val at = (start - inBase).toInt()
        val to = (start - outBase).toInt()
        if (to + size > outBuf[0].size) {
            val capacity = maxOf(to + size, outBuf[0].size * 2)
            for (c in 0 until channels) outBuf[c] = outBuf[c].copyOf(capacity)
        }
        // Where the voice is at this window's centre, when that is known.
        val known = masks?.at((origin + start + half).coerceAtLeast(0).toDouble() / sampleRate, binHz, gains[0], gains[1]) == true
        val goal = if (known && wanted) 1f else 0f
        if (amount < goal) amount = minOf(goal, amount + STEP) else if (amount > goal) amount = maxOf(goal, amount - STEP)
        // No masks for this moment: the audio as it is (the windows around it smooth the change).
        if (!known) amount = 0f

        plain = if (amount <= 0f) plain + 1 else 0
        if (amount <= 0f) {
            // Nothing to turn down: the window goes back as it came (no transform needed).
            for (c in 0 until channels) {
                val src = inBuf[c]
                val out = outBuf[c]
                for (i in 0 until size) out[to + i] += src[at + i] * window[i] * window[i] * OVERLAP_GAIN
            }
        } else {
            val a = amount
            for (c in 0 until channels) {
                val src = inBuf[c]
                val fr = re[c]
                val fi = im[c]
                for (i in 0 until size) fr[i] = src[at + i] * window[i]
                fi.fill(0f)
                fft.forward(fr, fi)
                // A mono song is turned down by both sides' masks together.
                val g = gains[if (channels == 1) 0 else c]
                val other = gains[if (channels == 1) 1 else c]
                for (b in 0..half) {
                    val mask = if (channels == 1) (g[b] + other[b]) * 0.5f else g[b]
                    val k = 1f + a * (mask - 1f)
                    fr[b] *= k
                    fi[b] *= k
                    if (b in 1 until half) {
                        fr[size - b] = fr[b]
                        fi[size - b] = -fi[b]
                    }
                }
                fft.inverse(fr, fi)
                val out = outBuf[c]
                for (i in 0 until size) out[to + i] += fr[i] * window[i] * OVERLAP_GAIN
            }
        }
        outLen = maxOf(outLen, to + size)
        outDone = start + hop
        frame++
    }

    companion object {
        const val SIZE = 4_096

        /** Hann windows a quarter apart add up to 2. */
        private const val OVERLAP_GAIN = 0.5f

        /** The voice goes down (or comes back) over this many windows: about a fifth of a second. */
        private const val STEP = 1f / 8f
    }
}
