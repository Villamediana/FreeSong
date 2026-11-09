package com.villamediana.sound.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/*
 * What a song sounds like — its mood and its rhythm — measured from its own audio while it plays.
 * Nothing is looked up and nothing leaves the phone: the player hands the sound it is already
 * playing to [SoundAnalyzer], which keeps a handful of numbers per song ([SoundPrint]). The DNA
 * (MusicDna) then learns which sound belongs to which moment: calm in the morning, heavy at the
 * gym, whatever this listener really does.
 *
 * The numbers are rough on purpose (cheap: a few sums per sample, no transform). They are never
 * read as absolutes: [SoundMap] places each song among everything this listener heard, so only the
 * order matters ("brighter than most of what I hear"), not the scale.
 */

/**
 * A song's sound:
 * - [loud]: its level, in dBFS, over the part that sounds;
 * - [bright]: where its sound sits, from dark to bright (Hz);
 * - [drive]: how much happens in it: dB of attack per second;
 * - [pulse]: 0..1, how steady its beat is;
 * - [tempo]: that beat's pace, in BPM (0 when it has none);
 * - [seconds]: how much of it was measured.
 */
data class SoundPrint(
    val loud: Float,
    val bright: Float,
    val drive: Float,
    val pulse: Float,
    val tempo: Float,
    val seconds: Float,
) {
    /** Measured enough: no need to listen to it again. */
    val settled: Boolean get() = seconds >= SETTLED_SECONDS

    /** This and another measurement of the same song, each by how much of it it heard. */
    fun merge(other: SoundPrint): SoundPrint {
        val a = seconds / (seconds + other.seconds)
        val b = 1f - a
        // A beat is the same at double or half pace: the longer measurement says which.
        val pace = if (seconds >= other.seconds) tempo else other.tempo
        return SoundPrint(
            loud = a * loud + b * other.loud,
            bright = a * bright + b * other.bright,
            drive = a * drive + b * other.drive,
            pulse = a * pulse + b * other.pulse,
            tempo = pace,
            seconds = (seconds + other.seconds).coerceAtMost(MAX_SECONDS),
        )
    }

    companion object {
        /** Less than this heard says too little about a song (an intro is not the song). */
        const val MIN_SECONDS = 15f
        const val SETTLED_SECONDS = 90f
        const val MAX_SECONDS = 600f
    }
}

/**
 * Measures a song as its audio goes by (pure: 16-bit PCM in, a [SoundPrint] out). Per sample it
 * only adds up the energy and the energy of the change from the sample before (the highs); every
 * hundredth of a second it looks at how much both rose (an attack) and how attacks repeat (the beat).
 */
class SoundAnalyzer(private val sampleRate: Int, private val channels: Int) {
    private val hop = maxOf(1, sampleRate / HOPS)
    private var inHop = 0
    private var energy = 0.0
    private var edge = 0.0
    private var prev = 0f

    /** Over the hops that sound. */
    private var sounding = 0
    private var sumEnergy = 0.0
    private var sumEdge = 0.0

    /** The level (dB, smoothed) of the hop before: the whole sound and its highs. */
    private var level = 0.0
    private var levelHigh = 0.0
    private var started = false

    /** The attacks of the last [MAX_LAG] hops (circular) and how each lag's pairs add up. */
    private val past = FloatArray(MAX_LAG + 1)
    private val together = DoubleArray(MAX_LAG + 1)
    private var hops = 0
    private var sumAttack = 0.0
    private var sumAttack2 = 0.0

    private var shorts = ShortArray(0)

    /** 16-bit PCM, the channels interleaved, in the machine's byte order. [pcm] is left as it was. */
    fun feed(pcm: ByteBuffer) {
        val view = pcm.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val n = view.remaining() - view.remaining() % channels
        if (n <= 0) return
        if (shorts.size < n) shorts = ShortArray(n)
        view.get(shorts, 0, n)
        val scale = 1f / (32768f * channels)
        var i = 0
        while (i < n) {
            var s = 0
            for (c in 0 until channels) s += shorts[i + c]
            add(s * scale)
            i += channels
        }
    }

    /** One sample (mono, -1..1). */
    fun add(x: Float) {
        val d = x - prev
        prev = x
        energy += x * x
        edge += d * d
        if (++inHop >= hop) endHop()
    }

    private fun endHop() {
        val e = energy / inHop
        val g = edge / inHop
        energy = 0.0
        edge = 0.0
        inHop = 0
        if (e >= SILENCE) {
            sounding++
            sumEnergy += e
            sumEdge += g
        }
        val db = 10.0 * log10(maxOf(e, SILENCE))
        val dbHigh = 10.0 * log10(maxOf(g, SILENCE_HIGH))
        var attack = 0f
        if (started) {
            val l = 0.5 * (db + level)
            val h = 0.5 * (dbHigh + levelHigh)
            attack = (0.5 * (rise(l - level) + rise(h - levelHigh))).toFloat()
            level = l
            levelHigh = h
        } else {
            level = db
            levelHigh = dbHigh
            started = true
        }
        val size = past.size
        val at = hops % size
        if (attack > 0f) {
            sumAttack += attack
            sumAttack2 += attack * attack
            var j = at - MIN_LAG
            if (j < 0) j += size
            for (lag in MIN_LAG..MAX_LAG) {
                together[lag] += attack * past[j]
                if (--j < 0) j = size - 1
            }
        }
        past[at] = attack
        hops++
    }

