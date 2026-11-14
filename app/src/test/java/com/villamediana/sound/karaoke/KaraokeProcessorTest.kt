package com.villamediana.sound.karaoke

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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The karaoke as the player's sink drives it: by song, turned on and off mid-song, ends and seeks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(UnstableApi::class)
class KaraokeProcessorTest {
    private val rate = 44_100

    private class Queue(private val ids: List<String>) : Timeline() {
        override fun getWindowCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(ids[windowIndex], MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, 200_000_000, windowIndex, windowIndex, 0)
        override fun getPeriodCount() = ids.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period.set(ids[periodIndex], uid(periodIndex), periodIndex, 200_000_000, 0)
        override fun getIndexOfPeriod(uid: Any) = ids.indices.firstOrNull { uid(it) == uid } ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = uid(periodIndex)
        fun uid(i: Int): Any = "period-${ids[i]}"
        fun stream(i: Int, positionUs: Long = 0): AudioProcessor.StreamMetadata =
            AudioProcessor.StreamMetadata.Builder().setTimeline(this).setPeriodUid(uid(i)).setPositionOffsetUs(positionUs).build()
    }

    private val queue = Queue(listOf("7", "yt:abc"))

    /** Masks that call everything above 1 kHz voice, from [fromSeconds] of the song on. */
    private fun above1k(fromSeconds: Double = 0.0, asked: MutableList<Double>? = null) = MaskSource { seconds, binHz, left, right ->
        asked?.add(seconds)
        if (seconds < fromSeconds) return@MaskSource false
        for (b in left.indices) {
            val g = if (b * binHz > 1_000) 0f else 1f
            left[b] = g
            right[b] = g
        }
        true
    }

    private val masks = HashMap<String?, MaskSource?>()
    private val processor = KaraokeProcessor { masks[it] }

    /** A "band" at 300 Hz and a "voice" at 2 kHz. */
    private fun song(frames: Int, from: Int = 0) = ShortArray(frames) { i ->
        ((0.3 * sin(2 * PI * 300.0 * (from + i) / rate) + 0.3 * sin(2 * PI * 2_000.0 * (from + i) / rate)) * 32_767).toInt().toShort()
    }

    private fun play(samples: ShortArray, chunk: Int = 882): ShortArray {
        val out = ArrayList<Short>()
        var at = 0
        while (at < samples.size) {
            val n = minOf(chunk, samples.size - at)
            val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) b.putShort(samples[at + i])
            b.flip()
            processor.queueInput(b)
            assertEquals(0, b.remaining())
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

    private fun level(x: ShortArray, hz: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        var norm = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            re += x[from + i] / 32_768.0 * w * cos(2 * PI * hz * (from + i) / rate)
            im += x[from + i] / 32_768.0 * w * sin(2 * PI * hz * (from + i) / rate)
            norm += w
        }
        return 2 * sqrt(re * re + im * im) / norm
    }

    private fun configure() {
        processor.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
    }

    @Test fun `with the karaoke off the song goes through untouched`() {
        configure()
        processor.flush(queue.stream(0))
        val x = song(rate)
        assertArrayEquals(x, play(x))
        assertEquals(0, end().size)
    }

    @Test fun `a song with its masks ready has no voice from the first frame to the last`() {
        masks["7"] = above1k()
        configure()
        processor.flush(queue.stream(0))
        val x = song(3 * rate)
        val out = play(x) + end()
        assertEquals("not a frame more or less", x.size, out.size)
        assertTrue(level(out, 2_000.0, rate / 10, rate) < 0.002)
        assertTrue(level(out, 2_000.0, 2 * rate, 3 * rate - rate / 10) < 0.002)
        assertEquals(0.3, level(out, 300.0, rate / 10, 3 * rate - rate / 10), 0.005)
    }

