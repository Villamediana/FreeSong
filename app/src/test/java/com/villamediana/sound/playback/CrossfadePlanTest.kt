package com.villamediana.sound.playback

import com.villamediana.sound.playback.CrossfadePlan.Look
import com.villamediana.sound.playback.CrossfadePlan.Step
import com.villamediana.sound.playback.CrossfadePlan.Tail
import com.villamediana.sound.playback.CrossfadePlan.Verdict
import com.villamediana.sound.playback.crossfade.Mix
import com.villamediana.sound.playback.crossfade.Mixing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        measuring: Boolean = true,
        mix: Mix? = null,
        tail: Tail? = null,
    ) = CrossfadePlan.step(Look(seconds, duration, position, playing, hasNext, repeatOne, blocked, measuring, mix, tail))

    private fun waiting(from: Long, applied: Mix? = null) = Tail(from, rolling = false, applied = applied)

    private fun rolling(applied: Mix? = null, from: Long = 0) = Tail(from, rolling = true, applied = applied)

    @Test fun measuresThenRollsTheTailThenSetsTheFadeThenMovesOnPastTheHandover() {
        assertEquals(Step.Idle, step(position = 100_000, measuring = false))
        // 45 s before the fade, the end of the song and the start of the next are measured.
        assertEquals(Step.Measure, step(position = 150_000, measuring = false))
        assertEquals(Step.Idle, step(position = 150_000))
        // Measured: the tail is readied 10 s before its fade, to wait 6 s before it.
        assertEquals(Step.Idle, step(position = 180_000, mix = measured))
        assertEquals(Step.Prepare(185_000), step(position = 181_500, mix = measured))
        assertEquals(Step.Idle, step(position = 184_800, mix = measured, tail = waiting(185_000)))
        assertEquals(Step.Roll, step(position = 184_950, mix = measured, tail = waiting(185_000)))
        // Rolling: the fade is set on both players, then the main player keeps the song until the
        // handover is behind it and moves on.
        assertEquals(Step.Apply(measured), step(position = 185_000, mix = measured, tail = rolling()))
        assertEquals(Step.Idle, step(position = 191_000, mix = measured, tail = rolling(measured)))
        assertEquals(Step.Start(), step(position = 191_000 + CrossfadePlan.START_AFTER_MS, mix = measured, tail = rolling(measured)))
        // A busy thread that comes late still moves on (the main player is silent by then).
        assertEquals(Step.Start(), step(position = 194_000, mix = measured, tail = rolling(measured)))
    }

    @Test fun theTailDoesntWaitForAMeasureStillOnItsWay() {
        // A seek to 20 s before the end of a song never heard: the measure takes its time, the tail
        // starts right away (silent) aimed at the earliest the fade could be, and gets in step.
        assertEquals(Step.Measure, step(position = 180_000, measuring = false))
        assertEquals(Step.Prepare(180_300), step(position = 180_000))
        assertEquals(Step.Roll, step(position = 180_250, tail = waiting(180_300)))
        assertEquals(Step.Idle, step(position = 182_000, tail = rolling()))
        // The measure comes in: set at once, its fade still well ahead.
        assertEquals(Step.Apply(measured), step(position = 184_000, mix = measured, tail = rolling()))
    }

    @Test fun aMeasureTooLateForItsOwnFadeTakesThePlainOne() {
        assertEquals(Step.Apply(plain), step(position = 190_500, mix = measured, tail = rolling()))
        // Too late even for that: no fade, the tail goes.
        assertEquals(Step.Cancel, step(position = 194_000, mix = measured, tail = rolling()))
    }

    @Test fun aMeasureThatDoesntComeInTimeFadesAsTheFileEnds() {
        assertEquals(Step.Idle, step(position = 188_000, tail = rolling()))
        assertEquals(Step.Apply(plain), step(position = 189_000, tail = rolling()))
        assertEquals(Step.Start(), step(position = 195_100, tail = rolling(plain)))
    }

    @Test fun tooCloseToTheFadeNoFade() {
        // Paused (or sought) right at the handover: no time to start the tail, the song just ends.
        assertEquals(Step.Idle, step(position = 190_000, mix = measured, tail = waiting(185_000, measured)))
        assertEquals(Step.Idle, step(position = 192_000, mix = measured, tail = waiting(185_000, measured)))
        assertEquals(Step.Idle, step(position = 189_500, mix = measured))
        assertEquals(Step.Idle, step(position = 196_000, mix = plain))
    }

    @Test fun aRollStoppedRollsAgainWhereTheTailWaits() {
        // Paused mid-roll: the tail waits where it was; back to playing, it rolls on from there.
        val tail = waiting(188_000, measured)
        assertEquals(Step.Idle, step(position = 180_000, mix = measured, tail = tail))
        assertEquals(Step.Roll, step(position = 187_950, mix = measured, tail = tail))
    }

    @Test fun aTailThatShouldntGoOnIsLetGo() {
        val at = 188_000L
        assertEquals(Step.Cancel, step(seconds = 0, position = at, tail = rolling(measured)))
        assertEquals(Step.Cancel, step(position = at, tail = rolling(measured), blocked = true))
        assertEquals(Step.Cancel, step(position = at, tail = rolling(measured), hasNext = false))
        assertEquals(Step.Cancel, step(seconds = 0, position = at, tail = waiting(185_000)))
        // Paused: the player's own pause stops the roll; the plan just waits.
        assertEquals(Step.Idle, step(position = at, tail = rolling(measured), playing = false))
    }

    @Test fun staysOutOfTheWay() {
        assertEquals(Step.Idle, step(seconds = 0, position = 189_000, mix = plain))
        assertEquals(Step.Idle, step(position = 189_000, mix = plain, playing = false))
        assertEquals(Step.Idle, step(position = 189_000, mix = plain, hasNext = false))
        assertEquals(Step.Idle, step(position = 189_000, mix = plain, blocked = true))
        assertEquals(Step.Idle, step(seconds = 0, position = 150_000, measuring = false))
        // A short track (jingle, intro) plays whole.
        assertEquals(Step.Idle, step(duration = 12_000, position = 1_000, measuring = false))
        assertEquals(Step.Idle, step(duration = 12_000, position = 6_000, mix = Mixing.plain(5_000, 12_000)))
    }

    @Test fun repeatOneFadesIntoItsOwnStart() {
        // The song follows itself: it fades even as the last one in the queue, back to the top.
        assertEquals(Step.Measure, step(position = 150_000, repeatOne = true, hasNext = false, measuring = false))
        assertEquals(Step.Prepare(189_000), step(position = 186_000, repeatOne = true, hasNext = false, mix = plain))
        assertEquals(Step.Start(loop = true), step(position = 195_100, repeatOne = true, hasNext = false, tail = rolling(plain)))
        assertEquals(Step.Start(loop = true), step(position = 195_100, repeatOne = true, tail = rolling(plain)))
        // Without "repeat one" it moves on to the next song, as always.
        assertEquals(Step.Start(loop = false), step(position = 195_100, tail = rolling(plain)))
        assertEquals(Step.Cancel, step(seconds = 0, position = 195_100, repeatOne = true, tail = rolling(plain)))
    }

    // ---------------------------------------------------------------- shortly before the fade

    @Test fun inStepItGoes() {
        assertEquals(Verdict.Go, CrossfadePlan.verdict(measured, 5))
        assertEquals(Verdict.Go, CrossfadePlan.verdict(measured, -CrossfadePlan.IN_STEP_MS))
    }

    @Test fun notInStepTheFadeStartsLaterAndEndsWhereItDid() {
        val later = (CrossfadePlan.verdict(measured, 70) as Verdict.Later).mix
        assertEquals(measured.fadeStartMs + CrossfadePlan.LATER_MS, later.fadeStartMs)
        assertEquals(measured.fadeStartMs + measured.fadeMs, later.fadeStartMs + later.fadeMs)
        assertEquals(measured.inStartMs, later.inStartMs)
        // Never measured (the tail only just started): later too, so it can be.
        assertEquals(Verdict.Later(later), CrossfadePlan.verdict(measured, null))
    }

    @Test fun noTimeLeftCloseEnoughGoesElseNoFade() {
        val short = Mix(3_000, 100_000, 0, 0f, 0f)
        assertNull(short.later(CrossfadePlan.LATER_MS, CrossfadePlan.MIN_FADE_MS))
        assertEquals(Verdict.Go, CrossfadePlan.verdict(short, 30))
        assertEquals(Verdict.Go, CrossfadePlan.verdict(short, null))
        assertEquals(Verdict.Drop, CrossfadePlan.verdict(short, 120))
        assertEquals(Verdict.Drop, CrossfadePlan.verdict(short, -90))
    }
}
