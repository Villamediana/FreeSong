package com.villamediana.sound.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(UnstableApi::class)
class SeekBlendTest {
    private val rate = 48_000
    private var now = 0L
    private val blend = SeekBlendProcessor { now }

    private fun start(atUs: Long) {
        blend.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        blend.flush(AudioProcessor.StreamMetadata(atUs))
    }

    /** [frames] stereo frames, both channels [value] of the frame. */
    private fun pcm(frames: Int, value: (Int) -> Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) repeat(2) { b.putShort(value(f).toShort()) }
        b.flip()
        return b
    }

    /** Feeds [input] in 20 ms buffers, as a decoder would, and returns the left channel out. */
    private fun run(input: ByteBuffer): ShortArray {
        val out = ArrayList<Short>()
        val chunk = rate / 50 * 4
        while (input.hasRemaining()) {
            val part = input.slice().order(ByteOrder.nativeOrder())
            part.limit(minOf(chunk, input.remaining()))
            input.position(input.position() + part.remaining())
            blend.queueInput(part)
            val o = blend.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) {
                out += o.short
                o.short
            }
        }
        return out.toShortArray()
    }

    /** The sink's flushes for one seek: one when it stops, one with the new position before its first buffer. */
    private fun seekTo(atUs: Long) {
        blend.flush(AudioProcessor.StreamMetadata(0))
        blend.flush(AudioProcessor.StreamMetadata(atUs))
    }

    @Test fun passesAudioThroughUntouched() {
        start(0)
        val out = run(pcm(4_800) { it % 1000 })
        assertEquals(4_800, out.size)
        assertEquals(999, out[999].toInt())
    }

    @Test fun aLyricTapFadesTheUnheardPartIntoTheNewOne() {
        start(10_000_000)
        // One second handed on (10.0 → 11.0 s) of a ramp: frame k is k % 30000.
        run(pcm(rate) { it % 30_000 })
        // Heard at 10.7 s: 0.3 s still in the output's buffer, thrown away by the seek.
        blend.arm(10_700)
        seekTo(60_000_000)
        val out = run(pcm(rate / 2) { 8_000 })
        val fade = rate * SeekBlend.FADE_MS / 1_000
        // Frame k of the blend: the new part fading in, the old one going on from where it was heard (frame 33 600).
        for (k in listOf(1_000, fade / 2, fade - 10)) {
            val (gOld, gNew) = SeekBlend.gains(k, fade, rate)
            val old = (33_600 + k) % 30_000
            val expected = (8_000 * gNew + old * gOld).toInt()
            assertTrue("frame $k: ${out[k]} vs $expected", kotlin.math.abs(out[k] - expected) <= 1)
        }
        // Equal power halfway; the new part alone after the fade.
        assertEquals(cos(PI / 4).toFloat(), SeekBlend.gains(fade / 2, fade, rate).first, 1e-3f)
        assertEquals(sin(PI / 4).toFloat(), SeekBlend.gains(fade / 2, fade, rate).second, 1e-3f)
        assertEquals(8_000, out[fade + 5].toInt())
        // Starts silent: no click from either part.
        assertEquals(0, out[0].toInt())
    }

    @Test fun anyOtherSeekIsAPlainCut() {
        start(10_000_000)
        run(pcm(rate) { 5_000 })
        seekTo(60_000_000)
        val out = run(pcm(4_800) { 8_000 })
        assertTrue(out.all { it.toInt() == 8_000 })
    }

    @Test fun anAnnouncementThatNoSeekFollowsExpires() {
        start(10_000_000)
        run(pcm(rate) { 5_000 })
        blend.arm(10_700)
        now += (SeekBlend.ARM_TIMEOUT_MS + 1) * 1_000_000
        seekTo(60_000_000)
        val out = run(pcm(4_800) { 8_000 })
        assertTrue(out.all { it.toInt() == 8_000 })
    }

    @Test fun newAudioThatTakesTooLongToLoadComesInDry() {
        start(10_000_000)
        run(pcm(rate) { 5_000 })
        blend.arm(10_700)
        seekTo(60_000_000)
        now += (SeekBlend.STALE_MS + 1) * 1_000_000
        val out = run(pcm(4_800) { 8_000 })
        assertTrue(out.all { it.toInt() == 8_000 })
    }

    @Test fun theTailIsWhatWasHandedOnAndNotHeard() {
        // 300 ms unheard, kept a second: the last 300 ms, faded over the full 300.
        assertEquals(SeekBlend.Tail(14_400, 14_400), SeekBlend.tail(11_000_000, 10_700_000, rate, keptFrames = rate))
        // More than the fade unheard: from the heard frame, for the fade.
        assertEquals(SeekBlend.Tail(24_000, 14_400), SeekBlend.tail(11_000_000, 10_500_000, rate, keptFrames = rate))
        // Heard ahead of what was handed on, older than what is kept, or almost nothing left: no blend.
        assertNull(SeekBlend.tail(11_000_000, 11_100_000, rate, keptFrames = rate))
        assertNull(SeekBlend.tail(11_000_000, 9_000_000, rate, keptFrames = rate))
        assertNull(SeekBlend.tail(11_000_000, 10_990_000, rate, keptFrames = rate))
        assertNotNull(SeekBlend.tail(11_000_000, 10_950_000, rate, keptFrames = rate))
    }
}
