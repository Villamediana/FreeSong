package com.villamediana.sound.playback.pitch

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A song played in another key: every note moved by the same interval, nothing faster or slower.
 *
 * Two steps, the classic way for whole mixes:
 *  1. The audio is stretched in time without changing its notes (a phase vocoder: the sound is
 *     taken apart in overlapping windows, each one's partials found, and the windows put back
 *     further apart or closer together with every partial's phase turned so it keeps ringing at
 *     its own frequency). All of a partial's bins turn together ("identity phase locking",
 *     Laroche & Dolson 1999), which keeps the sound from going hollow; both channels of a partial
 *     turn by the same angle, so the stereo picture stays where it was. A hit (a drum, a pluck)
 *     is not a ringing partial, and turned like one it smears over the whole window: where the
 *     energy of a band jumps, the bins the hit took over are put back in step with the hit itself
 *     (as it was heard, moved as a whole) for as long as it is inside the windows.
 *  2. The stretched audio is read back faster or slower (band-limited resampling), which brings
 *     the length back to the original and moves every frequency by exactly the ratio.
 *
 * Sample n that comes out is sample n that went in: only its pitch changed. Making it takes some
 * audio from after it (most of a window), so the output runs behind the input by that much.
 */

/** In-place complex FFT of a power-of-two size (tables worked out once). */
internal class FloatFft(val size: Int) {
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val reversed = IntArray(size).also { rev ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im)

    /** The inverse, scaled so that inverse(forward(x)) is x. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(im, re)
        val scale = 1f / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: FloatArray, im: FloatArray) {
        val n = size
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (a in start until start + halfLen) {
                    val b = a + halfLen
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}

/**
 * Shifts the pitch of [channels] of audio by [ratio] as it comes, keeping its length: frame n read
 * is frame n written, in another key. Interleaved floats in and out.
 *
 * [write] takes audio in, [read] gives what can already be made (it runs [lookahead] frames or so
 * behind), [finish] says no more is coming so the rest can be read. [ratio] may change at any time:
 * the audio already written keeps the ratio it was written under, to the frame.
 */
internal class PitchShifter(private val channels: Int, sampleRate: Int) {
    private val size = if (sampleRate > 50_000) 8192 else 4096
    private val half = size / 2
    /** How far apart the windows are put back together. */
    private val hop = size / 4
    private val fft = FloatFft(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }

    /** The frequency ratio for the audio written from now on (2^(semitones/12)). */
    var ratio: Double = 1.0
        set(value) {
            field = value.coerceIn(MIN_RATIO, MAX_RATIO)
        }

    /** About how many frames the output runs behind the input (at the lowest ratio, the most). */
    val lookahead: Int get() = (half * (1 + 1 / MIN_RATIO)).toInt() + 2 * TAPS

    // ---- input: per channel, the frames [inBase, inBase + inLen); before frame 0 there is silence
    private var inBuf = Array(channels) { FloatArray(size * 4) }
    private var inBase = -half.toLong()
    private var inLen = half
    /** Frames written (the zeros [finish] adds don't count). */
    private var written = 0L
    private var finished = false

    // ---- the stretched audio, windows summed: per channel, the frames [stBase, stBase + stLen)
    private var stBuf = Array(channels) { FloatArray(size * 4) }
    private var stBase = -half.toLong()
    private var stLen = 0
    /** Stretched frames below this one have every window they will get. */
    private var stDone = -half.toLong()

    // ---- windows: the next one to take apart, and where the ones already done sit
    private var frame = 0L
    private var center = 0.0
    private var prevStart = 0L
    /** Centre (in input frames) and ratio of the windows [mapBase, mapBase + mapLen). */
    private var mapCenter = DoubleArray(64)
    private var mapRatio = DoubleArray(64)
    private var mapBase = 0L
    private var mapLen = 0

    // ---- the vocoder's memory
    private val re = Array(channels) { FloatArray(size) }
    private val im = Array(channels) { FloatArray(size) }
    private val prevRe = Array(channels) { FloatArray(half + 1) }
    private val prevIm = Array(channels) { FloatArray(half + 1) }
    private var hasPrev = false
    private val power = FloatArray(half + 1)
    private var turn = FloatArray(half + 1)
    private var nextTurn = FloatArray(half + 1)
    private val peaks = IntArray(half + 1)

