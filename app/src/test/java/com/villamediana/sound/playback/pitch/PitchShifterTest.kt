package com.villamediana.sound.playback.pitch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The transposer on sounds whose answer is known: notes, chords, clicks, a stereo picture. */
class PitchShifterTest {
    private val rate = 44_100

    /** [input] (interleaved) through the shifter in blocks of [chunk] frames, as a player hands it over. */
    private fun shift(input: FloatArray, channels: Int, ratio: Double, chunk: Int = 1_024, rate: Int = this.rate, ratioAt: (Int) -> Double = { ratio }): FloatArray {
        val shifter = PitchShifter(channels, rate)
        val frames = input.size / channels
        val out = FloatArray(input.size)
        var done = 0
        var at = 0
        while (at < frames) {
            shifter.ratio = ratioAt(at)
            val n = minOf(chunk, frames - at)
            shifter.write(input, at, n)
            at += n
            done += shifter.read(out, done, frames - done)
        }
        shifter.finish()
        done += shifter.read(out, done, frames - done)
        assertEquals("every frame written comes out", frames, done)
        return out
    }

    private fun tone(seconds: Double, vararg partials: Pair<Double, Double>): FloatArray =
        FloatArray((seconds * rate).toInt()) { i -> partials.sumOf { (f, a) -> a * sin(2 * PI * f * i / rate) }.toFloat() }

