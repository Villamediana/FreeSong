package com.villamediana.sound.playback.crossfade

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.playback.crossfade.FadeShape.Role
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
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(UnstableApi::class)
class FadeShaperTest {
    private val rate = 48_000

    private fun shaper(): FadeShaper = FadeShaper().apply {
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata(0))
    }

    /**
     * [frames] stereo frames of [value] (frame index → sample) through [p]; the left channel out.
     * The highest pitch there is (+x, −x…) passes a low shelf untouched: it shows the volume alone.
     */
    private fun run(p: FadeShaper, frames: Int, value: (Int) -> Int = { if (it % 2 == 0) 10_000 else -10_000 }): ShortArray {
        val out = ShortArray(frames)
        var f = 0
        while (f < frames) {
            val n = minOf(960, frames - f)
            val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (k in 0 until n) repeat(2) { b.putShort(value(f + k).toShort()) }
            b.flip()
            p.queueInput(b)
            val o = p.output.order(ByteOrder.nativeOrder())
            for (k in 0 until n) {
                out[f + k] = o.short
                o.short
            }
            f += n
        }
        return out
    }

    private fun level(out: ShortArray, at: Int) = abs(out[at].toInt()) / 10_000f

    @Test fun plainAudioPassesUntouched() {
        val p = shaper()
        val wave = { k: Int -> (k * 37 % 20_000) - 10_000 }
        val out = run(p, rate, wave)
        assertArrayEquals(ShortArray(rate) { wave(it).toShort() }, out)
    }

    @Test fun theNextSongFadesInByItsOwnPosition() {
        val p = shaper()
        // Into the next song at 2.0 s, a 1 s fade from there.
        val shape = FadeShape(Role.IN, 2_000_000, 1_000_000, 0f)
        p.arm(shape)
        p.flush(AudioProcessor.StreamMetadata(2_000_000))
        val out = run(p, rate * 2)
        assertEquals(0f, level(out, 0), 0.01f)
        for (t in listOf(0.25f, 0.5f, 0.75f)) {
            val at = (t * rate).toInt()
            assertEquals("t=$t", sin(t * PI / 2).toFloat(), level(out, at), 0.01f)
        }
        // Past the fade: plain again, to the sample.
        assertEquals(10_000, abs(out[rate + 6_000].toInt()))
    }

    @Test fun theSinksSecondFlushKeepsTheFadeButALaterSeekCutsIt() {
        val p = shaper()
        p.arm(FadeShape(Role.IN, 0, 1_000_000, 0f))
        // The sink flushes twice for one seek, the second with the position, before any audio.
        p.flush(AudioProcessor.StreamMetadata(0))
        p.flush(AudioProcessor.StreamMetadata(0))
        assertEquals(sin(0.25 * PI / 2).toFloat(), level(run(p, rate / 4 + 1), rate / 4), 0.01f)
        // The listener seeks away mid-fade: plain from there.
        p.flush(AudioProcessor.StreamMetadata(30_000_000))
        val out = run(p, rate / 10)
        assertEquals(10_000, abs(out[100].toInt()))
    }

    @Test fun anArmedFadeWaitsForTheSeek() {
        val p = shaper()
        p.arm(FadeShape(Role.IN, 0, 1_000_000, 0f))
        // Audio of the song still going out, before the seek into the next: untouched.
        assertEquals(10_000, abs(run(p, 1_000)[500].toInt()))
    }

    @Test fun theTailHoldsItsFadeFromNowAndThroughItsSeeks() {
        val p = shaper()
        // The tail player sits at 100 s, where the song's fade starts.
        p.flush(AudioProcessor.StreamMetadata(100_000_000))
        p.hold(FadeShape(Role.OUT, 100_000_000, 1_000_000, 0f))
        val out = run(p, rate + rate / 2)
        assertEquals(1f, level(out, 0), 0.01f)
        assertEquals(kotlin.math.cos(0.5 * PI / 2).toFloat(), level(out, rate / 2), 0.01f)
        // Done: silent from the fade's end on.
        assertEquals(0f, level(out, rate + 1_000), 0.001f)
        // A re-seek of the tail keeps its fade, by position.
        p.flush(AudioProcessor.StreamMetadata(100_250_000))
        assertEquals(kotlin.math.cos(0.25 * PI / 2).toFloat(), level(run(p, 10), 0), 0.01f)
    }

    @Test fun theMatchHoldsTheLoudSongDown() {
        val p = shaper()
        p.arm(FadeShape(Role.IN, 0, 1_000_000, -12f))
        p.flush(AudioProcessor.StreamMetadata(0))
        val out = run(p, rate)
        val t = 0.5f
        val expected = sin(t * PI / 2).toFloat() * FadeShape.dbToGain(-12f * (1f - FadeShape.smooth(t)))
        assertEquals(expected, level(out, rate / 2), 0.01f)
    }

    @Test fun theBassIsHeldBackWhileItBelongsToTheOtherSong() {
        val p = shaper()
        // 60 Hz: all bass. The next song's first quarter keeps its bass at the floor.
        p.arm(FadeShape(Role.IN, 0, 4_000_000, 0f))
        p.flush(AudioProcessor.StreamMetadata(0))
        val bass = { k: Int -> (10_000 * sin(2 * PI * 60 * k / rate)).toInt() }
        val out = run(p, rate * 4, bass)
        fun peak(from: Int, to: Int) = (from until to).maxOf { abs(out[it].toInt()) } / 10_000f
        // Around 0.6 s (t = 0.15): volume sin(0.15·π/2) ≈ 0.23, bass ~−23 dB on top.
        val early = peak((0.55 * rate).toInt(), (0.65 * rate).toInt())
        assertTrue("early bass $early", early < 0.23f * 0.15f)
        // Around 3.6 s (t = 0.9): the bass is back, at the fade's volume.
        val late = peak((3.55 * rate).toInt(), (3.65 * rate).toInt())
        assertEquals(sin(0.9 * PI / 2).toFloat(), late, 0.05f)
    }

    @Test fun cutShortItGoesBackToPlainQuickly() {
        val p = shaper()
        p.arm(FadeShape(Role.IN, 0, 10_000_000, 0f))
        p.flush(AudioProcessor.StreamMetadata(0))
        run(p, rate / 10)
        p.release()
        val out = run(p, rate / 10)
        // Within the ramp, from the faded level up; past it, plain.
        assertTrue(level(out, 0) < 0.1f)
        assertEquals(10_000, abs(out[rate * FadeShaper.RELEASE_MS / 1_000 + 200].toInt()))
    }

    // ---------------------------------------------------------------- the handover at the fade's start

    /** A fade starting at 100 s. */
    private val at100 = Mix(5_000, 100_000, 0, 0f, 0f)

    private fun ms(ms: Int) = rate * ms / 1_000

    @Test fun theMainPlayerHandsTheSongOverAtTheFade() {
        val p = shaper()
        p.flush(AudioProcessor.StreamMetadata(99_000_000))
        p.leave(at100.leaving())
        val out = run(p, rate * 2)
        assertEquals(1f, level(out, rate - 100), 0.001f)
        assertEquals(FadeShape.handOut(20_000), level(out, rate + ms(20)), 0.02f)
        // Silent from the handover on, until it moves on.
        assertEquals(0f, level(out, rate + ms(41)), 0.001f)
        assertEquals(0f, level(out, rate * 2 - 1), 0.001f)
    }

    @Test fun theTailIsSilentUntilTheHandoverThenTakesTheSongAndFadesItOut() {
        val p = FadeShaper()
        p.hold(at100.outgoing())
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata(94_000_000))
        val out = run(p, rate * 7)
        assertEquals(0f, level(out, rate * 3), 0.001f)
        assertEquals(0f, level(out, rate * 6 - 100), 0.001f)
        assertEquals(FadeShape.handIn(20_000), level(out, rate * 6 + ms(20)), 0.02f)
        assertEquals(1f, level(out, rate * 6 + ms(60)), 0.01f)
        assertEquals(kotlin.math.cos(0.1 * PI / 2).toFloat(), level(out, rate * 6 + rate / 2), 0.01f)
    }

    @Test fun onTheSameMomentTheSongGoesOnWholeThroughTheHandover() {
        val main = shaper().apply { flush(AudioProcessor.StreamMetadata(99_000_000)); leave(at100.leaving()) }
        val tail = shaper().apply { hold(at100.outgoing()); flush(AudioProcessor.StreamMetadata(99_000_000)) }
        val wave = { k: Int -> (8_000 * sin(2 * PI * 440 * k / rate) + 4_000 * sin(2 * PI * 55 * k / rate)).toInt() }
        val a = run(main, rate * 3 / 2, wave)
        val b = run(tail, rate * 3 / 2, wave)
        // Before and after it, the song to the sample; along it, never under (a few dB over, for ms).
        for (k in ms(900) until ms(1_000)) assertEquals("at $k", wave(k).toFloat(), (a[k] + b[k]).toFloat(), 3f)
        for (k in ms(1_041) until ms(1_050)) assertEquals("at $k", wave(k).toFloat(), (a[k] + b[k]).toFloat(), 3f)
        for (k in ms(1_000) until ms(1_041)) {
            val g = FadeShape.handIn((k - rate) * 1_000_000L / rate) + FadeShape.handOut((k - rate) * 1_000_000L / rate)
            assertTrue("at $k: $g", g in 0.999f..1.415f)
            assertEquals("at $k", wave(k) * g, (a[k] + b[k]).toFloat(), 4f + abs(wave(k)) * 0.002f)
        }
    }

    @Test fun aFewMsApartNothingDropsOut() {
        // The players as close as TailSync leaves them: the tail 6 ms behind. Noise: no two parts alike.
        val behind = ms(6)
        val random = java.util.Random(7)
        val table = IntArray(rate * 3) { random.nextInt(16_384) - 8_192 }
        val noise = { k: Int -> table[k + behind] }
        val main = shaper().apply { flush(AudioProcessor.StreamMetadata(99_000_000)); leave(at100.leaving()) }
        val tail = shaper().apply { hold(at100.outgoing()); flush(AudioProcessor.StreamMetadata(99_000_000L - 6_000)) }
        val a = run(main, rate * 2, noise)
        val b = run(tail, rate * 2, { k -> noise(k - behind) })
        fun rms(x: (Int) -> Double, from: Int) = kotlin.math.sqrt((from until from + ms(10)).sumOf { x(it) * x(it) } / ms(10))
        val plain = rms({ noise(it).toDouble() }, ms(500))
        // Every 10 ms around the handover: never more than ~1.5 dB down where the two cross, never up.
        for (w in ms(900) until ms(1_150) step ms(10)) {
            val r = rms({ (a[it] + b[it]).toDouble() }, w) / plain
            assertTrue("at ${w * 1_000 / rate} ms: $r", r > 0.84 && r < 1.08)
        }
    }

    @Test fun leavingEndsAtTheNextSeekOrARelease() {
        val p = shaper()
        p.flush(AudioProcessor.StreamMetadata(99_000_000))
        p.leave(at100.leaving())
        run(p, rate * 2)
        // The listener seeks back: the song as it is.
        p.flush(AudioProcessor.StreamMetadata(50_000_000))
        assertEquals(1f, level(run(p, 100), 50), 0.001f)
        // Paused before the handover: the song as it is, past it too.
        val q = shaper()
        q.flush(AudioProcessor.StreamMetadata(99_000_000))
        q.leave(at100.leaving())
        run(q, rate / 2)
        q.release()
        assertEquals(1f, level(run(q, rate), rate * 3 / 4), 0.001f)
        // The next song armed after it: the leaving ends at that seek, the next one fades in.
        val r = shaper()
        r.flush(AudioProcessor.StreamMetadata(99_000_000))
        r.leave(at100.leaving())
        run(r, rate * 2)
        r.arm(FadeShape(Role.IN, 0, 1_000_000, 0f))
        r.flush(AudioProcessor.StreamMetadata(0))
        assertEquals(sin(0.5 * PI / 2).toFloat(), level(run(r, rate), rate / 2), 0.01f)
    }

    // ---------------------------------------------------------------- through ExoPlayer's real sink

    @Test fun theNextSongFadesInThroughTheRealSink() {
        val written = ByteArrayOutputStream()
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            val offset = 1_000_000_000_000L
            val shaper = FadeShaper()
            val sink = DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).setAudioProcessors(arrayOf<AudioProcessor>(shaper)).build()
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(2).setSampleRate(rate).build()
            sink.configure(format, 0, null)
            sink.setOutputStreamOffsetUs(offset)
            sink.play()
            // The next song comes in at 3.0 s of it (its silence skipped), fading over 0.4 s.
            shaper.arm(FadeShape(Role.IN, 3_000_000, 400_000, 0f))
            sink.flush()
            written.reset()
            sink.play()
            val startUs = 3_000_000L
            val chunk = rate / 50
            var f = 0
            while (f < rate) {
                val n = minOf(chunk, rate - f)
                val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
                for (k in 0 until n) repeat(2) { b.putShort(if ((f + k) % 2 == 0) 10_000 else -10_000) }
                b.flip()
                var tries = 0
                while (!sink.handleBuffer(b, offset + startUs + f * 1_000_000L / rate, 1)) if (++tries > 50) break
                if (tries > 50) break
                f += n
            }
            val bytes = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.nativeOrder())
            val left = ShortArray(bytes.remaining() / 4) { bytes.short.also { bytes.short } }
            assertTrue("written ${left.size}", left.size > rate / 2)
            // The sink places the audio at 3.0 s: halfway through the fade at 3.2 s.
            val half = (0.2 * rate).toInt()
            assertEquals(sin(0.5 * PI / 2).toFloat(), abs(left[half].toInt()) / 10_000f, 0.02f)
            assertEquals(10_000, abs(left[(0.45 * rate).toInt()].toInt()))
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }
}