    /** A rise in level as an attack: a flutter is none, a blast counts up to [MAX_RISE]. */
    private fun rise(db: Double): Double = if (db < MIN_RISE) 0.0 else minOf(db, MAX_RISE)

    /** What was heard so far; null while it is too little to say. */
    fun print(): SoundPrint? {
        val seconds = sounding.toFloat() / HOPS
        if (seconds < SoundPrint.MIN_SECONDS || sumEnergy <= 0.0) return null
        val mean = sumAttack / hops
        val spread = sumAttack2 - hops * mean * mean
        var best = 0
        var bestScore = 0.0
        for (lag in MIN_LAG..MAX_LAG) {
            val c = together[lag] - (hops - lag) * mean * mean
            if (c <= 0.0) continue
            // Between a beat and its double or half, the one nearer a walking pace is the one felt.
            val octaves = ln(60.0 * HOPS / lag / TEMPO_CENTER) / ln(2.0)
            val score = c * exp(-0.5 * (octaves / TEMPO_SPREAD) * (octaves / TEMPO_SPREAD))
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        val pulse = if (best == 0 || spread <= 0.0) 0.0 else ((together[best] - (hops - best) * mean * mean) / spread).coerceIn(0.0, 1.0)
        return SoundPrint(
            loud = (10.0 * log10(sumEnergy / sounding)).toFloat(),
            bright = (sqrt(sumEdge / sumEnergy) * sampleRate / (2 * PI)).toFloat(),
            drive = (sumAttack * HOPS / hops).toFloat(),
            pulse = pulse.toFloat(),
            tempo = if (best == 0) 0f else 60f * HOPS / best,
            seconds = seconds,
        )
    }

    companion object {
        /** Looks at the sound this many times a second. */
        const val HOPS = 100
        /** Below this (-60 dBFS) it is silence: not counted, and no attack is read in it. */
        private const val SILENCE = 1e-6
        private const val SILENCE_HIGH = 1e-8
        /** An attack: the level rising at least this much in a hop, counted up to this many dB. */
        private const val MIN_RISE = 0.3
        private const val MAX_RISE = 12.0
        /** The beats looked for: from 240 BPM down to 40 BPM (in hops between two beats). */
        private const val MIN_LAG = 25
        private const val MAX_LAG = 150
        private const val TEMPO_CENTER = 120.0
        private const val TEMPO_SPREAD = 1.0
    }
}

/** Where a song sits among everything this listener heard: 0..1 from calm to intense, 0..1 from still to moving. */
data class Spot(val energy: Float, val motion: Float)

/**
 * This listener's own scale of sound: each measure of a [SoundPrint] is read as its place among
 * the songs they heard (0 = the least of all, 1 = the most), so a quiet ear and a loud one are
 * both spread over the whole map, and the roughness of the measures doesn't matter.
 */
class SoundMap private constructor(
    private val loud: FloatArray,
    private val bright: FloatArray,
    private val drive: FloatArray,
    private val pulse: FloatArray,
    private val tempo: FloatArray,
) {
    private var energy = FloatArray(0)
    private var motion = FloatArray(0)

    fun spot(p: SoundPrint): Spot = Spot(place(energy, energyOf(p)), place(motion, motionOf(p)))

    /** The mood: loud and bright, with how much happens. */
    private fun energyOf(p: SoundPrint): Float = 0.4f * place(loud, p.loud) + 0.4f * place(bright, p.bright) + 0.2f * place(drive, p.drive)

    /** The rhythm: how much happens, how steady the beat, how fast. */
    private fun motionOf(p: SoundPrint): Float = 0.45f * place(drive, p.drive) + 0.35f * place(pulse, p.pulse) + 0.2f * place(tempo, p.tempo)

    companion object {
        /** Fewer songs measured than this, and there is no scale yet. */
        const val MIN_SONGS = 12

        fun of(prints: Collection<SoundPrint>): SoundMap? {
            if (prints.size < MIN_SONGS) return null
            fun sorted(of: (SoundPrint) -> Float) = FloatArray(prints.size).also { a ->
                prints.forEachIndexed { i, p -> a[i] = of(p) }
                a.sort()
            }
            val map = SoundMap(sorted { it.loud }, sorted { it.bright }, sorted { it.drive }, sorted { it.pulse }, sorted { it.tempo })
            map.energy = sorted(map::energyOf)
            map.motion = sorted(map::motionOf)
            return map
        }

        /** 0..1: the share of [sorted] below [v] (those equal to it count half). */
        internal fun place(sorted: FloatArray, v: Float): Float {
            if (sorted.isEmpty()) return 0.5f
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < v) lo = mid + 1 else hi = mid
            }
            val below = lo
            hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= v) lo = mid + 1 else hi = mid
            }
            return (below + lo) / (2f * sorted.size)
        }
    }
}