    private fun stereo(left: FloatArray, right: FloatArray = left) = FloatArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }

    private fun channel(interleaved: FloatArray, c: Int, channels: Int = 2) = FloatArray(interleaved.size / channels) { interleaved[it * channels + c] }

    /** Amplitude and phase of [x] at [hz] over frames [from, to) (a Hann-weighted correlation). */
    private fun probe(x: FloatArray, hz: Double, from: Int, to: Int): Pair<Double, Double> {
        var re = 0.0
        var im = 0.0
        var norm = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            val ph = 2 * PI * hz * (from + i) / rate
            re += x[from + i] * w * cos(ph)
            im -= x[from + i] * w * sin(ph)
            norm += w
        }
        return 2 * sqrt(re * re + im * im) / norm to atan2(im, re)
    }

    /** The frequency of the partial of [x] near [hz], from how its phase moves between two stretches. */
    private fun frequency(x: FloatArray, hz: Double, from: Int = rate, length: Int = rate): Double {
        val gap = rate / 2
        val a = probe(x, hz, from, from + length).second
        val b = probe(x, hz, from + gap, from + gap + length).second
        var d = b - a
        d -= 2 * PI * Math.rint(d / (2 * PI))
        return hz + d / (2 * PI) * rate / gap
    }

    private fun cents(got: Double, want: Double) = 1200 * ln(got / want) / ln(2.0)

    private fun db(ratio: Double) = 20 * log10(ratio)

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    @Test fun `a note lands on the right pitch`() {
        var worst = 0.0
        for (hz in listOf(55.0, 82.41, 110.0, 146.83, 220.0, 440.0, 1_760.0, 5_000.0)) {
            val note = tone(4.0, hz to 0.4)
            for (semitones in listOf(-7, -5, -2, -1, 1, 2, 5, 7)) {
                val ratio = PitchShifter.ratioOf(semitones)
                val out = shift(note, 1, ratio)
                val want = hz * ratio
                val off = cents(frequency(out, want), want)
                worst = maxOf(worst, abs(off))
                assertTrue("$hz Hz by $semitones: $off cents off", abs(off) < 1.0)
                val level = db(probe(out, want, rate, 3 * rate).first / 0.4)
                assertTrue("$hz Hz by $semitones: level $level dB", abs(level) < 0.5)
            }
        }
        println("pitch: at most ${"%.3f".format(worst)} cents off")
    }

    @Test fun `a chord keeps the balance of its notes`() {
        val notes = listOf(110.0 to 0.25, 220.0 to 0.2, 277.18 to 0.15, 329.63 to 0.15, 440.0 to 0.1, 1_318.5 to 0.05)
        val chord = tone(4.0, *notes.toTypedArray())
        for (semitones in listOf(-4, -2, 2, 4)) {
            val ratio = PitchShifter.ratioOf(semitones)
            val out = shift(chord, 1, ratio)
            for ((hz, level) in notes) {
                val want = hz * ratio
                val off = cents(frequency(out, want), want)
                val got = db(probe(out, want, rate, 3 * rate).first / level)
                assertTrue("$hz Hz by $semitones: $off cents, $got dB", abs(off) < 2.0 && abs(got) < 1.0)
            }
            assertTrue(abs(db(rms(out, rate, 3 * rate) / rms(chord, rate, 3 * rate))) < 0.5)
        }
    }

    /** [x] without its low notes: what is left of it is the hits. */
    private fun bright(x: FloatArray): FloatArray {
        val d = FloatArray(x.size)
        for (i in 2 until x.size) d[i] = x[i] - 2 * x[i - 1] + x[i - 2]
        return d
    }

    /** How much of a hit expected at frame [at] is within [ms] of it, and where its energy is centred (ms). */
    private fun hit(x: FloatArray, at: Int, ms: Int = 5): Pair<Double, Double> {
        val span = rate * 45 / 1_000
        var weight = 0.0
        var moment = 0.0
        var near = 0.0
        for (i in at - span until at + span) {
            val e = x[i].toDouble() * x[i]
            weight += e
            moment += e * (i - at)
            if (abs(i - at) <= rate * ms / 1_000) near += e
        }
        return near / weight to moment / weight * 1_000 / rate
    }

    @Test fun `a hit stays a hit, at its moment`() {
        // Over a held note: a click, and a short burst of noise (a snare), every half second.
        val frames = 5 * rate
        val hits = (1..8).map { it * rate / 2 + 137 * it }
        val random = java.util.Random(7)
        for (kind in listOf("click", "burst")) {
            val x = FloatArray(frames) { i -> (0.15 * sin(2 * PI * 220.0 * i / rate)).toFloat() }
            for (h in hits) {
                if (kind == "click") x[h] += 0.9f
                else for (i in 0 until rate * 3 / 1_000) x[h + i] += (random.nextGaussian() * 0.35 * Math.exp(-i / (rate * 0.001))).toFloat()
            }
            val base = hits.map { hit(bright(x), it).first }.average()
            for (semitones in listOf(1, -1, 2, -2, 3, -3, 5, -5, 7)) {
                val ratio = PitchShifter.ratioOf(semitones)
                val out = shift(x, 1, ratio)
                val edge = bright(out)
                val sharp = hits.map { hit(edge, it).first }.average()
                val late = hits.map { hit(edge, it).second }
                println("$kind by $semitones: ${"%.2f".format(sharp)} of it within 5 ms (${"%.2f".format(base)} before), ${"%+.1f".format(late.average())} ms")
                assertTrue("$kind by $semitones: only $sharp of it within 5 ms", sharp > 0.8)
                assertTrue("$kind by $semitones: ${late.maxOf { abs(it) }} ms off", late.all { abs(it) < 2.0 })
                // The note under the hits holds steady through them.
                val want = 220.0 * ratio
                for (h in hits.drop(1).dropLast(1)) {
                    val level = db(probe(out, want, h - rate / 10, h + rate / 10).first / 0.15)
                    assertTrue("$kind by $semitones: the note is $level dB at a hit", abs(level) < 1.5)
                }
            }
        }
    }

    @Test fun `the stereo picture stays where it was`() {
        // One note mostly on the left, another mostly on the right, a third in the middle.
        val a = tone(3.0, 220.0 to 0.3)
        val b = tone(3.0, 554.37 to 0.3)
        val mid = tone(3.0, 880.0 to 0.2)
        val left = FloatArray(a.size) { a[it] + 0.25f * b[it] + mid[it] }
        val right = FloatArray(a.size) { 0.25f * a[it] + b[it] + mid[it] }
        val ratio = PitchShifter.ratioOf(3)
        val out = shift(stereo(left, right), 2, ratio)
        val l = channel(out, 0)
        val r = channel(out, 1)
        fun side(hz: Double) = db(probe(l, hz * ratio, rate, 2 * rate).first / probe(r, hz * ratio, rate, 2 * rate).first)
        assertEquals(12.04, side(220.0), 0.7)
        assertEquals(-12.04, side(554.37), 0.7)
        assertEquals(0.0, side(880.0), 0.3)
        // The middle note is in phase on both sides, as it was.
        val phase = probe(l, 880.0 * ratio, rate, 2 * rate).second - probe(r, 880.0 * ratio, rate, 2 * rate).second
        assertEquals(0.0, phase - 2 * PI * Math.rint(phase / (2 * PI)), 0.05)
        // The same sound on both sides comes out the same on both sides.
        val same = shift(stereo(left), 2, ratio)
        assertArrayEquals(channel(same, 0), channel(same, 1), 0f)
    }

    @Test fun `the blocks it comes in do not change the sound`() {
        val x = tone(1.5, 196.0 to 0.3, 493.88 to 0.2, 2_637.0 to 0.1)
        val ratio = PitchShifter.ratioOf(-2)
        val whole = shift(x, 1, ratio, chunk = x.size)
        assertArrayEquals(whole, shift(x, 1, ratio, chunk = 1), 0f)
        assertArrayEquals(whole, shift(x, 1, ratio, chunk = 733), 0f)
        assertArrayEquals(whole, shift(x, 1, ratio, chunk = 9_000), 0f)
    }

    @Test fun `the key can change while it plays`() {
        val note = tone(6.0, 440.0 to 0.4)
        val up = PitchShifter.ratioOf(2)
        val down = PitchShifter.ratioOf(-3)
        val out = shift(note, 1, up) { at -> if (at < 3 * rate) up else down }
        assertEquals(0.0, cents(frequency(out, 440.0 * up, rate / 2, rate), 440.0 * up), 1.0)
        assertEquals(0.0, cents(frequency(out, 440.0 * down, 4 * rate, rate), 440.0 * down), 1.0)
        // No jump in level where it changes.
        for (start in 2 * rate until 4 * rate step rate / 20) {
            assertTrue(rms(out, start, start + rate / 20) > 0.15)
        }
    }

    @Test fun `48 kHz and long songs`() {
        val rate = 48_000
        val x = FloatArray(rate * 20) { (0.3 * sin(2 * PI * 330.0 * it / rate)).toFloat() }
        val shifter = PitchShifter(1, rate)
        shifter.ratio = PitchShifter.ratioOf(1)
        val out = FloatArray(x.size)
        var done = 0
        val started = System.nanoTime()
        var at = 0
        while (at < x.size) {
            val n = minOf(960, x.size - at)
            shifter.write(x, at, n)
            at += n
            done += shifter.read(out, done, x.size - done)
            // It never runs further behind than it says.
            assertTrue(at - done <= shifter.lookahead)
        }
        shifter.finish()
        done += shifter.read(out, done, x.size - done)
        assertEquals(x.size, done)
        println("mono 48 kHz: ${"%.1f".format((System.nanoTime() - started) / 1e6 / 20)} ms per second of audio")
        var peak = 0f
        for (i in rate until x.size - rate) peak = maxOf(peak, abs(out[i]))
        assertEquals(0.3, peak.toDouble(), 0.02)
    }

    @Test fun `it is light enough to run while playing`() {
        // Stereo music-like noise with notes, as the player hands it over; warmed up first.
        val random = java.util.Random(3)
        val seconds = 20
        val x = FloatArray(rate * seconds * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (0.2 * sin(2 * PI * 220 * t) + 0.1 * sin(2 * PI * 1_245 * t) + 0.05 * random.nextGaussian()).toFloat()
        }
        var best = Double.MAX_VALUE
        repeat(3) {
            val started = System.nanoTime()
            shift(x, 2, PitchShifter.ratioOf(2), chunk = 960)
            best = minOf(best, (System.nanoTime() - started) / 1e6 / seconds)
        }
        println("stereo 44.1 kHz, +2: ${"%.1f".format(best)} ms per second of audio")
        var down = Double.MAX_VALUE
        repeat(2) {
            val started = System.nanoTime()
            shift(x, 2, PitchShifter.ratioOf(-3), chunk = 960)
            down = minOf(down, (System.nanoTime() - started) / 1e6 / seconds)
        }
        println("stereo 44.1 kHz, -3: ${"%.1f".format(down)} ms per second of audio")
        assertTrue(best < 60 && down < 60)
    }

    @Test fun `silence stays silent and a start is clean`() {
        val quiet = FloatArray(rate)
        assertArrayEquals(quiet, shift(quiet, 1, PitchShifter.ratioOf(4)), 0f)
        // A note after a second of nothing: nothing before it, all of it after.
        val x = FloatArray(3 * rate) { if (it < rate) 0f else (0.4 * sin(2 * PI * 440.0 * (it - rate) / rate)).toFloat() }
        val out = shift(x, 1, PitchShifter.ratioOf(2))
        assertTrue(rms(out, 0, rate - rate / 10) < 0.002)
        assertEquals(0.4 / sqrt(2.0), rms(out, rate + rate / 5, 3 * rate - rate / 5), 0.01)
    }
}
