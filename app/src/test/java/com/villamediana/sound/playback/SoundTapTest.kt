package com.villamediana.sound.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** The tap on the player's audio: it measures the song between begin and take, and only then. */
class SoundTapTest {
    private val tap = SoundTap()

    /** [seconds] of a stereo tone in 20 ms buffers, as the player hands them on. */
    private fun play(seconds: Int, rate: Int = 44_100, level: Double = 0.3) {
        val frames = rate / 50
        var n = 0
        repeat(seconds * 50) {
            val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames) {
                val v = (level * 32767 * sin(2 * PI * 440 * n++ / rate)).toInt().toShort()
                b.putShort(v).putShort(v)
            }
            b.flip()
            tap.handleBuffer(b.asReadOnlyBuffer())
        }
    }

    @Test
    fun aSongIsMeasuredFromBeginToTake() {
        tap.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        tap.begin()
        play(20)
        val p = tap.take()!!
        assertEquals(20f, p.seconds, 0.5f)
        assertEquals(-13.5f, p.loud, 0.5f)
        assertEquals(440f, p.bright, 20f)
        // Taken: nothing more until the next song.
        play(20)
        assertNull(tap.take())
    }

    @Test
    fun nothingIsHeardWithoutBeingAskedOrWhenTheSongIsKnown() {
        tap.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        play(20)
        assertNull(tap.take())
        tap.begin()
        tap.rest()
        play(20)
        assertNull(tap.take())
        // Too little of a song says nothing.
        tap.begin()
        play(5)
        assertNull(tap.take())
    }

    @Test
    fun anotherKindOfAudioIsAnotherSong() {
        tap.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        tap.begin()
        play(20)
        // The next song (another sample rate) starts flowing before the player says so.
        tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        play(1, rate = 48_000, level = 0.9)
        val p = tap.take()
        assertNotNull(p)
        assertEquals(-13.5f, p!!.loud, 0.5f)
        // Audio that is not 16-bit is left alone.
        tap.flush(48_000, 2, C.ENCODING_PCM_FLOAT)
        tap.begin()
        play(20, rate = 48_000)
        assertNull(tap.take())
    }
}
