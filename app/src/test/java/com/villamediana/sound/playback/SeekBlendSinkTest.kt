package com.villamediana.sound.playback

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The blend inside ExoPlayer's real audio sink: the positions the sink hands the processor on its
 * flushes, its two flushes per seek, and what reaches the output after a lyric tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(UnstableApi::class)
class SeekBlendSinkTest {
    private val rate = 48_000
    private val offset = 1_000_000_000_000L
    private val written = ByteArrayOutputStream()
    private val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }

    @Before fun listen() = ShadowAudioTrack.addAudioDataListener(listener)

    @After fun stop() = ShadowAudioTrack.removeAudioDataListener(listener)

    private val format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(2)
        .setSampleRate(rate)
        .build()

    /** Hands [frames] to the sink from [startUs] (media time) in 20 ms buffers; how many it took. */
    private fun feed(sink: DefaultAudioSink, startUs: Long, frames: Int, value: (Int) -> Int): Int {
        val chunk = rate / 50
        var f = 0
        while (f < frames) {
            val n = minOf(chunk, frames - f)
            val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (k in 0 until n) repeat(2) { b.putShort(value(f + k).toShort()) }
            b.flip()
            var tries = 0
            while (!sink.handleBuffer(b, offset + startUs + f * 1_000_000L / rate, 1)) {
                if (++tries > 50) return f
            }
            f += n
        }
        return f
    }

    private fun left(): ShortArray {
        val b = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.nativeOrder())
        return ShortArray(b.remaining() / 4) { b.short.also { b.short } }
    }

    @Test fun aLyricTapThroughTheRealSink() {
        val blend = SeekBlendProcessor { 0L }
        val sink = DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).setAudioProcessors(arrayOf<AudioProcessor>(blend)).build()
        sink.configure(format, 0, null)
        sink.setOutputStreamOffsetUs(offset)
        sink.play()
        // From 10.0 s, a ramp: frame k is k % 30000.
        val took = feed(sink, 10_000_000, rate) { it % 30_000 }
        assertTrue("the sink took $took frames", took >= rate / 2)
        // Heard 300 ms before the end of what was handed on.
        val handedOnUntilMs = 10_000 + took * 1_000L / rate
        val heardMs = handedOnUntilMs - 300
        blend.arm(heardMs)
        sink.flush()
        written.reset()
        sink.play()
        feed(sink, 60_000_000, rate / 2) { 8_000 }
        val out = left()
        assertTrue("written ${out.size}", out.size > rate / 4)
        val fade = rate * SeekBlend.FADE_MS / 1_000
        val heardFrame = ((heardMs - 10_000) * rate / 1_000).toInt()
        // Past the sink's own ~20 ms fade-in after a flush (it has one of its own since Media3 1.11).
        for (k in listOf(SINK_RAMP, fade / 4, fade / 2, fade - 10)) {
            val (gOld, gNew) = SeekBlend.gains(k, fade, rate)
            val expected = (8_000 * gNew + ((heardFrame + k) % 30_000) * gOld).toInt()
            assertTrue("frame $k: ${out[k]} vs $expected", kotlin.math.abs(out[k] - expected) <= 1)
        }
        assertEquals(8_000, out[fade + 10].toInt())
    }

    @Test fun aSeekBarSeekThroughTheRealSinkIsAPlainCut() {
        val blend = SeekBlendProcessor { 0L }
        val sink = DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).setAudioProcessors(arrayOf<AudioProcessor>(blend)).build()
        sink.configure(format, 0, null)
        sink.setOutputStreamOffsetUs(offset)
        sink.play()
        feed(sink, 10_000_000, rate) { it % 30_000 }
        sink.flush()
        written.reset()
        sink.play()
        feed(sink, 60_000_000, rate / 2) { 8_000 }
        val out = left()
        assertTrue(out.size > SINK_RAMP)
        // The new part alone (after the sink's own fade-in): nothing of the old one.
        assertTrue(out.drop(SINK_RAMP).all { it.toInt() == 8_000 })
    }

    private companion object {
        /** The sink fades in its first ~960 frames after a flush; checks start after that. */
        const val SINK_RAMP = 1_500
    }
}