    // ---- attacks: per band of the spectrum, a hit going through the windows
    /** First bin of each band (and the end of the last): an octave each from 200 Hz up. */
    private val bandEdges: IntArray = run {
        val edges = ArrayList<Int>()
        edges += 1
        var hz = 200.0
        while (hz < sampleRate / 2.0) {
            val bin = (hz * size / sampleRate).toInt()
            if (bin > edges.last() && bin < half) edges += bin
            hz *= 2
        }
        edges += half
        edges.toIntArray()
    }
    private val bandEnergy = FloatArray(bandEdges.size - 1)
    /** Windows since the band's hit was first seen ([CALM]: none going through). */
    private val bandSince = IntArray(bandEdges.size - 1) { CALM }
    /** How far the hit has drifted from where its first window put it, in frames. */
    private val bandDrift = DoubleArray(bandEdges.size - 1)
    private val prevPower = FloatArray(half + 1)
    /** Each bin's power right before its band's hit. */
    private val before = FloatArray(half + 1)

    /** Frames read so far: the next one out is frame [produced] of what was written. */
    private var produced = 0L

    /** Forgets everything: what is written next is frame 0 again. */
    fun reset() {
        inBase = -half.toLong()
        inLen = half
        for (c in 0 until channels) inBuf[c].fill(0f, 0, half)
        written = 0
        finished = false
        stBase = -half.toLong()
        stLen = 0
        stDone = -half.toLong()
        for (c in 0 until channels) stBuf[c].fill(0f)
        frame = 0
        center = 0.0
        prevStart = 0
        mapBase = 0
        mapLen = 0
        hasPrev = false
        turn.fill(0f)
        bandSince.fill(CALM)
        produced = 0
    }

    /** Takes [frames] frames of [input] (interleaved, from frame [offset]). */
    fun write(input: FloatArray, offset: Int, frames: Int) {
        if (frames <= 0 || finished) return
        append(frames) { c, i -> input[(offset + i) * channels + c] }
        written += frames
        takeApart()
    }

    /** No more audio is coming: everything written can now be read. */
    fun finish() {
        if (finished) return
        finished = true
        if (written == 0L) return
        // Silence stands for what would have come, until the last real frame can be made.
        var guard = 0
        while (!canMake(written - 1) && guard++ < 64) {
            append(size) { _, _ -> 0f }
            takeApart()
        }
    }

    /** Fills [output] (interleaved, from frame [offset]) with up to [maxFrames]; returns how many. */
    fun read(output: FloatArray, offset: Int, maxFrames: Int): Int {
        var made = 0
        while (made < maxFrames && produced < written) {
            val w = windowOf(produced)
            if (w < 0 || !reaches(produced, w)) break
            make(produced, w, output, (offset + made) * channels)
            produced++
            made++
        }
        if (made > 0) forget()
        return made
    }

    // ------------------------------------------------------------------ input

