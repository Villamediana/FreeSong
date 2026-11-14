package com.villamediana.sound.karaoke

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A song's masks on disk, the model's answer turned into them, and the voice turned down with them. */
class VoiceMasksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rate = 44_100

    /** A part where every moment keeps the bands below [cut] and drops the ones from it up. */
    private fun part(masks: VoiceMasks, part: Int, cut: Int, above: Int = 0): ByteArray {
        val data = ByteArray(masks.framesIn(part) * VoiceMasks.FRAME_BYTES)
        for (f in 0 until masks.framesIn(part)) for (side in 0..1) {
            val at = f * VoiceMasks.FRAME_BYTES + side * VoiceMasks.VALUES
            for (b in 0 until VoiceMasks.BINS) data[at + b] = if (b < cut) 255.toByte() else 0
            data[at + VoiceMasks.BINS] = above.toByte()
        }
        return data
    }

    @Test fun `parts are kept as they come and found again another day`() {
        val file = tmp.newFile("song.masks").apply { delete() }
        // A song of 30 s: three parts, the last one short.
        val frames = VoiceMasks.framesFor(30L * rate)
        var masks = VoiceMasks.open(file, frames)
        assertEquals(3, masks.parts)
        assertEquals(frames - 2 * VoiceMasks.PART, masks.framesIn(2))
        assertEquals(0, masks.done)
        assertEquals(1, masks.partAt(15_000))
        assertEquals(2, masks.partAt(600_000))
        val left = FloatArray(2_049)
        val right = FloatArray(2_049)
        assertFalse(masks.at(12.0, rate / 4_096.0, left, right))
        // The part being played is worked out first.
        masks.write(1, part(masks, 1, cut = 100))
        assertTrue(masks.isReady(1))
        assertFalse(masks.isReady(0))
        assertTrue(masks.at(12.0, rate / 4_096.0, left, right))
        assertEquals(1f, left[50], 0f)
        assertEquals(0f, right[500], 0f)
        // The bands above the model's take what it said of its top ones.
        assertEquals(0f, left[1_500], 0f)
        // A moment whose neighbour isn't known yet isn't either (the edge of the part).
        assertFalse(masks.at(5.0, rate / 4_096.0, left, right))
        masks.close()

        masks = VoiceMasks.open(file, frames)
        assertEquals(1, masks.done)
        assertFalse(masks.complete)
        masks.write(0, part(masks, 0, cut = 100))
        masks.write(2, part(masks, 2, cut = 100, above = 255))
        assertTrue(masks.complete)
        assertTrue(masks.at(29.9, rate / 4_096.0, left, right))
        assertEquals(1f, left[1_500], 0f)
        masks.close()
        // Another recording under the same name (another length) starts over.
        assertEquals(0, VoiceMasks.open(file, frames + 40).also { it.close() }.done)
    }

    @Test fun `a song at another rate reads the same bands`() {
        val masks = VoiceMasks.open(tmp.newFile("a.masks").apply { delete() }, VoiceMasks.framesFor(8L * rate))
        // Kept up to band 400: 4.3 kHz.
        masks.write(0, part(masks, 0, cut = 400))
        val left = FloatArray(2_049)
        val right = FloatArray(2_049)
        assertTrue(masks.at(2.0, 48_000 / 4_096.0, left, right))
        // At 48 kHz that is bin 367: well below it kept, well above it gone.
        assertEquals(1f, left[360], 0f)
        assertEquals(0f, left[372], 0f)
        masks.close()
    }

    private fun mask(input: FloatArray, channels: Int, rate: Int = this.rate, chunk: Int = 1_000, from: Long = 0, setUp: (VoiceMasker) -> Unit = {}): FloatArray {
        val masker = VoiceMasker(channels, rate)
        masker.reset(from)
        setUp(masker)
        val frames = input.size / channels
        val out = FloatArray(input.size)
        var done = 0
        var at = 0
        while (at < frames) {
            val n = minOf(chunk, frames - at)
            masker.write(input, at, n)
            at += n
            done += masker.read(out, done, frames - done)
            assertTrue(at - done <= masker.lookahead)
        }
        masker.finish()
        done += masker.read(out, done, frames - done)
        assertEquals(frames, done)
        return out
    }

    private fun tones(seconds: Double, vararg partials: Pair<Double, Double>) =
        FloatArray((seconds * rate).toInt()) { i -> partials.sumOf { (f, a) -> a * sin(2 * PI * f * i / rate) }.toFloat() }

    private fun level(x: FloatArray, hz: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        var norm = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            re += x[from + i] * w * cos(2 * PI * hz * (from + i) / rate)
            im += x[from + i] * w * sin(2 * PI * hz * (from + i) / rate)
            norm += w
        }
        return 2 * sqrt(re * re + im * im) / norm
    }

    @Test fun `without masks the audio comes out as it went in`() {
        val random = java.util.Random(1)
        val x = FloatArray(2 * rate) { (random.nextGaussian() * 0.2).toFloat() }
        val out = mask(x, 2)
        var worst = 0f
        for (i in x.indices) worst = maxOf(worst, abs(out[i] - x[i]))
        assertTrue("off by $worst", worst < 1e-5f)
        assertArrayEquals(out, mask(x, 2, chunk = 7), 0f)
    }

    @Test fun `the bands the masks call voice go down, the rest stays`() {
        // A "voice" at 2 kHz over a "band" at 300 Hz; the masks know the song from its 2nd second on.
        val x = tones(6.0, 300.0 to 0.3, 2_000.0 to 0.3)
        val known = MaskSource { seconds, binHz, left, right ->
            if (seconds < 2.0) return@MaskSource false
            for (b in left.indices) {
                val g = if (b * binHz > 1_000) 0f else 1f
                left[b] = g
                right[b] = g
            }
            true
        }
        val out = mask(x, 1) { it.masks = known }
        // Before the masks know it: the song as it is.
        assertEquals(0.3, level(out, 2_000.0, rate / 4, rate * 3 / 2), 0.005)
        // From there the voice goes down over a moment and stays down; the band never moves.
        assertTrue(level(out, 2_000.0, 3 * rate, 5 * rate) < 0.001)
        assertEquals(0.3, level(out, 300.0, 3 * rate, 5 * rate), 0.005)
        assertEquals(0.3, level(out, 300.0, rate * 3 / 2, rate * 5 / 2), 0.01)

        // The same song from its 4th minute (a seek): the masks are asked for that moment.
        val asked = ArrayList<Double>()
        mask(x.copyOf(rate), 1, from = 240L * rate) { m -> m.masks = MaskSource { s, _, _, _ -> asked += s; false } }
        assertTrue(asked.first() in 239.9..240.05 && asked.last() in 240.9..241.1)

        // The voice asked back while it plays: it returns without a jump.
        var masker: VoiceMasker? = null
        val back = mask(x, 1, chunk = 441) { masker = it; it.masks = MaskSource { _, binHz, l, r -> for (b in l.indices) { l[b] = if (b * binHz > 1_000) 0f else 1f; r[b] = l[b] }; true } }
        assertTrue(level(back, 2_000.0, rate, 5 * rate) < 0.001)
        val returning = FloatArray(x.size)
        VoiceMasker(1, rate).apply {
            reset(0, startDown = true)
            masks = known
            var done = 0
            var at = 0
            while (at < x.size) {
                if (at >= 4 * rate) wanted = false
                write(x, at, 441)
                at += 441
                done += read(returning, done, x.size - done)
            }
            finish()
            read(returning, done, x.size - done)
        }
        assertTrue(level(returning, 2_000.0, 5 * rate / 2, 7 * rate / 2) < 0.001)
        assertEquals(0.3, level(returning, 2_000.0, 5 * rate, 6 * rate - rate / 10), 0.005)
        assertTrue(masker != null)
    }

    @Test fun `the model's answer becomes the part's masks`() {
        // A model that calls everything from band 200 up "voice", twice as loud as it is.
        val asked = ArrayList<Triple<Int, Long, Int>>()
        val model = object : StemModel {
            override fun stems(mix: FloatArray): StemModel.Stems {
                val band = FloatArray(mix.size)
                val voice = FloatArray(mix.size)
                for (i in mix.indices) if (i % VoiceMasks.BINS < 200) band[i] = mix[i] else voice[i] = 2 * mix[i]
                return StemModel.Stems(band, voice)
            }
            override fun close() = Unit
        }
        val random = java.util.Random(2)
        val data = VocalRemover(model).part(1) { side, from, into ->
            asked += Triple(side, from, into.size)
            for (i in into.indices) into[i] = (random.nextGaussian() * 0.1).toFloat()
        }
        // The part's moments are the middle of what the model heard: 32 moments before, as many after.
        val first = (VoiceMasks.PART - VocalRemover.LEAD).toLong() * VoiceMasks.HOP - VoiceMasks.FFT / 2
        assertEquals(listOf(Triple(0, first, 511 * VoiceMasks.HOP + VoiceMasks.FFT), Triple(1, first, 511 * VoiceMasks.HOP + VoiceMasks.FFT)), asked)
        assertEquals(VoiceMasks.PART * VoiceMasks.FRAME_BYTES, data.size)
        for (f in listOf(0, 200, VoiceMasks.PART - 1)) for (side in 0..1) {
            val at = f * VoiceMasks.FRAME_BYTES + side * VoiceMasks.VALUES
            assertEquals(255, data[at + 10].toInt() and 0xFF)
            assertEquals(255, data[at + 199].toInt() and 0xFF)
            assertEquals(0, data[at + 200].toInt() and 0xFF)
            assertEquals(0, data[at + VoiceMasks.BINS].toInt() and 0xFF)
        }
        // Silence is kept whole (nothing to take away).
        val quiet = VocalRemover(model).part(0) { _, _, into -> into.fill(0f) }
        assertEquals(255, quiet[500].toInt() and 0xFF)
    }
}
