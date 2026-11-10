package com.villamediana.sound.playback.crossfade

import com.villamediana.sound.playback.crossfade.FadeShape.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class MixingTest {
    private val fade = 6_000L
    private val duration = 200_000L

    /** An envelope from [startMs]: each part is its length (ms) and loudness (LUFS; null = silence). */
    private fun env(startMs: Long, vararg parts: Pair<Long, Float?>): Envelope {
        val blocks = ArrayList<Float>()
        for ((ms, lufs) in parts) repeat((ms / 100).toInt()) { blocks += if (lufs == null) 0f else Envelope.powerOf(lufs).toFloat() }
        return Envelope(startMs * 1_000, blocks.toFloatArray())
    }

    /** The end of the song: the last [Mixing.TAIL_WINDOW_MS] at [lufs], then [silenceMs] of silence. */
    private fun end(lufs: Float, silenceMs: Long = 0) =
        env(duration - Mixing.TAIL_WINDOW_MS, (Mixing.TAIL_WINDOW_MS - silenceMs) to lufs, silenceMs to null)

    private fun start(lufs: Float, silenceMs: Long = 0) = env(0, silenceMs to null, (Mixing.HEAD_WINDOW_MS - silenceMs) to lufs)

    @Test fun aQuietSongIntoALoudOneBringsTheLoudOneInAtTheQuietLevel() {
        val mix = Mixing.mix(fade, duration, end(-24f), start(-9f))
        assertEquals(duration - fade, mix.fadeStartMs)
        assertEquals(0L, mix.inStartMs)
        assertEquals(-15f, mix.inMatchDb, 0.3f)
        assertEquals(0f, mix.outMatchDb)
    }

    @Test fun aLoudSongIntoAQuietOneComesDownToIt() {
        val mix = Mixing.mix(fade, duration, end(-8f), start(-20f))
        assertEquals(-12f, mix.outMatchDb, 0.3f)
        assertEquals(0f, mix.inMatchDb)
    }

    @Test fun songsAtTheSameLevelAreLeftAlone() {
        val mix = Mixing.mix(fade, duration, end(-10f), start(-10f))
        assertEquals(0f, mix.outMatchDb, 0.3f)
        assertEquals(0f, mix.inMatchDb, 0.3f)
    }

    @Test fun theMatchHasALimit() {
        val mix = Mixing.mix(fade, duration, end(-38f), start(-6f))
        assertEquals(-Mixing.MAX_MATCH_DB, mix.inMatchDb)
    }

    @Test fun silenceAtTheEndIsLeftOutOfTheFade() {
        val mix = Mixing.mix(fade, duration, end(-10f, silenceMs = 7_000), start(-10f))
        assertEquals(duration - 7_000 + Mixing.TAIL_MARGIN_MS - fade, mix.fadeStartMs)
        // At most 20 s of it.
        val long = Mixing.mix(fade, duration, end(-10f, silenceMs = 30_000), start(-10f))
        assertEquals(duration - Mixing.MAX_TAIL_SILENCE_MS - fade, long.fadeStartMs)
    }

    @Test fun silenceAtTheStartOfTheNextIsSkipped() {
        val mix = Mixing.mix(fade, duration, end(-10f), start(-10f, silenceMs = 1_500))
        assertEquals(1_500 - Mixing.LEAD_MARGIN_MS, mix.inStartMs)
        val long = Mixing.mix(fade, duration, end(-10f), start(-10f, silenceMs = 12_000))
        assertEquals(Mixing.MAX_LEAD_SILENCE_MS, long.inStartMs)
    }

    @Test fun aQuietEndingIsNotSilence() {
        // −40 LUFS in a −40 LUFS song is its music, not silence (silence is 25 LU below it).
        val quiet = env(duration - Mixing.TAIL_WINDOW_MS, (Mixing.TAIL_WINDOW_MS - 5_000) to -40f, 5_000L to -52f)
        assertEquals(duration - fade, Mixing.mix(fade, duration, quiet, null).fadeStartMs)
    }

    @Test fun aMeasureThatStoppedShortIsNotTrusted() {
        // It ends 10 s before the song does (a read error): its "silence" is just unknown.
        val short = env(duration - Mixing.TAIL_WINDOW_MS, 20_000L to -10f)
        assertEquals(Mixing.plain(fade, duration), Mixing.mix(fade, duration, short, null))
        val allSilent = env(duration - Mixing.TAIL_WINDOW_MS, Mixing.TAIL_WINDOW_MS to null)
        assertEquals(Mixing.plain(fade, duration), Mixing.mix(fade, duration, allSilent, env(0, 5_000L to null)))
        assertEquals(Mixing.plain(fade, duration), Mixing.mix(fade, duration, null, null))
    }

    @Test fun theNextSongChangedOnlyTheEndHolds() {
        val mix = Mixing.mix(fade, duration, end(-24f, silenceMs = 3_000), start(-9f, silenceMs = 2_000))
        val alone = mix.withoutIncoming()
        assertEquals(mix.fadeStartMs, alone.fadeStartMs)
        assertEquals(0L, alone.inStartMs)
        assertEquals(0f, alone.inMatchDb)
        assertEquals(0f, alone.outMatchDb)
    }

    // ---------------------------------------------------------------- the shape of the fade

    private fun db(gain: Float) = 20 * log10(gain.toDouble())

    /** How loud the two together are, songs at [outLufs] and [inLufs], at progress [t]. */
    private fun together(mix: Mix, t: Float, outLufs: Float, inLufs: Float): Double {
        val o = mix.outgoing()
        val i = mix.incoming()
        val outGain = o.gain(o.startUs + (t * o.lengthUs).toLong())
        val inGain = i.gain(i.startUs + (t * i.lengthUs).toLong())
        val power = 10.0.pow((outLufs + db(outGain)) / 10) * (if (outGain > 0) 1 else 0) +
            10.0.pow((inLufs + db(inGain)) / 10) * (if (inGain > 0) 1 else 0)
        return 10 * log10(power)
    }

    @Test fun equalPowerWhenTheSongsMatch() {
        val mix = Mix(fade, 100_000, 0, 0f, 0f)
        val o = mix.outgoing()
        val i = mix.incoming()
        for (t in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            val og = o.gain(o.startUs + (t * o.lengthUs).toLong())
            val ig = i.gain(i.startUs + (t * i.lengthUs).toLong())
            assertEquals("t=$t", 1f, og * og + ig * ig, 1e-3f)
        }
        assertEquals(1f, o.gain(o.startUs - 1))
        assertEquals(0f, o.gain(o.startUs + o.lengthUs))
        assertEquals(0f, i.gain(i.startUs - 1))
        assertEquals(1f, i.gain(i.startUs + i.lengthUs))
    }

    @Test fun fromQuietToLoudTheLevelRisesSmoothlyInsteadOfJumping() {
        val matched = Mixing.mix(fade, duration, end(-24f), start(-9f))
        val plain = Mixing.plain(fade, duration)
        // Without the match the loud song has taken over a quarter of the way in…
        assertTrue(together(plain, 0.25f, -24f, -9f) > -24 + 6)
        // …with it, the pair is still near the quiet song's level, and rises steadily to the loud one.
        assertTrue(together(matched, 0.25f, -24f, -9f) < -24 + 4)
        var last = Double.NEGATIVE_INFINITY
        for (k in 1..19) {
            val level = together(matched, k / 20f, -24f, -9f)
            assertTrue("step $k: $level after $last", level >= last - 0.5)
            assertTrue(level <= -9 + 0.5)
            last = level
        }
        assertEquals(-9.0, together(matched, 1f, -24f, -9f), 0.01)
    }

    @Test fun fromLoudToQuietTheLevelComesDownSmoothly() {
        val matched = Mixing.mix(fade, duration, end(-8f), start(-20f))
        var last = Double.POSITIVE_INFINITY
        for (k in 1..19) {
            val level = together(matched, k / 20f, -8f, -20f)
            assertTrue("step $k: $level after $last", level <= last + 0.5)
            last = level
        }
        // Halfway it's already well below the loud song (no loud tail over the quiet start).
        assertTrue(together(matched, 0.5f, -8f, -20f) < -8 - 4)
    }

    @Test fun theBassChangesHandsInTheMiddle() {
        val mix = Mix(fade, 100_000, 0, 0f, 0f)
        val o = mix.outgoing()
        val i = mix.incoming()
        fun at(s: FadeShape, t: Float) = s.bassDb(s.startUs + (t * s.lengthUs).toLong())
        assertEquals(0f, at(o, 0.1f), 1e-3f)
        assertEquals(FadeShape.BASS_FLOOR_DB, at(i, 0.1f))
        assertEquals(-3f, at(o, 0.5f), 0.1f)
        assertEquals(-3f, at(i, 0.5f), 0.1f)
        assertEquals(FadeShape.BASS_FLOOR_DB, at(o, 0.9f))
        assertEquals(0f, at(i, 0.9f), 1e-3f)
        // It moves steadily, never in a jump.
        for (k in 0 until 100) {
            assertTrue(abs(at(i, (k + 1) / 100f) - at(i, k / 100f)) < 3f)
        }
    }
}
