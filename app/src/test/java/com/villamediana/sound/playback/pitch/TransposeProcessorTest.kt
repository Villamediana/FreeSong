package com.villamediana.sound.playback.pitch

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** The transposer as the player's sink drives it: by song, mid-song changes, ends and seeks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(UnstableApi::class)
class TransposeProcessorTest {
    private val rate = 44_100

    /** A queue of [ids], one period each: what the sink tells a processor at a flush. */
    private class Queue(private val ids: List<String>) : Timeline() {
        override fun getWindowCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(ids[windowIndex], MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, 200_000_000, windowIndex, windowIndex, 0)
        override fun getPeriodCount() = ids.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(ids[periodIndex], uid(periodIndex), periodIndex, 200_000_000, 0)
        override fun getIndexOfPeriod(uid: Any) = ids.indices.firstOrNull { uid(it) == uid } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = uid(periodIndex)
        fun uid(i: Int): Any = "period-${ids[i]}"
        fun stream(i: Int, positionUs: Long = 0): AudioProcessor.StreamMetadata =
            AudioProcessor.StreamMetadata.Builder().setTimeline(this).setPeriodUid(uid(i)).setPositionOffsetUs(positionUs).build()
    }

    private val queue = Queue(listOf("7", "yt:abc"))
    private val keys = HashMap<String?, Int>()
    private val processor = TransposeProcessor { keys[it] ?: 0 }

    private fun configure(channels: Int = 1, encoding: Int = C.ENCODING_PCM_16BIT) {
        processor.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
    }

    private fun note(hz: Double, frames: Int, from: Int = 0, level: Double = 0.3) = ShortArray(frames) { (level * 32_767 * sin(2 * PI * hz * (from + it) / rate)).toInt().toShort() }

