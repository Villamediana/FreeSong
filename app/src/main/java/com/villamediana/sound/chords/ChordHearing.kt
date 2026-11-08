package com.villamediana.sound.chords

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One note heard: MIDI pitch (middle C = 60), when it starts, how long it lasts, how loud (0–127). */
data class Note(val pitch: Int, val startMs: Long, val durationMs: Long, val velocity: Int = 92)

/**
 * The chords of a recording, heard on the phone (from the LauncherAI's ChordHearing).
 *
 * It starts from the notes Basic Pitch hears, not from the raw sound: they already say which
 * pitches ring and for how long, chord tones included. Then, beat by beat:
 *
 * 1. **What rings** — a weight for each of the twelve pitch classes, every note counted for the
 *    part of the beat it lasts, the low ones a little more (the harmony lives low), and the lowest
 *    kept apart as the bass.
 * 2. **What it sounds like** — every major, minor, 7 and m7 chord compared with that weight; the
 *    bass on the root, and a chord that belongs to the song's key, tip it a little.
 * 3. **What a player would write** — a chord holds until something else is clearly better
 *    (Viterbi over the beats, a price on every change), and nothing shorter than about two beats of
 *    a ballad stays a chord of its own: a passing melody note is not the harmony.
 *
 * The beats are the band's own ([BeatTracker]), so chords change where the music does.
 */
object ChordHearing {

    /** A chord and when it rings, in the recording's milliseconds. */
    data class Heard(val chord: Chord, val startMs: Long, val endMs: Long)

    data class Hearing(val chords: List<Heard>, val key: MusicKey?) {
        val sequence: List<Chord> get() = chords.map { it.chord }
    }

    private class Candidate(val chord: Chord, val template: DoubleArray, val tones: Int)

    private val QUALITIES = listOf(
        ChordQuality.MAJOR to listOf(0 to 1.0, 4 to 0.85, 7 to 0.7),
        ChordQuality.MINOR to listOf(0 to 1.0, 3 to 0.85, 7 to 0.7),
        ChordQuality.DOM7 to listOf(0 to 1.0, 4 to 0.85, 7 to 0.7, 10 to 0.6),
        ChordQuality.MIN7 to listOf(0 to 1.0, 3 to 0.85, 7 to 0.7, 10 to 0.6),
    )

    private val CANDIDATES: List<Candidate> = buildList {
        for (root in 0 until 12) for ((quality, tones) in QUALITIES) {
            val t = DoubleArray(12)
            for ((interval, w) in tones) t[(root + interval) % 12] = w
            val norm = sqrt(t.sumOf { it * it })
            for (i in 0 until 12) t[i] /= norm
            add(Candidate(Chord(root, quality), t, tones.size))
        }
    }

    /** What a change of chord costs, on a weak beat and on a strong one. */
    private const val CHANGE = 0.20
    private const val CHANGE_ON_STRONG = 0.12
    private const val BASS_ON_ROOT = 0.15
    private const val BASS_IN_CHORD = 0.05
    private const val IN_KEY = 0.06
    private const val FOUR_NOTES = 0.04

    /** The shortest a chord is written for: about half a bar of a ballad. Two beats at the least. */
    private const val MIN_CHORD_MS = 1_300.0

    /** On an even grid of [bpm] from the first note (what the tests and a click track use). */
    fun hear(notes: List<Note>, bpm: Int): Hearing {
        if (notes.isEmpty()) return Hearing(emptyList(), null)
        val beat = 60_000.0 / bpm.coerceIn(30, 260)
        val t0 = notes.minOf { it.startMs }
        val end = notes.maxOf { it.startMs + it.durationMs }
        val n = ((end - t0) / beat).toInt() + 1
        return hear(notes, LongArray(n + 1) { t0 + (it * beat).toLong() })
    }

