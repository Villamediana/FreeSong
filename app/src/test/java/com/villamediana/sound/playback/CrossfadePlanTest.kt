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

    private fun prepare(mix: Mix) = Step.Prepare(mix, mix.fadeStartMs - CrossfadePlan.ROLL_AHEAD_MS)

    private fun rolling(mix: Mix) = Ready(mix, mix.fadeStartMs - CrossfadePlan.ROLL_AHEAD_MS, rolling = true)

    @Test fun measuresThenReadiesThenRollsTheTailThenMovesOnPastTheHandover() {
        assertEquals(Step.Idle, step(position = 100_000, measuring = false))
        // 45 s before the fade, the end of the song and the start of the next are measured.
        assertEquals(Step.Measure, step(position = 150_000, measuring = false))
        assertEquals(Step.Idle, step(position = 150_000))
        // Measured: readied 8 s before its fade (which comes earlier: the silence at the end is left out).
        assertEquals(Step.Idle, step(position = 182_000, mix = measured))
        // The tail waits 6 s before the fade and is started a moment ahead of it, silent.
        assertEquals(Step.Prepare(measured, 185_000), step(position = 183_500, mix = measured))
        assertEquals(Step.Idle, step(position = 184_800, ready = waiting(measured), mix = measured))
        assertEquals(Step.Roll(measured), step(position = 184_950, ready = waiting(measured), mix = measured))
        // A look that comes a little late changes nothing.
        assertEquals(Step.Roll(measured), step(position = 185_300, ready = waiting(measured), mix = measured))
        // Rolling: the main player keeps the song until the handover is behind it, then moves on.
        assertEquals(Step.Idle, step(position = 191_000, ready = rolling(measured), mix = measured))
        assertEquals(Step.Start(), step(position = 191_000 + CrossfadePlan.START_AFTER_MS, ready = rolling(measured), mix = measured))
        // A busy thread that comes late still moves on (the main player is silent by then).
        assertEquals(Step.Start(), step(position = 194_000, ready = rolling(measured), mix = measured))
    }

    @Test fun aTailThatComesLateStillRollsWholeAndTheFadeStartsLater() {
        // The measure of a song never heard came 3 s before its fade (a seek near the end): the tail
        // waits a second ahead of the song, rolls its whole 6 s, and the fade starts then, ending
        // where it would (196 s). So late that 6 s of roll would leave 1 s of fade: it keeps 2 s.
        val fitted = Mix(2_000, 194_000, 800, 0f, -3f)
        assertEquals(Step.Prepare(measured, 189_000, fitted), step(position = 188_000, mix = measured))
        val late = Ready(measured, 189_000, rolling = false, mix = fitted)
        assertEquals(Step.Idle, step(position = 188_500, ready = late, mix = measured))
        assertEquals(Step.Roll(fitted), step(position = 188_900, ready = late, mix = measured))
        val rolling = Ready(measured, 189_000, rolling = true, mix = fitted)
        assertEquals(Step.Idle, step(position = 191_100, ready = rolling, mix = measured))
        assertEquals(Step.Start(), step(position = 194_000 + CrossfadePlan.START_AFTER_MS, ready = rolling, mix = measured))
    }

    @Test fun aLittleLateTheFadeIsOnlyALittleShorter() {
        // 5 s before the fade: readied a second ahead, the fade starts 2 s later than measured.
        val base = Mix(7_000, 180_000, 800, 0f, -3f)
        val fitted = base.copy(fadeStartMs = 182_000, fadeMs = 5_000)
        assertEquals(Step.Prepare(base, 176_000, fitted), step(seconds = 7, position = 175_000, mix = base))
        assertEquals(Step.Roll(fitted.copy(fadeStartMs = 181_900, fadeMs = 5_100)), step(seconds = 7, position = 175_900, ready = Ready(base, 176_000, false, fitted), mix = base))
        // Paused while it rolled and played again 3 s before the fade: a whole roll again.
        assertEquals(Step.Roll(base.copy(fadeStartMs = 183_000, fadeMs = 4_000)), step(seconds = 7, position = 177_000, ready = Ready(base, 177_000, false), mix = base))
        // Not late: as measured.
        assertEquals(base, CrossfadePlan.fit(base, 174_000))
        assertEquals(base, CrossfadePlan.fit(base, 174_400))
    }

    @Test fun tooCloseToTheEndNoFade() {
        // Paused (or sought) where not even the shortest fade has time to start: the song just ends.
        assertEquals(Step.Idle, step(position = 193_600, ready = Ready(measured, 193_600, false), mix = measured))
        assertEquals(Step.Idle, step(position = 195_000, ready = Ready(measured, 195_000, false), mix = measured))
        assertEquals(Step.Idle, step(position = 193_000, mix = measured))
    }

    @Test fun aMeasureThatDoesntComeInTimeFadesAsTheFileEnds() {
        // Waited for until the last moment for a whole roll.
        assertEquals(Step.Idle, step(position = 187_500))
        assertEquals(Step.Prepare(plain, 189_250, plain), step(position = 188_250))
        assertEquals(Step.Roll(plain), step(position = 189_200, ready = Ready(plain, 189_250, false)))
        assertEquals(Step.Start(), step(position = 195_100, ready = rolling(plain)))
    }

    @Test fun notReadiedInTimeNoFade() {
        // A seek to the very end: its moment is gone.
        assertEquals(Step.Idle, step(position = 197_000, mix = plain))
        assertEquals(Step.Idle, step(position = 199_800, mix = plain))
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
        assertEquals(prepare(plain), step(position = 188_000, repeatOne = true, hasNext = false, mix = plain))
        assertEquals(Step.Roll(plain), step(position = 189_000, repeatOne = true, hasNext = false, ready = waiting(plain)))
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
