package com.villamediana.sound.playback

import com.villamediana.sound.playback.CrossfadePlan.Step
import com.villamediana.sound.playback.crossfade.Mix
import com.villamediana.sound.playback.crossfade.Mixing
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadePlanTest {
    private val duration = 200_000L
    private val plain = Mixing.plain(5_000, duration)

    /** Measured: 4 s of silence at the end, the next song 3 dB louder and starting after 0.8 s. */
    private val measured = Mix(5_000, duration - 4_000 - 5_000, 800, 0f, -3f)

    private fun step(
        seconds: Int = 5,
        duration: Long = this.duration,
        position: Long,
        playing: Boolean = true,
        hasNext: Boolean = true,
        repeatOne: Boolean = false,
        blocked: Boolean = false,
        prepared: Mix? = null,
        measuring: Boolean = true,
        mix: Mix? = null,
    ) = CrossfadePlan.step(seconds, duration, position, playing, hasNext, repeatOne, blocked, prepared, measuring, mix)

    @Test fun measuresAheadThenReadiesTheTailThenStartsAtTheFade() {
        assertEquals(Step.Idle, step(position = 100_000, measuring = false))
        // 45 s before the fade, the end of the song and the start of the next are measured.
        assertEquals(Step.Measure, step(position = 150_000, measuring = false))
        assertEquals(Step.Idle, step(position = 150_000))
        // Measured: ready 6 s before its fade (which comes earlier: the silence at the end is left out).
        assertEquals(Step.Idle, step(position = 184_000, mix = measured))
        assertEquals(Step.Prepare(measured), step(position = 185_500, mix = measured))
        assertEquals(Step.Idle, step(position = 190_500, prepared = measured, mix = measured))
        assertEquals(Step.Start(), step(position = 191_000, prepared = measured, mix = measured))
        // A seek into the fade's second half: no fade, the song just ends.
        assertEquals(Step.Idle, step(position = 194_000, prepared = measured, mix = measured))
    }

    @Test fun aMeasureThatDoesntComeInTimeFadesAsTheFileEnds() {
        assertEquals(Step.Idle, step(position = 188_000))
        assertEquals(Step.Prepare(plain), step(position = 189_500))
        assertEquals(Step.Start(), step(position = 195_000, prepared = plain))
    }

    @Test fun notReadiedInTimeNoFade() {
        // A seek to the very end: its moment is gone.
        assertEquals(Step.Idle, step(position = 196_000, mix = plain))
        assertEquals(Step.Idle, step(position = 192_000, mix = measured))
    }

    @Test fun staysOutOfTheWay() {
        val end = 195_500L
        assertEquals(Step.Idle, step(seconds = 0, position = end, prepared = plain))
        assertEquals(Step.Idle, step(position = end, prepared = plain, playing = false))
        assertEquals(Step.Idle, step(position = end, prepared = plain, hasNext = false))
        assertEquals(Step.Idle, step(position = end, prepared = plain, blocked = true))
        assertEquals(Step.Idle, step(seconds = 0, position = 150_000, measuring = false))
        // A short track (jingle, intro) plays whole.
        assertEquals(Step.Idle, step(duration = 12_000, position = 9_000, prepared = Mixing.plain(5_000, 12_000)))
        assertEquals(Step.Idle, step(duration = 12_000, position = 1_000, measuring = false))
    }

    @Test fun repeatOneFadesIntoItsOwnStart() {
        // The song follows itself: it fades even as the last one in the queue, back to the top.
        assertEquals(Step.Measure, step(position = 150_000, repeatOne = true, hasNext = false, measuring = false))
        assertEquals(Step.Prepare(plain), step(position = 190_000, repeatOne = true, hasNext = false, mix = plain))
        assertEquals(Step.Start(loop = true), step(position = 196_000, repeatOne = true, hasNext = false, prepared = plain))
        assertEquals(Step.Start(loop = true), step(position = 196_000, repeatOne = true, prepared = plain))
        // Without "repeat one" it moves on to the next song, as always.
        assertEquals(Step.Start(loop = false), step(position = 196_000, prepared = plain))
        // The rest still holds: off, paused, sleep timer, a short track.
        assertEquals(Step.Idle, step(seconds = 0, position = 196_000, repeatOne = true, prepared = plain))
        assertEquals(Step.Idle, step(position = 196_000, repeatOne = true, prepared = plain, playing = false))
        assertEquals(Step.Idle, step(position = 196_000, repeatOne = true, prepared = plain, blocked = true))
        assertEquals(Step.Idle, step(duration = 12_000, position = 9_000, repeatOne = true, prepared = Mixing.plain(5_000, 12_000)))
    }
}
