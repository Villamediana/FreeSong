package com.villamediana.sound.chords

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MonoResamplerTest {
    private fun sine(hz: Double, rate: Int, seconds: Double) = FloatArray((rate * seconds).toInt()) { sin(2 * PI * hz * it / rate).toFloat() }

    private fun rms(x: FloatArray, from: Int = x.size / 4) = sqrt(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from))

    private fun all(r: MonoResampler, audio: FloatArray, block: Int): FloatArray {
        val out = ArrayList<Float>()
        var at = 0
        while (at < audio.size) {
            val n = minOf(block, audio.size - at)
            out += r.push(audio.copyOfRange(at, at + n)).toList()
            at += n
        }
        return out.toFloatArray()
    }

    @Test fun aNoteGoesThroughWhole() {
        val out = all(MonoResampler(44_100, 22_050), sine(440.0, 44_100, 1.0), 1024)
        assertEquals(22_050.0, out.size.toDouble(), 2.0)
        // A sine's RMS is 1/√2.
        assertEquals(0.707, rms(out), 0.01)
    }

    @Test fun whatIsAboveTheNewNyquistDoesNotFoldDown() {
        // 15 kHz at 44.1 kHz would come out as a loud 7 kHz tone, straight decimated.
        val cymbal = sine(15_000.0, 44_100, 1.0)
        val plain = all(MonoResampler(44_100, 22_050, antiAlias = false), cymbal.copyOf(), 1024)
        val filtered = all(MonoResampler(44_100, 22_050), cymbal.copyOf(), 1024)
        assertTrue(rms(plain) > 0.5)
        assertTrue("left ${rms(filtered)}", rms(filtered) < 0.02)
        // From 48 kHz too (the Opus streams).
        assertTrue(rms(all(MonoResampler(48_000, 22_050), sine(16_000.0, 48_000, 1.0), 960)) < 0.02)
    }

    @Test fun blockSizesDoNotMatter() {
        val audio = sine(1_000.0, 48_000, 0.5)
        val a = all(MonoResampler(48_000, 22_050), audio, 4096)
        val b = all(MonoResampler(48_000, 22_050), audio, 333)
        assertArrayEquals(a, b, 1e-6f)
    }

    @Test fun upFromALowRate() {
        val out = all(MonoResampler(16_000, 22_050), sine(300.0, 16_000, 1.0), 512)
        assertEquals(22_050.0, out.size.toDouble(), 2.0)
        assertEquals(0.707, rms(out), 0.01)
    }
}
