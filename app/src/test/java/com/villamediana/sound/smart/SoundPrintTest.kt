package com.villamediana.sound.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** What a song sounds like, measured from its audio: level, brightness, attacks, beat. */
class SoundPrintTest {
    private val rate = 22_050

    private fun tone(hz: Double, level: Float, seconds: Int): FloatArray =
        FloatArray(rate * seconds) { (level * sin(2 * PI * hz * it / rate)).toFloat() }

    /** A low hum with a short burst of noise [bpm] times a minute. */
    private fun beat(bpm: Int, seconds: Int): FloatArray {
        val rnd = Random(7)
        val every = rate * 60 / bpm
        return FloatArray(rate * seconds) { i ->
            val since = (i % every).toDouble() / rate
            (0.1 * sin(2 * PI * 110 * i / rate) + 0.5 * exp(-since / 0.03) * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private fun measure(samples: FloatArray): SoundPrint? = SoundAnalyzer(rate, 1).also { a -> samples.forEach(a::add) }.print()

    @Test
    fun aBeatIsFoundAtItsPace() {
        val p = measure(beat(120, 30))!!
        assertEquals(120f, p.tempo, 3f)
        assertTrue("pulse ${p.pulse}", p.pulse > 0.6f)
        assertTrue("drive ${p.drive}", p.drive > 8f)
        assertEquals(100f, measure(beat(100, 30))!!.tempo, 3f)
    }

    @Test
    fun aSteadySoundHasNoBeatAndNoAttacks() {
        val p = measure(tone(440.0, 0.3f, 30))!!
        assertEquals(0f, p.pulse, 0.05f)
        assertEquals(0f, p.tempo, 0f)
        assertTrue("drive ${p.drive}", p.drive < 1f)
    }

    @Test
    fun levelAndBrightnessAreRead() {
        val low = measure(tone(200.0, 0.5f, 20))!!
        val high = measure(tone(4_000.0, 0.05f, 20))!!
        assertEquals(-9f, low.loud, 0.5f)
        assertEquals(-29f, high.loud, 0.5f)
        assertEquals(200f, low.bright, 10f)
        assertTrue("bright ${high.bright}", high.bright > 3_000f)
        assertEquals(20f, low.seconds, 0.5f)
    }

    @Test
    fun tooLittleOrSilenceSaysNothing() {
        assertNull(measure(tone(440.0, 0.3f, 5)))
        assertNull(measure(FloatArray(rate * 30)))
        // Silence around the song doesn't count as the song.
        val p = measure(FloatArray(rate * 10) + tone(440.0, 0.3f, 20) + FloatArray(rate * 10))!!
        assertEquals(20f, p.seconds, 0.5f)
        assertEquals(-13.5f, p.loud, 0.5f)
    }

    @Test
    fun thePlayersBytesAreHeardAsTheSoundTheyAre() {
        val mono = beat(120, 20)
        // Stereo 16-bit, as the player hands it on, in buffers of any size.
        val pcm = ByteBuffer.allocateDirect(mono.size * 4).order(ByteOrder.nativeOrder())
        mono.forEach { x -> repeat(2) { pcm.putShort((x * 32767).toInt().coerceIn(-32768, 32767).toShort()) } }
        pcm.flip()
        val a = SoundAnalyzer(rate, 2)
        while (pcm.hasRemaining()) {
            val chunk = pcm.duplicate()
            chunk.limit(minOf(pcm.limit(), pcm.position() + 3_528))
            a.feed(chunk.asReadOnlyBuffer())
            pcm.position(chunk.limit())
        }
        val fromBytes = a.print()!!
        val direct = measure(mono)!!
        assertEquals(direct.tempo, fromBytes.tempo, 0.01f)
        assertEquals(direct.loud, fromBytes.loud, 0.2f)
        assertEquals(direct.bright, fromBytes.bright, direct.bright * 0.03f)
        assertEquals(direct.drive, fromBytes.drive, direct.drive * 0.05f)
    }

    @Test
    fun twoListensOfASongJoinByHowMuchEachHeard() {
        val a = SoundPrint(-10f, 1_000f, 10f, 0.2f, 90f, 30f)
        val b = SoundPrint(-20f, 3_000f, 30f, 0.6f, 180f, 90f)
        val m = a.merge(b)
        assertEquals(-17.5f, m.loud, 0.01f)
        assertEquals(2_500f, m.bright, 0.5f)
        assertEquals(180f, m.tempo, 0f)
        assertEquals(120f, m.seconds, 0f)
        assertTrue(m.settled)
        assertTrue(!a.settled)
    }

    private fun sound(e: Float, m: Float = e) = SoundPrint(-30f + 20f * e, 500f + 3_000f * e, 5f + 40f * m, 0.1f + 0.7f * m, 70f + 90f * m, 120f)

    @Test
    fun aSongIsPlacedAmongWhatThisListenerHears() {
        assertNull(SoundMap.of((1..5).map { sound(it / 5f) }))
        val prints = (0 until 20).map { sound(it / 20f, 1f - it / 20f) }
        val map = SoundMap.of(prints)!!
        val spots = prints.map(map::spot)
        // From the calmest to the most intense, over the whole scale; the rhythm goes the other way here.
        assertTrue(spots.zipWithNext().all { (a, b) -> a.energy < b.energy })
        assertTrue(spots.first().energy < 0.1f && spots.last().energy > 0.9f)
        assertTrue(spots.first().motion > 0.9f && spots.last().motion < 0.1f)
        // The same songs twice as loud everywhere: the same places.
        val louder = prints.map { it.copy(loud = it.loud + 6f) }
        assertEquals(spots, louder.map(SoundMap.of(louder)!!::spot))
        assertNotNull(map.spot(sound(5f)))
        assertEquals(1f, map.spot(sound(5f)).energy, 0.03f)
    }
}
