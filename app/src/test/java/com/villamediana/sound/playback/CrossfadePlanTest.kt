package com.villamediana.sound.playback

import com.villamediana.sound.playback.CrossfadePlan.Ready
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
        ready: Ready? = null,
        measuring: Boolean = true,
        mix: Mix? = null,
    ) = CrossfadePlan.step(seconds, duration, position, playing, hasNext, repeatOne, blocked, ready, measuring, mix)

    private fun waiting(mix: Mix) = Ready(mix, mix.fadeStartMs - CrossfadePlan.ROLL_AHEAD_MS, rolling = false)

    private fun rolling(mix: Mix) = Ready(mix, mix.fadeStartMs - CrossfadePlan.ROLL_AHEAD_MS, rolling = true)

    @Test fun measuresThenReadiesThenRollsTheTailThenMovesOnPastTheHandover() {
        assertEquals(Step.Idle, step(position = 100_000, measuring = false))
        // 45 s before the fade, the end of the song and the start of the next are measured.
        assertEquals(Step.Measure, step(position = 150_000, measuring = false))
        assertEquals(Step.Idle, step(position = 150_000))
        // Measured: readied 8 s before its fade (which comes earlier: the silence at the end is left out).
        assertEquals(Step.Idle, step(position = 182_000, mix = measured))
        assertEquals(Step.Prepare(measured), step(position = 183_500, mix = measured))
        // The tail waits 6 s before the fade and is started a moment ahead of it, silent.
        assertEquals(185_000, CrossfadePlan.tailFromMs(measured, 183_500))
        assertEquals(Step.Idle, step(position = 184_800, ready = waiting(measured), mix = measured))
        assertEquals(Step.Roll, step(position = 184_950, ready = waiting(measured), mix = measured))
        // Rolling: the main player keeps the song until the handover is behind it, then moves on.
        assertEquals(Step.Idle, step(position = 191_000, ready = rolling(measured), mix = measured))
        assertEquals(Step.Start(), step(position = 191_000 + CrossfadePlan.START_AFTER_MS, ready = rolling(measured), mix = measured))
        // A busy thread that comes late still moves on (the main player is silent by then).
        assertEquals(Step.Start(), step(position = 194_000, ready = rolling(measured), mix = measured))
    }

    @Test fun aSongComeInLateRollsAsSoonAsItCan() {
        // A seek to 3 s before the fade: the tail waits a little ahead of the song, rolls from there.
        assertEquals(Step.Prepare(measured), step(position = 188_000, mix = measured))
        assertEquals(188_600, CrossfadePlan.tailFromMs(measured, 188_000))
        assertEquals(190_500, CrossfadePlan.tailFromMs(measured, 190_400))
        val late = Ready(measured, 188_600, rolling = false)
        assertEquals(Step.Roll, step(position = 188_550, ready = late, mix = measured))
    }

    @Test fun tooCloseToTheFadeNoFade() {
        // Paused (or sought) right at the handover: no time to start the tail, the song just ends.
        assertEquals(Step.Idle, step(position = 190_600, ready = waiting(measured), mix = measured))
        assertEquals(Step.Idle, step(position = 192_000, ready = waiting(measured), mix = measured))
        assertEquals(Step.Idle, step(position = 190_600, mix = measured))
    }

    @Test fun aMeasureThatDoesntComeInTimeFadesAsTheFileEnds() {
        assertEquals(Step.Idle, step(position = 186_500))
        assertEquals(Step.Prepare(plain), step(position = 187_500))
        assertEquals(Step.Roll, step(position = 189_000, ready = waiting(plain)))
        assertEquals(Step.Start(), step(position = 195_100, ready = rolling(plain)))
    }

    @Test fun notReadiedInTimeNoFade() {
        // A seek to the very end: its moment is gone.
        assertEquals(Step.Idle, step(position = 194_600, mix = plain))
        assertEquals(Step.Idle, step(position = 196_000, mix = plain))
    }

    @Test fun aRollThatShouldntGoOnIsLetGo() {
        val at = 188_000L
        assertEquals(Step.Cancel, step(seconds = 0, position = at, ready = rolling(measured)))
        assertEquals(Step.Cancel, step(position = at, ready = rolling(measured), blocked = true))
        assertEquals(Step.Cancel, step(position = at, ready = rolling(measured), hasNext = false))
        // Paused: the player's own pause stops the roll; the plan just waits.
        assertEquals(Step.Idle, step(position = at, ready = rolling(measured), playing = false))
        // A tail only waiting is no trouble.
        assertEquals(Step.Idle, step(seconds = 0, position = at, ready = waiting(measured)))
    }

    @Test fun staysOutOfTheWay() {
        val roll = 189_000L
        assertEquals(Step.Idle, step(seconds = 0, position = roll, ready = waiting(plain)))
        assertEquals(Step.Idle, step(position = roll, ready = waiting(plain), playing = false))
        assertEquals(Step.Idle, step(position = roll, ready = waiting(plain), hasNext = false))
        assertEquals(Step.Idle, step(position = roll, ready = waiting(plain), blocked = true))
        assertEquals(Step.Idle, step(seconds = 0, position = 150_000, measuring = false))
        // A short track (jingle, intro) plays whole.
        val short = Mixing.plain(5_000, 12_000)
        assertEquals(Step.Idle, step(duration = 12_000, position = 6_000, ready = waiting(short)))
        assertEquals(Step.Idle, step(duration = 12_000, position = 1_000, measuring = false))
    }

    @Test fun repeatOneFadesIntoItsOwnStart() {
        // The song follows itself: it fades even as the last one in the queue, back to the top.
        assertEquals(Step.Measure, step(position = 150_000, repeatOne = true, hasNext = false, measuring = false))
        assertEquals(Step.Prepare(plain), step(position = 188_000, repeatOne = true, hasNext = false, mix = plain))
        assertEquals(Step.Roll, step(position = 189_000, repeatOne = true, hasNext = false, ready = waiting(plain)))
        assertEquals(Step.Start(loop = true), step(position = 195_100, repeatOne = true, hasNext = false, ready = rolling(plain)))
        assertEquals(Step.Start(loop = true), step(position = 195_100, repeatOne = true, ready = rolling(plain)))
        // Without "repeat one" it moves on to the next song, as always.
        assertEquals(Step.Start(loop = false), step(position = 195_100, ready = rolling(plain)))
        // The rest still holds: off, sleep timer, a short track.
        assertEquals(Step.Cancel, step(seconds = 0, position = 195_100, repeatOne = true, ready = rolling(plain)))
        assertEquals(Step.Cancel, step(position = 195_100, repeatOne = true, ready = rolling(plain), blocked = true))
        assertEquals(Step.Idle, step(duration = 12_000, position = 6_000, repeatOne = true, ready = waiting(Mixing.plain(5_000, 12_000))))
    }
}
