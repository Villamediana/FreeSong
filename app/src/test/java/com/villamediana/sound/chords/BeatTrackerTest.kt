package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Beats of synthetic recordings: a kick and a strum on the beat, hats in between, a little noise. */
class BeatTrackerTest {
    private val rate = BeatTracker.RATE

    private fun hit(out: FloatArray, at: Double, freq: Double, gain: Float, decay: Double) {
        val start = (at * rate).toInt()
        val len = (decay * 6 * rate).toInt()
        for (i in 0 until len) {
            val j = start + i
            if (j !in out.indices) break
            val t = i.toDouble() / rate
            out[j] += (gain * exp(-t / decay) * sin(2 * PI * freq * t)).toFloat()
        }
    }

    private fun song(bpm: Double, seconds: Double, offset: Double, hats: Boolean, seed: Int = 3): Pair<FloatArray, List<Double>> {
        val r = Random(seed)
        val out = FloatArray((seconds * rate).toInt()) { (r.nextFloat() - 0.5f) * 0.01f }
        val period = 60.0 / bpm
        val beats = mutableListOf<Double>()
        var t = offset
        var n = 0
        while (t < seconds - 0.5) {
            beats += t
            hit(out, t, 60.0, if (n % 2 == 0) 0.8f else 0.5f, 0.06) // kick, stronger on 1 and 3
            for (f in listOf(196.0, 247.0, 294.0)) hit(out, t + 0.004, f, 0.12f, 0.25) // a strummed G
            if (hats) hit(out, t + period / 2, 7000.0, 0.15f, 0.01)
            t += period
            n++
        }
        return out to beats
    }

    private fun assertFollows(found: LongArray, truth: List<Double>, toleranceMs: Long = 35) {
        val inner = truth.filter { it > 2.0 && it < truth.last() - 2.0 }
        val matched = inner.count { b -> found.any { abs(it - b * 1000) <= toleranceMs } }
        assertTrue("$matched of ${inner.size} beats found", matched >= inner.size * 0.9)
    }

    @Test fun findsTheBeatsOfAMidTempoSong() {
        val (audio, truth) = song(bpm = 100.0, seconds = 30.0, offset = 0.37, hats = false)
        val beats = BeatTracker.track(audio)
        assertEquals(100.0, beats.bpm, 2.0)
        assertFollows(beats.timesMs, truth)
    }

    @Test fun eighthNoteHatsDontDoubleTheTempo() {
        val (audio, truth) = song(bpm = 128.0, seconds = 30.0, offset = 0.11, hats = true)
        val beats = BeatTracker.track(audio)
        assertEquals(128.0, beats.bpm, 2.6)
        assertFollows(beats.timesMs, truth)
    }

    @Test fun aSlowBalladStaysSlow() {
        val (audio, truth) = song(bpm = 72.0, seconds = 40.0, offset = 0.8, hats = false)
        val beats = BeatTracker.track(audio)
        assertEquals(72.0, beats.bpm, 1.5)
        assertFollows(beats.timesMs, truth)
    }

    @Test fun silenceHasNoBeats() {
        assertEquals(0, BeatTracker.track(FloatArray(rate * 5)).timesMs.size)
    }

    @Test fun fftMatchesTheDefinition() {
        val n = 16
        val x = DoubleArray(n) { sin(2 * PI * 3 * it / n) + 0.5 * sin(2 * PI * 5 * it / n) }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += x[t] * kotlin.math.cos(2 * PI * k * t / n)
                si -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(sr, re[k], 1e-9)
            assertEquals(si, im[k], 1e-9)
        }
    }
}
