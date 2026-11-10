package com.villamediana.sound.playback.crossfade

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.villamediana.sound.playback.CrossfadePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The whole crossfade on two made-up songs, as the app runs it: measured, mixed, then each song
 * through its own [FadeShaper] (the tail player's and the main player's), the two summed as the
 * phone's output would. What comes out is measured again.
 */
@OptIn(UnstableApi::class)
class CrossfadeEndToEndTest {
    private val rate = 48_000
    private val fadeMs = 6_000L

    /** A quiet song (~−31 LUFS) of 30 s whose last 4 s are silence. */
    private val quietDuration = 30_000L
    private fun quiet(frame: Long): Double {
        val sec = frame.toDouble() / rate
        return if (sec >= 26.0) 0.0 else 0.03 * sin(2 * PI * 440 * sec)
    }

    /** A loud song (~−7 LUFS) with bass, after 1 s of silence. */
    private fun loud(frame: Long): Double {
        val sec = frame.toDouble() / rate
        return if (sec < 1.0) 0.0 else 0.45 * sin(2 * PI * 660 * sec) + 0.3 * sin(2 * PI * 55 * sec)
    }

    private fun pcm(song: (Long) -> Double, fromFrame: Long, frames: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (k in 0 until frames) {
            val v = (song(fromFrame + k) * 32767).toInt().toShort()
            b.putShort(v)
            b.putShort(v)
        }
        b.flip()
        return b
    }

    private fun envelope(song: (Long) -> Double, fromMs: Long, toMs: Long): Envelope {
        val meter = LoudnessMeter(rate, 2, fromMs * 1_000, toMs * 1_000)
        var f = fromMs * rate / 1_000
        while (f < toMs * rate / 1_000) {
            meter.feed(pcm(song, f, 960), f * 1_000_000 / rate, float = false)
            f += 960
        }
        return meter.envelope()!!
    }

    /** [song] from [fromMs] for [frames], through [shaper] (null: as it is); the left channel, as floats. */
    private fun play(song: (Long) -> Double, fromMs: Long, frames: Int, shaper: FadeShaper?): FloatArray {
        val out = FloatArray(frames)
        var f = 0
        val start = fromMs * rate / 1_000
        while (f < frames) {
            val n = minOf(960, frames - f)
            var b = pcm(song, start + f, n)
            if (shaper != null) {
                shaper.queueInput(b)
                b = shaper.output.order(ByteOrder.nativeOrder())
            }
            for (k in 0 until n) {
                out[f + k] = b.short / 32768f
                b.short
            }
            f += n
        }
        return out
    }

    private fun shaper(atMs: Long) = FadeShaper().apply {
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata(atMs * 1_000))
    }

    @Test fun aQuietSongIntoALoudOne() {
        val mix = Mixing.mix(
            fadeMs,
            quietDuration,
            envelope(::quiet, maxOf(0, quietDuration - Mixing.TAIL_WINDOW_MS), quietDuration),
            envelope(::loud, 0, Mixing.HEAD_WINDOW_MS),
        )
        // The 4 s of silence and the 1 s before the loud one are left out; the loud one comes in held down.
        assertEquals(26_000 + Mixing.TAIL_MARGIN_MS - fadeMs, mix.fadeStartMs)
        assertEquals(1_000 - Mixing.LEAD_MARGIN_MS, mix.inStartMs)
        assertTrue("match ${mix.inMatchDb}", mix.inMatchDb < -18f + 0.01f)

        // 3 s of the quiet song before the fade, as the main player plays it, handing it over at the
        // fade's start to the tail, which rolls from 3 s before, silent until then…
        val handOn = (CrossfadePlan.START_AFTER_MS * rate / 1_000).toInt()
        val main = shaper(mix.fadeStartMs - 3_000).apply { leave(mix.leaving()) }
        val before = play(::quiet, mix.fadeStartMs - 3_000, rate * 3 + handOn, main)
        val fadeFrames = (fadeMs * rate / 1_000).toInt()
        val after = rate * 3
        val tail = FadeShaper().apply {
            hold(mix.outgoing())
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            flush(AudioProcessor.StreamMetadata((mix.fadeStartMs - 3_000) * 1_000))
        }
        val out = play(::quiet, mix.fadeStartMs - 3_000, rate * 3 + fadeFrames + after, tail)
        // …then the main player moves on into the next song.
        main.arm(mix.incoming())
        main.flush(AudioProcessor.StreamMetadata(mix.inStartMs * 1_000))
        val inc = play(::loud, mix.inStartMs, fadeFrames + after - handOn, main)
        val mainOut = before + inc
        val heard = FloatArray(out.size) { out[it] + mainOut[it] }

        // What the ear gets, in 400 ms windows every 100 ms.
        val meter = LoudnessMeter(rate, 1, 0, Long.MAX_VALUE)
        val bytes = ByteBuffer.allocate(heard.size * 4).order(ByteOrder.nativeOrder())
        heard.forEach { bytes.putFloat(it) }
        bytes.flip()
        meter.feed(bytes, 0, float = true)
        val env = meter.envelope()!!
        fun at(ms: Long) = env.loudness(ms * 1_000, (ms + 400) * 1_000)!!
        val quietLevel = at(1_000)
        val loudLevel = at(3_000 + fadeMs + 1_500)
        assertTrue("levels $quietLevel → $loudLevel", loudLevel - quietLevel > 20)

        // No step anywhere: the level moves less than 1.5 dB from one 100 ms to the next…
        var last = at(0)
        for (ms in 100L..(3_000 + fadeMs + 2_000) step 100) {
            val level = at(ms)
            assertTrue("at $ms ms: $last → $level", abs(level - last) < 1.5f)
            last = level
        }
        // …a fifth into the fade the pair is still close to the quiet song (a plain fade has the loud
        // one ~15 dB over it by then)…
        assertTrue(at(3_000 + fadeMs / 5 - 200) < quietLevel + 4)
        // …and the loud one gets to its own level only as the fade ends, the time the listener set.
        assertTrue(at(3_000 + fadeMs * 7 / 10 - 200) < loudLevel - 3)
        assertEquals(loudLevel, at(3_000 + fadeMs), 1f)
    }
}