    /** Hands [samples] over in buffers of [chunk] frames; what came out meanwhile. */
    private fun play(samples: ShortArray, chunk: Int = 882, channels: Int = 1): ShortArray {
        val out = ArrayList<Short>()
        var at = 0
        while (at < samples.size) {
            val n = minOf(chunk * channels, samples.size - at)
            val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) b.putShort(samples[at + i])
            b.flip()
            processor.queueInput(b)
            assertEquals("the buffer is taken whole", 0, b.remaining())
            take(out)
            at += n
        }
        return out.toShortArray()
    }

    private fun take(out: MutableList<Short>) {
        val got = processor.output.order(ByteOrder.nativeOrder())
        while (got.remaining() >= 2) out += got.short
    }

    private fun end(): ShortArray {
        val out = ArrayList<Short>()
        processor.queueEndOfStream()
        take(out)
        assertTrue(processor.isEnded)
        return out.toShortArray()
    }

    /** Amplitude (of full scale) and phase of [x] at [hz] over frames [from, to). */
    private fun probe(x: ShortArray, hz: Double, from: Int, to: Int): Pair<Double, Double> {
        var re = 0.0
        var im = 0.0
        var norm = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            val ph = 2 * PI * hz * (from + i) / rate
            re += x[from + i] / 32_768.0 * w * cos(ph)
            im -= x[from + i] / 32_768.0 * w * sin(ph)
            norm += w
        }
        return 2 * sqrt(re * re + im * im) / norm to atan2(im, re)
    }

    private fun cents(x: ShortArray, hz: Double, from: Int, length: Int = rate / 2): Double {
        val gap = rate / 4
        var d = probe(x, hz, from + gap, from + gap + length).second - probe(x, hz, from, from + length).second
        d -= 2 * PI * Math.rint(d / (2 * PI))
        return 1200 * ln((hz + d / (2 * PI) * rate / gap) / hz) / ln(2.0)
    }

    private fun rms(x: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += (x[i] / 32_768.0) * (x[i] / 32_768.0)
        return sqrt(s / (to - from))
    }

    @Test fun `a song in its own key goes through untouched`() {
        configure()
        processor.flush(queue.stream(0))
        val x = note(440.0, rate)
        assertArrayEquals(x, play(x))
        assertEquals(0, end().size)
    }

    @Test fun `a transposed song is in its key from the first frame to the last`() {
        keys["7"] = 2
        configure()
        processor.flush(queue.stream(0))
        val x = note(440.0, 3 * rate)
        val out = play(x) + end()
        assertEquals("not a frame more or less", x.size, out.size)
        val want = 440.0 * PitchShifter.ratioOf(2)
        assertEquals(0.0, cents(out, want, rate / 10), 1.0)
        assertEquals(0.3, probe(out, want, rate / 10, rate).first, 0.02)
        assertEquals(0.3, probe(out, want, 2 * rate, 3 * rate - rate / 10).first, 0.02)
        assertTrue(probe(out, 440.0, rate, 2 * rate).first < 0.01)
    }

    @Test fun `each song plays in its own key`() {
        keys["7"] = -3
        configure()
        // The first song ends (the sink drains it), the next starts: as recorded, from its top.
        processor.flush(queue.stream(0))
        val first = note(330.0, rate)
        val a = play(first) + end()
        assertEquals(first.size, a.size)
        assertEquals(0.0, cents(a, 330.0 * PitchShifter.ratioOf(-3), rate / 10), 1.0)
        processor.flush(queue.stream(1))
        val second = note(523.25, rate)
        assertArrayEquals(second, play(second))
        // Back in the first one (a tap on "previous"), and a seek inside it: its key again, at once.
        processor.flush(queue.stream(0, positionUs = 30_000_000))
        val again = play(first) + end()
        assertEquals(0.3, probe(again, 330.0 * PitchShifter.ratioOf(-3), rate / 10, rate - rate / 10).first, 0.02)
    }

    @Test fun `the key changes while the song plays, without a gap or a bump`() {
        configure()
        processor.flush(queue.stream(0))
        val x = note(440.0, 6 * rate)
        val out = ArrayList<Short>()
        // Two seconds as recorded: nothing held back.
        out += play(x.copyOfRange(0, 2 * rate)).toList()
        assertEquals(2 * rate, out.size)
        // "+" twice by the chords.
        keys["7"] = 2
        processor.refresh()
        out += play(x.copyOfRange(2 * rate, 4 * rate)).toList()
        val behind = 4 * rate - out.size
        assertTrue("runs $behind frames behind while transposing", behind in 1..rate / 5)
        // Back to the song's own key: what was held comes out, and nothing runs behind anymore.
        keys["7"] = 0
        processor.refresh()
        out += play(x.copyOfRange(4 * rate, 6 * rate)).toList()
        assertEquals(6 * rate, out.size)
        assertEquals(0, end().size)
        val y = out.toShortArray()

        assertArrayEquals(x.copyOfRange(0, 2 * rate), y.copyOfRange(0, 2 * rate))
        assertEquals(0.0, cents(y, 440.0 * PitchShifter.ratioOf(2), 2 * rate + rate / 2), 1.0)
        // Plain again to the frame, shortly after the change.
        assertArrayEquals(x.copyOfRange(4 * rate + rate / 2, 6 * rate), y.copyOfRange(4 * rate + rate / 2, 6 * rate))
        // The level holds through both changes.
        val steady = 0.3 / sqrt(2.0)
        for (start in 2 * rate - rate / 5 until 4 * rate + rate / 2 step rate / 100) {
            val level = rms(y, start, start + rate / 50)
            assertTrue("level $level at ${start.toDouble() / rate} s", level > steady * 0.75 && level < steady * 1.3)
        }
    }

    @Test fun `stereo and floats`() {
        keys["7"] = 4
        configure(channels = 2, encoding = C.ENCODING_PCM_FLOAT)
        processor.flush(queue.stream(0))
        val frames = 2 * rate
        val b = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            b.putFloat((0.4 * sin(2 * PI * 220.0 * i / rate)).toFloat())
            b.putFloat((0.1 * sin(2 * PI * 220.0 * i / rate)).toFloat())
        }
        b.flip()
        processor.queueInput(b)
        val first = processor.output.order(ByteOrder.nativeOrder())
        val got = ArrayList<Float>()
        while (first.remaining() >= 4) got += first.float
        processor.queueEndOfStream()
        val rest = processor.output.order(ByteOrder.nativeOrder())
        while (rest.remaining() >= 4) got += rest.float
        assertEquals(frames * 2, got.size)
        val left = ShortArray(frames) { (got[it * 2] * 32_767).toInt().toShort() }
        val right = ShortArray(frames) { (got[it * 2 + 1] * 32_767).toInt().toShort() }
        val want = 220.0 * PitchShifter.ratioOf(4)
        assertEquals(0.4, probe(left, want, rate / 2, frames - rate / 4).first, 0.02)
        assertEquals(0.1, probe(right, want, rate / 2, frames - rate / 4).first, 0.01)
    }

    @Test fun `audio it cannot transpose is left to the player`() {
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT)))
        assertTrue(!processor.isActive)
    }

    @Test fun `through the player's own sink, song after song`() {
        // The real sink: it drains the first song when the next is configured and tells the
        // processor which song each stretch of audio is.
        val written = ByteArrayOutputStream()
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            keys["7"] = 2
            val sink = DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).setAudioProcessors(arrayOf<AudioProcessor>(processor)).build()
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(1).setSampleRate(rate).build()
            val offset = 1_000_000_000_000L
            fun feed(startUs: Long, samples: ShortArray) {
                var at = 0
                while (at < samples.size) {
                    val n = minOf(882, samples.size - at)
                    val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
                    for (i in 0 until n) b.putShort(samples[at + i])
                    b.flip()
                    var tries = 0
                    while (!sink.handleBuffer(b, offset + startUs + at * 1_000_000L / rate, 1)) check(++tries < 200) { "the sink stopped taking audio" }
                    at += n
                }
            }
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).setTimeline(queue).setMediaPeriodId(MediaSource.MediaPeriodId(queue.uid(0))).build())
            sink.setOutputStreamOffsetUs(offset)
            sink.play()
            val first = note(440.0, 2 * rate)
            feed(0, first)
            // The next song, as the renderer announces it.
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).setTimeline(queue).setMediaPeriodId(MediaSource.MediaPeriodId(queue.uid(1))).build())
            sink.handleDiscontinuity()
            val second = note(660.0, 2 * rate)
            feed(2_000_000, second)
            sink.playToEndOfStream()
            var tries = 0
            while (!sink.isEnded && tries++ < 200) sink.hasPendingData()
            val bytes = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.nativeOrder())
            val heard = ShortArray(bytes.remaining() / 2) { bytes.short }
            assertTrue("heard ${heard.size} frames", heard.size >= 3 * rate + rate / 2)
            // The first song a tone up, whole; the second as recorded, from its very first frame.
            assertEquals(0.3, probe(heard, 440.0 * PitchShifter.ratioOf(2), rate / 4, 2 * rate - rate / 4).first, 0.02)
            assertTrue(probe(heard, 440.0, rate / 4, 2 * rate - rate / 4).first < 0.01)
            assertArrayEquals(second.copyOfRange(0, rate), heard.copyOfRange(2 * rate, 3 * rate))
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }
}
