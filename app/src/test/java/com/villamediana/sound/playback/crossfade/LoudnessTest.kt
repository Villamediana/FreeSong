package com.villamediana.sound.playback.crossfade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class LoudnessTest {
    /** Interleaved 16-bit PCM: [seconds] of a [hz] sine at [amplitude] (0 = silence) on [channels]. */
    private fun tone(rate: Int, channels: Int, seconds: Double, hz: Double, amplitude: Double, phase0: Int = 0): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val b = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * (f + phase0) / rate) * 32767).toInt().toShort()
            repeat(channels) { b.putShort(v) }
        }
        b.flip()
        return b
    }

    private fun measure(rate: Int, channels: Int, vararg parts: Pair<Double, Double>): Envelope {
        val meter = LoudnessMeter(rate, channels, 0, Long.MAX_VALUE)
        var us = 0L
        var frame = 0
        for ((seconds, amplitude) in parts) {
            // In 20 ms buffers, as a decoder hands them out.
            val all = tone(rate, channels, seconds, 997.0, amplitude, frame)
            val chunk = rate / 50 * channels * 2
            while (all.hasRemaining()) {
                val n = minOf(chunk, all.remaining())
                val part = all.slice().order(ByteOrder.nativeOrder()).limit(n) as ByteBuffer
                meter.feed(part, us, float = false)
                all.position(all.position() + n)
                us += n / (channels * 2) * 1_000_000L / rate
            }
            frame += (rate * seconds).toInt()
        }
        return meter.envelope()!!
    }

    @Test fun aSineReadsAsTheStandardSays() {
        // BS.1770: a 997 Hz sine at full scale on one channel is −3.01 LUFS.
        for (rate in listOf(44_100, 48_000)) {
            val e = measure(rate, 1, 5.0 to 0.1)
            assertEquals("mono at $rate", 20 * log10(0.1) - 3.01, e.loudness()!!.toDouble(), 0.15)
            // The same on both channels: twice the power.
            val s = measure(rate, 2, 5.0 to 0.1)
            assertEquals("stereo at $rate", 20 * log10(0.1), s.loudness()!!.toDouble(), 0.15)
        }
    }

    @Test fun silenceIsGatedOut() {
        val e = measure(48_000, 2, 4.0 to 0.0, 4.0 to 0.1, 4.0 to 0.0)
        // The 400 ms windows across each edge count a little (as BS.1770 has it).
        assertEquals(-20.0, e.loudness()!!.toDouble(), 0.4)
        assertNull(measure(48_000, 2, 3.0 to 0.0).loudness())
    }

    @Test fun findsWhereTheSoundStartsAndEnds() {
        val e = measure(48_000, 2, 2.0 to 0.0, 6.0 to 0.1, 3.0 to 0.0)
        assertEquals(2_000_000.0, e.firstSoundUs(-45f)!!.toDouble(), 100_000.0)
        assertEquals(8_000_000.0, e.lastSoundUs(-45f)!!.toDouble(), 100_000.0)
        assertNull(measure(48_000, 2, 2.0 to 0.0).lastSoundUs(-45f))
    }

    @Test fun keepsOnlyTheStretchAskedFor() {
        val meter = LoudnessMeter(48_000, 2, 1_000_000, 3_000_000)
        meter.feed(tone(48_000, 2, 5.0, 997.0, 0.1), 0, float = false)
        val e = meter.envelope()
        assertNotNull(e)
        assertEquals(1_000_000L, e!!.startUs)
        assertEquals(3_000_000L, e.endUs)
        assertEquals(true, meter.full)
    }

    @Test fun loudnessOfAPartOnly() {
        val e = measure(48_000, 2, 4.0 to 0.03, 4.0 to 0.3)
        assertEquals(20 * log10(0.03), e.loudness(0, 4_000_000)!!.toDouble(), 0.3)
        assertEquals(20 * log10(0.3), e.loudness(4_000_000, 8_000_000)!!.toDouble(), 0.3)
    }
}