    @Test fun `each song goes by its own masks, at the moment it is at`() {
        val asked = ArrayList<Double>()
        masks["7"] = above1k(asked = asked)
        configure()
        processor.flush(queue.stream(0))
        val first = song(rate)
        val a = play(first) + end()
        assertTrue(level(a, 2_000.0, rate / 10, rate - rate / 10) < 0.002)
        // The next song has none: as it is, from its top.
        processor.flush(queue.stream(1))
        assertArrayEquals(first, play(first))
        // Back in the first, at its second minute: the masks are asked for that moment.
        asked.clear()
        processor.flush(queue.stream(0, positionUs = 60_000_000))
        play(first)
        end()
        assertTrue("asked ${asked.firstOrNull()}..${asked.lastOrNull()}", asked.first() in 59.9..60.06 && asked.last() in 60.9..61.1)
    }

    @Test fun `the voice goes and comes back while the song plays, without a gap`() {
        configure()
        processor.flush(queue.stream(0))
        val x = song(8 * rate)
        val out = ArrayList<Short>()
        out += play(x.copyOfRange(0, 2 * rate)).toList()
        assertEquals(2 * rate, out.size)
        // The microphone turned on (or this song's masks just arrived).
        masks["7"] = above1k()
        processor.refresh()
        out += play(x.copyOfRange(2 * rate, 5 * rate)).toList()
        val behind = 5 * rate - out.size
        assertTrue("runs $behind frames behind", behind in 1..rate / 5)
        // Turned off: the voice returns, then nothing runs behind anymore.
        masks["7"] = null
        processor.refresh()
        out += play(x.copyOfRange(5 * rate, 8 * rate)).toList()
        assertEquals(8 * rate, out.size)
        assertEquals(0, end().size)
        val y = out.toShortArray()
        assertArrayEquals(x.copyOfRange(0, 2 * rate - rate / 5), y.copyOfRange(0, 2 * rate - rate / 5))
        assertTrue(level(y, 2_000.0, 3 * rate, 5 * rate - rate / 10) < 0.002)
        assertEquals(0.3, level(y, 2_000.0, 6 * rate, 8 * rate - rate / 10), 0.005)
        // The band never moves, and where the voice is back the song is the song again, to the sample.
        for (start in rate until 7 * rate step rate / 4) assertEquals(0.3, level(y, 300.0, start, start + rate / 4), 0.01)
        for (i in 6 * rate + rate / 2 until 8 * rate) assertTrue(abs(y[i] - x[i]) <= 1)
    }

    @Test fun `parts not worked out yet keep their voice until they are`() {
        // The masks know the song from its 3rd second on.
        masks["7"] = above1k(fromSeconds = 3.0)
        configure()
        processor.flush(queue.stream(0))
        val x = song(6 * rate)
        val out = play(x) + end()
        assertEquals(x.size, out.size)
        assertEquals(0.3, level(out, 2_000.0, rate / 2, 2 * rate + rate / 2), 0.005)
        assertTrue(level(out, 2_000.0, 4 * rate, 6 * rate - rate / 10) < 0.002)
    }

    @Test fun `through the player's own sink, song after song`() {
        val written = ByteArrayOutputStream()
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            masks["7"] = above1k()
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
            val first = song(2 * rate)
            feed(0, first)
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).setTimeline(queue).setMediaPeriodId(MediaSource.MediaPeriodId(queue.uid(1))).build())
            sink.handleDiscontinuity()
            val second = song(2 * rate)
            feed(2_000_000, second)
            sink.playToEndOfStream()
            var tries = 0
            while (!sink.isEnded && tries++ < 200) sink.hasPendingData()
            val bytes = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.nativeOrder())
            val heard = ShortArray(bytes.remaining() / 2) { bytes.short }
            assertTrue("heard ${heard.size} frames", heard.size >= 3 * rate + rate / 2)
            // The first song without its voice, whole; the second as it is, from its very first frame.
            assertTrue(level(heard, 2_000.0, rate / 4, 2 * rate - rate / 4) < 0.002)
            assertEquals(0.3, level(heard, 300.0, rate / 4, 2 * rate - rate / 4), 0.005)
            assertArrayEquals(second.copyOfRange(0, rate), heard.copyOfRange(2 * rate, 3 * rate))
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }
}