    private inline fun append(frames: Int, sample: (channel: Int, index: Int) -> Float) {
        // Nothing before the next window's start is needed again.
        val keepFrom = (windowStart() - inBase).coerceIn(0L, inLen.toLong()).toInt()
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

    private fun windowStart(): Long = center.roundToLong() - half

    // ------------------------------------------------------------------ the vocoder

    private fun takeApart() {
        while (windowStart() + size <= inBase + inLen) window()
    }

    /** One window: taken apart, its partials turned, put back at its place in the stretched audio. */
    private fun window() {
        val start = windowStart()
        val at = (start - inBase).toInt()
        val r = ratio
        var energy = 0f
        for (c in 0 until channels) {
            val src = inBuf[c]
            val fr = re[c]
            val fi = im[c]
            for (i in 0 until size) {
                val v = src[at + i] * window[i]
                fr[i] = v
                energy += v * v
            }
            fi.fill(0f)
        }
        val silent = energy < SILENCE
        if (!silent) {
            for (c in 0 until channels) fft.forward(re[c], im[c])
            if (hasPrev) turnPartials((start - prevStart).toInt()) else startOver()
            for (c in 0 until channels) {
                System.arraycopy(re[c], 0, prevRe[c], 0, half + 1)
                System.arraycopy(im[c], 0, prevIm[c], 0, half + 1)
            }
            hasPrev = true
            applyTurn()
            for (c in 0 until channels) fft.inverse(re[c], im[c])
        } else {
            // Nothing sounding: nothing to keep in phase when sound comes back.
            hasPrev = false
        }

        // Its place in the stretched audio: centred `hop` after the window before.
        val place = frame * hop - half
        val to = (place - stBase).toInt()
        ensureStretched(to + size)
        if (!silent) {
            for (c in 0 until channels) {
                val out = stBuf[c]
                val fr = re[c]
                for (i in 0 until size) out[to + i] += fr[i] * window[i] * OVERLAP_GAIN
            }
        }
        stLen = maxOf(stLen, to + size)
        stDone = (frame + 1) * hop - half

        if (mapLen == mapCenter.size) {
            mapCenter = mapCenter.copyOf(mapLen * 2)
            mapRatio = mapRatio.copyOf(mapLen * 2)
        }
        mapCenter[mapLen] = center
        mapRatio[mapLen] = r
        mapLen++

        prevStart = start
        frame++
        center += hop / r
    }

    /** The first window with sound in it: played as it is, and remembered for the next one. */
    private fun startOver() {
        turn.fill(0f)
        bandSince.fill(CALM)
        measure()
        for (band in bandEnergy.indices) bandEnergy[band] = energyOf(band)
        System.arraycopy(power, 0, prevPower, 0, half + 1)
    }

    private fun measure() {
        for (b in 0..half) {
            var p = 0f
            for (c in 0 until channels) p += re[c][b] * re[c][b] + im[c][b] * im[c][b]
            power[b] = p
        }
    }

    private fun energyOf(band: Int): Float {
        var e = 0f
        for (b in bandEdges[band] until bandEdges[band + 1]) e += power[b]
        return e
    }

    /**
     * How far each bin's phase has to turn in this window: every peak of the spectrum (a partial)
     * goes on from the turn its bin had, by what keeps it at its own frequency now that the windows
     * sit [hop] apart instead of [taken]; the bins around a peak turn with it.
     */
    private fun turnPartials(taken: Int) {
        measure()
        var count = 0
        for (b in 1 until half) {
            val p = power[b]
            if (p > power[b - 1] && p >= power[b + 1] && (b < 2 || p > power[b - 2]) && (b + 2 > half || p >= power[b + 2])) peaks[count++] = b
        }
        if (count == 0) {
            System.arraycopy(turn, 0, nextTurn, 0, half + 1)
        }
        val stretch = hop.toDouble() / taken - 1.0
        var lo = 0
        for (j in 0 until count) {
            val peak = peaks[j]
            // A peak's bins end at the quietest bin before the next peak.
            val hi = if (j == count - 1) half else {
                val next = peaks[j + 1]
                var quietest = peak + 1
                for (b in peak + 2 until next) if (power[b] < power[quietest]) quietest = b
                quietest - 1
            }
            // The louder channel says how the partial moved since the window before.
            var ref = 0
            if (channels > 1) {
                var best = -1f
                for (c in 0 until channels) {
                    val p = re[c][peak] * re[c][peak] + im[c][peak] * im[c][peak]
                    if (p > best) {
                        best = p
                        ref = c
                    }
                }
            }
            val ar = re[ref][peak]
            val ai = im[ref][peak]
            val br = prevRe[ref][peak]
            val bi = prevIm[ref][peak]
            val omega = 2.0 * PI * peak / size
            var deviation = atan2((ai * br - ar * bi).toDouble(), (ar * br + ai * bi).toDouble()) - omega * taken
            deviation -= 2.0 * PI * Math.rint(deviation / (2.0 * PI))
            var angle = turn[peak] + (hop - taken) * omega + stretch * deviation
            angle -= 2.0 * PI * Math.rint(angle / (2.0 * PI))
            val a = angle.toFloat()
            for (b in lo..hi) nextTurn[b] = a
            lo = hi + 1
        }
        hits(taken)
        val t = turn
        turn = nextTurn
        nextTurn = t
        System.arraycopy(power, 0, prevPower, 0, half + 1)
    }

    /**
     * A hit is heard where a band's energy jumps from one window to the next. From its first window
     * (played with the phases it came with: the hit as it is) until it has gone through the windows,
     * the bins it took over — louder than they were before it — are not turned as partials but moved
     * as a whole by how far the windows have drifted since, so every window puts the hit at the same
     * moment. Bins still held by a note that was already sounding go on as partials.
     */
    private fun hits(taken: Int) {
        // A hit is seen by every window that covers it: this many, and one to spare.
        val crossing = (size + taken - 1) / taken + 1
        for (band in bandEnergy.indices) {
            val from = bandEdges[band]
            val to = bandEdges[band + 1]
            val energy = energyOf(band)
            val since = bandSince[band]
            val jumped = energy > ATTACK * bandEnergy[band] && energy > (to - from) * SILENCE
            bandEnergy[band] = energy
            when {
                // A new hit (the same one keeps growing for a couple of windows as it comes in).
                jumped && (since == CALM || since >= SAME_HIT) -> {
                    System.arraycopy(prevPower, from, before, from, to - from)
                    bandSince[band] = 0
                    // Only a hit in the middle of its window is already at its moment: one seen
                    // off-centre is that much early or late once the windows are spread.
                    bandDrift[band] = (1.0 - hop.toDouble() / taken) * (whereInWindow(from, to) - half)
                }
                since == CALM -> continue
                since >= crossing -> {
                    bandSince[band] = CALM
                    continue
                }
                else -> {
                    bandSince[band] = since + 1
                    bandDrift[band] += (hop - taken).toDouble()
                }
            }
            val drift = bandDrift[band]
            for (b in from until to) {
                if (power[b] > TAKEN_OVER * before[b]) {
                    var angle = 2.0 * PI * b / size * drift
                    angle -= 2.0 * PI * Math.rint(angle / (2.0 * PI))
                    nextTurn[b] = angle.toFloat()
                }
            }
        }
    }

    /**
     * Where in the window (0 until [size]) the bins a hit took over have their energy: a click at
     * frame u of the window turns each bin's phase by u more than the bin before it.
     */
    private fun whereInWindow(from: Int, to: Int): Double {
        var sr = 0.0
        var si = 0.0
        for (b in from until to - 1) {
            if (power[b] <= TAKEN_OVER * before[b] || power[b + 1] <= TAKEN_OVER * before[b + 1]) continue
            for (c in 0 until channels) {
                val ar = re[c][b + 1]
                val ai = im[c][b + 1]
                val br = re[c][b]
                val bi = im[c][b]
                sr += ar * br + ai * bi
                si += ai * br - ar * bi
            }
        }
        if (sr == 0.0 && si == 0.0) return half.toDouble()
        val u = -atan2(si, sr) * size / (2.0 * PI)
        return if (u < 0) u + size else u
    }

    private fun applyTurn() {
        var last = Float.NaN
        var cs = 1f
        var sn = 0f
        // The first and the last bin have no mirror image: they stay as they are.
        for (b in 1 until half) {
            val angle = turn[b]
            if (angle != last) {
                last = angle
                cs = cos(angle)
                sn = sin(angle)
            }
            for (c in 0 until channels) {
                val fr = re[c]
                val fi = im[c]
                val yr = fr[b] * cs - fi[b] * sn
                val yi = fr[b] * sn + fi[b] * cs
                fr[b] = yr
                fi[b] = yi
                fr[size - b] = yr
                fi[size - b] = -yi
            }
        }
    }

    // ------------------------------------------------------------------ the stretched audio

    private fun ensureStretched(length: Int) {
        if (length <= stBuf[0].size) return
        val capacity = maxOf(length, stBuf[0].size * 2)
        for (c in 0 until channels) stBuf[c] = stBuf[c].copyOf(capacity)
    }

    /** Drops the windows and the stretched audio the reader is past. */
    private fun forget() {
        var past = 0
        while (past + 1 < mapLen && produced >= mapCenter[past + 1]) past++
        if (past > 0) {
            System.arraycopy(mapCenter, past, mapCenter, 0, mapLen - past)
            System.arraycopy(mapRatio, past, mapRatio, 0, mapLen - past)
            mapLen -= past
            mapBase += past
        }
        if (mapLen == 0) return
        val from = floor(position(produced.coerceAtLeast(0), 0)).toLong() - MAX_REACH
        val drop = (from - stBase).coerceAtMost(stLen.toLong()).toInt()
        if (drop < size * 2) return
        for (c in 0 until channels) {
            val buf = stBuf[c]
            System.arraycopy(buf, drop, buf, 0, stLen - drop)
            buf.fill(0f, stLen - drop, stLen)
        }
        stBase += drop
        stLen -= drop
    }

    // ------------------------------------------------------------------ reading it back

    /**
     * Which of the windows kept ([mapBase] + the answer) input frame [n] falls after — its stretch
     * is the one between that window's centre and the next one's. -1: not taken apart yet.
     */
    private fun windowOf(n: Long): Int {
        if (mapLen == 0 || n < 0) return -1
        var w = 0
        while (w + 1 < mapLen && n >= mapCenter[w + 1]) w++
        // After the last window done: only up to where the next one will be centred.
        if (w == mapLen - 1 && n >= mapCenter[w] + hop / mapRatio[w]) return -1
        return w
    }

    /** Where input frame [n], in window [w]'s stretch, sits in the stretched audio. */
    private fun position(n: Long, w: Int): Double = (mapBase + w) * hop.toDouble() + (n - mapCenter[w]) * mapRatio[w]

    /** Whether the stretched audio around frame [n] already has every window it will get. */
    private fun reaches(n: Long, w: Int): Boolean = floor(position(n, w)).toLong() + reach(mapRatio[w]) + 1 <= stDone

    private fun canMake(n: Long): Boolean {
        val w = windowOf(n)
        return w >= 0 && reaches(n, w)
    }

    /** How many stretched frames to each side one output frame is made from. */
    private fun reach(r: Double): Int = ceil(TAPS * maxOf(1.0, r)).toInt()

    /** Output frame [n]: the stretched audio read at its place, through a windowed sinc. */
    private fun make(n: Long, w: Int, output: FloatArray, at: Int) {
        val r = mapRatio[w]
        val where = position(n, w)
        val whole = floor(where).toLong()
        // Read faster than written (pitch up), what would fold back is filtered out by a wider kernel.
        val cut = minOf(1.0, 1.0 / r)
        val reach = reach(r)
        val first = whole - reach + 1
        val base = (first - stBase).toInt()
        var total = 0f
        if (channels == 2) {
            val left = stBuf[0]
            val right = stBuf[1]
            var l = 0f
            var rr = 0f
            for (i in 0 until 2 * reach) {
                val w = Kernel.at(((first + i) - where) * cut)
                val index = base + i
                if (index >= 0 && index < stLen) {
                    l += left[index] * w
                    rr += right[index] * w
                }
                total += w
            }
            output[at] = l / total
            output[at + 1] = rr / total
        } else {
            for (c in 0 until channels) {
                val buf = stBuf[c]
                var sum = 0f
                total = 0f
                for (i in 0 until 2 * reach) {
                    val w = Kernel.at(((first + i) - where) * cut)
                    val index = base + i
                    if (index >= 0 && index < stLen) sum += buf[index] * w
                    total += w
                }
                output[at + c] = sum / total
            }
        }
    }

    /** A Kaiser-windowed sinc, [TAPS] zero crossings to each side, read from a table. */
    private object Kernel {
        private const val STEPS = 512
        private val table = FloatArray(TAPS * STEPS + 2).also { t ->
            val i0Beta = bessel(BETA)
            for (i in t.indices) {
                val x = i.toDouble() / STEPS
                if (x >= TAPS) {
                    t[i] = 0f
                    continue
                }
                val sinc = if (i == 0) 1.0 else sin(PI * x) / (PI * x)
                val w = bessel(BETA * sqrt(1.0 - (x / TAPS) * (x / TAPS))) / i0Beta
                t[i] = (sinc * w).toFloat()
            }
        }

        fun at(x: Double): Float {
            val a = if (x < 0) -x else x
            if (a >= TAPS) return 0f
            val scaled = a * STEPS
            val i = scaled.toInt()
            val f = (scaled - i).toFloat()
            return table[i] + (table[i + 1] - table[i]) * f
        }

        private fun bessel(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 60) {
                term *= (x / (2.0 * k)) * (x / (2.0 * k))
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }
    }

    companion object {
        /** Eleven semitones each way, the furthest a song is transposed. */
        val MAX_RATIO = Math.pow(2.0, 11.0 / 12.0)
        val MIN_RATIO = 1.0 / MAX_RATIO

        fun ratioOf(semitones: Int): Double = Math.pow(2.0, semitones / 12.0)

        /**
         * How many frames of the audio just played start a shifter mid-song well: enough for every
         * window that reaches the first frame given out to be whole.
         */
        fun warmUp(sampleRate: Int): Int = 2 * if (sampleRate > 50_000) 8192 else 4096

        /** Zero crossings of the reading kernel to each side. */
        private const val TAPS = 16
        private const val BETA = 9.0
        private val MAX_REACH = ceil(TAPS * MAX_RATIO).toLong() + 2

        /** Hann windows squared, a quarter apart, add up to 1.5. */
        private const val OVERLAP_GAIN = 1f / 1.5f

        /** A window quieter than this (about −110 dB) is silence. */
        private const val SILENCE = 1e-8f

        /** A band this many times louder than in the window before: a hit. */
        private const val ATTACK = 2f

        /** A bin this many times louder than before the hit is the hit's. */
        private const val TAKEN_OVER = 4f

        /** A jump this many windows after a hit began is another hit, not the same one coming in. */
        private const val SAME_HIT = 3

        private const val CALM = Int.MAX_VALUE
    }
}