    /**
     * On the beats found in the recording ([beatsMs], sorted). Before the first beat and after the
     * last, the grid goes on at the same pace so every note lands in a beat.
     */
    fun hear(notes: List<Note>, beatsMs: LongArray): Hearing {
        if (notes.isEmpty() || beatsMs.size < 2) return Hearing(emptyList(), null)
        val edges = extend(beatsMs, notes.minOf { it.startMs }, notes.maxOf { it.startMs + it.durationMs })
        val frames = edges.size - 1

        // 1. What rings, beat by beat.
        val chroma = Array(frames) { DoubleArray(12) }
        val bassPitch = IntArray(frames) { Int.MAX_VALUE }
        val total = DoubleArray(12)
        for (n in notes) {
            val pc = Math.floorMod(n.pitch, 12)
            val from = n.startMs.toDouble()
            val to = (n.startMs + n.durationMs).toDouble()
            val low = if (n.pitch < 60) 1.3 else 1.0
            val loud = n.velocity.coerceIn(20, 127) / 127.0
            var f = frameAt(edges, n.startMs)
            while (f < frames && edges[f] < to) {
                val a = edges[f].toDouble()
                val b = edges[f + 1].toDouble()
                val overlap = (min(to, b) - max(from, a)) / (b - a)
                if (overlap > 0) {
                    chroma[f][pc] += overlap * low * loud
                    if (overlap >= 0.3 && n.pitch < bassPitch[f]) bassPitch[f] = n.pitch
                }
                f++
            }
            total[pc] += n.durationMs.coerceAtMost(2_000).toDouble()
        }
        val key = Keys.of(total)
        val diatonic = key?.let { diatonicChords(it) }.orEmpty()

        // 2. How much each chord sounds like each beat.
        val emission = Array(frames) { f ->
            val c = chroma[f]
            val norm = sqrt(c.sumOf { it * it })
            val bass = bassPitch[f].takeIf { it != Int.MAX_VALUE }?.let { Math.floorMod(it, 12) }
            DoubleArray(CANDIDATES.size) { k ->
                if (norm <= 1e-9) return@DoubleArray 0.0 // Silence: whatever rang before goes on.
                val cand = CANDIDATES[k]
                var s = 0.0
                for (i in 0 until 12) s += c[i] / norm * cand.template[i]
                if (bass != null) s += if (bass == cand.chord.root) BASS_ON_ROOT else if (cand.template[bass] > 0) BASS_IN_CHORD else 0.0
                if (cand.chord in diatonic) s += IN_KEY
                if (cand.tones == 4) s -= FOUR_NOTES
                s
            }
        }

        // 3. The likeliest chords, held until a change pays for itself.
        val n = CANDIDATES.size
        val score = Array(frames) { DoubleArray(n) }
        val from = Array(frames) { IntArray(n) }
        for (k in 0 until n) score[0][k] = emission[0][k]
        for (f in 1 until frames) {
            val price = if (f % 2 == 0) CHANGE_ON_STRONG else CHANGE
            var bestPrev = 0
            for (k in 1 until n) if (score[f - 1][k] > score[f - 1][bestPrev]) bestPrev = k
            for (k in 0 until n) {
                val stay = score[f - 1][k]
                val move = score[f - 1][bestPrev] - price
                if (stay >= move) { score[f][k] = stay + emission[f][k]; from[f][k] = k } else { score[f][k] = move + emission[f][k]; from[f][k] = bestPrev }
            }
        }
        val path = IntArray(frames)
        path[frames - 1] = (0 until n).maxByOrNull { score[frames - 1][it] } ?: 0
        for (f in frames - 1 downTo 1) path[f - 1] = from[f][path[f]]

        // 4. No chord shorter than a player would write. What is left that short is a melody note
        // or a passing bass, not the harmony: its beats go to whichever neighbour explains them
        // better, shortest first.
        while (true) {
            val runs = runs(path)
            if (runs.size <= 1) break
            val short = runs.filter { (a, b) -> edges[b + 1] - edges[a] < MIN_CHORD_MS || b - a + 1 < 2 }
                .minByOrNull { (a, b) -> edges[b + 1] - edges[a] } ?: break
            val (a, b) = short
            val before = if (a > 0) path[a - 1] else -1
            val after = if (b + 1 < frames) path[b + 1] else -1
            fun fit(k: Int) = if (k < 0) Double.NEGATIVE_INFINITY else (a..b).sumOf { emission[it][k] }
            val into = if (fit(before) >= fit(after)) before else after
            for (f in a..b) path[f] = into
        }

        // Silence at the start borrows the first chord heard, not a guess.
        val heardFrom = (0 until frames).firstOrNull { f -> chroma[f].sum() > 1e-9 } ?: 0
        val chords = mutableListOf<Heard>()
        var f = heardFrom
        while (f < frames) {
            var g = f
            while (g + 1 < frames && path[g + 1] == path[f]) g++
            chords += Heard(CANDIDATES[path[f]].chord, edges[f], edges[g + 1])
            f = g + 1
        }
        return Hearing(chords, key)
    }

    /** The beat grid stretched at its own pace to cover [fromMs]..[toMs]. */
    internal fun extend(beats: LongArray, fromMs: Long, toMs: Long): LongArray {
        val period = ((beats.last() - beats.first()).toDouble() / (beats.size - 1)).coerceAtLeast(150.0)
        val out = ArrayList<Long>(beats.size + 16)
        var t = beats.first().toDouble()
        val before = ArrayList<Long>()
        while (t > fromMs) { t -= period; before += t.toLong() }
        out.addAll(before.reversed())
        beats.forEach { out += it }
        t = beats.last().toDouble()
        while (t < toMs) { t += period; out += t.toLong() }
        return out.toLongArray()
    }

    private fun frameAt(edges: LongArray, ms: Long): Int {
        var lo = 0
        var hi = edges.size - 2
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (edges[mid] <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** The stretches of equal chords in [path], as first and last frame. */
    private fun runs(path: IntArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var a = 0
        for (f in 1..path.size) if (f == path.size || path[f] != path[a]) { out += a to f - 1; a = f }
        return out
    }

    /** The chords that belong to [key]: its triads, the dominant seventh, and the major V of a minor key. */
    internal fun diatonicChords(key: MusicKey): Set<Chord> {
        val major = if (key.minor) (key.tonic + 3) % 12 else key.tonic
        fun c(i: Int, q: ChordQuality) = Chord((major + i) % 12, q)
        val set = mutableSetOf(
            c(0, ChordQuality.MAJOR), c(5, ChordQuality.MAJOR), c(7, ChordQuality.MAJOR), c(7, ChordQuality.DOM7),
            c(2, ChordQuality.MINOR), c(4, ChordQuality.MINOR), c(9, ChordQuality.MINOR), c(2, ChordQuality.MIN7), c(9, ChordQuality.MIN7),
        )
        if (key.minor) {
            set += Chord((key.tonic + 7) % 12, ChordQuality.MAJOR)
            set += Chord((key.tonic + 7) % 12, ChordQuality.DOM7)
        }
        return set
    }
}
