package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The Kotlin [Cqt] and [Btc] against the Python reference (tools/chords/bench/export_btc.py with
 * "synthetic"): the same test signal gives the same spectrum, and that spectrum the same chords.
 */
class BtcTest {
    private fun floats(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/chords/$name")!!.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun rows(flat: FloatArray, width: Int) = Array(flat.size / width) { r -> flat.copyOfRange(r * width, (r + 1) * width) }

    /** C Am F G, 2.5 s each, as export_btc.synthetic() makes it. */
    private fun synthetic(): FloatArray {
        val chords = listOf(listOf(48, 60, 64, 67), listOf(45, 57, 60, 64), listOf(41, 57, 60, 65), listOf(43, 55, 59, 62))
        return FloatArray(22_050 * 25) { n ->
            val t = n / 22_050.0
            val notes = chords[floor(t / 2.5).toInt() % 4]
            var s = 0.0
            for (m in notes) {
                val f = 440.0 * Math.pow(2.0, (m - 69) / 12.0)
                for (h in 1..3) s += 0.2 / h * sin(2 * PI * f * h * t)
            }
            s.toFloat()
        }
    }

    private val model by lazy { Btc(ByteBuffer.wrap(File("src/main/assets/chords/btc_voca.bin").readBytes())) }

    @Test fun theSpectrumIsLibrosas() {
        val want = rows(floats("btc_check.feat.f32"), Cqt.BINS)
        val got = Cqt.features(synthetic())
        assertEquals(want.size, got.size)
        var sum = 0.0
        var n = 0
        var worst = 0.0
        for (t in want.indices) for (b in 0 until Cqt.BINS) {
            // Where there is sound (well above the 1e-6 floor of the log).
            if (want[t][b] < -9f) continue
            val d = abs(want[t][b] - got[t][b]).toDouble()
            sum += d; n++
            worst = maxOf(worst, d)
        }
        assertTrue("mean ${sum / n}", sum / n < 1e-3)
        assertTrue("worst $worst", worst < 0.05)
    }

    @Test fun theModelHearsWhatPyTorchHears() {
        val features = rows(floats("btc_check.feat.f32"), Cqt.BINS)
        val want = rows(floats("btc_check.logp.f32"), Btc.CLASSES)
        val t0 = System.nanoTime()
        val got = model.logProbs(features)
        println("BTC: ${features.size} frames in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(want.size, got.size)
        var worst = 0f
        var same = 0
        for (t in want.indices) {
            for (c in 0 until Btc.CLASSES) if (want[t][c] > -20f) worst = maxOf(worst, abs(want[t][c] - got[t][c]))
            if (want[t].indices.maxBy { want[t][it] } == got[t].indices.maxBy { got[t][it] }) same++
        }
        assertTrue("worst log-prob difference $worst", worst < 0.02f)
        assertEquals(want.size, same)
        // And it hears the progression: C Am F G.
        val heard = got.map { r -> Btc.chord(r.indices.maxBy { r[it] }) }
        assertEquals(Chord(0, ChordQuality.MAJOR), heard[13])
        assertEquals(Chord(9, ChordQuality.MINOR), heard[40])
        assertEquals(Chord(5, ChordQuality.MAJOR), heard[67])
        assertEquals(Chord(7, ChordQuality.MAJOR), heard[94])
    }
}
