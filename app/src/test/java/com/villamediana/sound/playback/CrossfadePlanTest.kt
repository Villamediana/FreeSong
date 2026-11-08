package com.villamediana.sound.playback

import com.villamediana.sound.playback.CrossfadePlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CrossfadePlanTest {
    private fun step(
        seconds: Int = 5,
        duration: Long = 200_000,
        position: Long,
        playing: Boolean = true,
        hasNext: Boolean = true,
        repeatOne: Boolean = false,
        blocked: Boolean = false,
        prepared: Boolean = false,
    ) = CrossfadePlan.step(seconds, duration, position, playing, hasNext, repeatOne, blocked, prepared)

    @Test fun readiesTheTailAheadThenStartsAtTheFade() {
        assertEquals(Step.Idle, step(position = 100_000))
        // 5 s fade + 6 s ahead: from 189 s the end of the song is readied at 195 s.
        assertEquals(Step.Prepare(195_000), step(position = 190_000))
        assertEquals(Step.Idle, step(position = 190_000, prepared = true))
        assertEquals(Step.Start(4_000), step(position = 196_000, prepared = true))
        // Not readied in time (a seek to the very end): no fade, the song just ends.
        assertEquals(Step.Idle, step(position = 196_000, prepared = false))
    }

    @Test fun staysOutOfTheWay() {
        val end = 197_000L
        assertEquals(Step.Idle, step(seconds = 0, position = end, prepared = true))
        assertEquals(Step.Idle, step(position = end, prepared = true, playing = false))
        assertEquals(Step.Idle, step(position = end, prepared = true, hasNext = false))
        assertEquals(Step.Idle, step(position = end, prepared = true, blocked = true))
        // A short track (jingle, intro) plays whole.
        assertEquals(Step.Idle, step(duration = 12_000, position = 9_000, prepared = true))
    }

    @Test fun repeatOneFadesIntoItsOwnStart() {
        // The song follows itself: it fades even as the last one in the queue, back to the top.
        assertEquals(Step.Prepare(195_000), step(position = 190_000, repeatOne = true, hasNext = false))
        assertEquals(Step.Start(4_000, loop = true), step(position = 196_000, repeatOne = true, hasNext = false, prepared = true))
        assertEquals(Step.Start(4_000, loop = true), step(position = 196_000, repeatOne = true, prepared = true))
        // Without "repeat one" it moves on to the next song, as always.
        assertEquals(Step.Start(4_000, loop = false), step(position = 196_000, prepared = true))
        // The rest still holds: off, paused, sleep timer, a short track.
        assertEquals(Step.Idle, step(seconds = 0, position = 196_000, repeatOne = true, prepared = true))
        assertEquals(Step.Idle, step(position = 196_000, repeatOne = true, prepared = true, playing = false))
        assertEquals(Step.Idle, step(position = 196_000, repeatOne = true, prepared = true, blocked = true))
        assertEquals(Step.Idle, step(duration = 12_000, position = 9_000, repeatOne = true, prepared = true))
    }

    @Test fun equalPowerCurve() {
        assertEquals(1f to 0f, CrossfadePlan.gains(0f).let { (a, b) -> a to (if (abs(b) < 1e-6f) 0f else b) })
        val (outMid, inMid) = CrossfadePlan.gains(0.5f)
        assertTrue(abs(outMid - inMid) < 1e-4f)
        // Constant power: out² + in² = 1 all along.
        listOf(0.1f, 0.3f, 0.7f, 0.9f).forEach { t ->
            val (o, i) = CrossfadePlan.gains(t)
            assertEquals(1f, o * o + i * i, 1e-4f)
        }
        val (outEnd, inEnd) = CrossfadePlan.gains(1f)
        assertTrue(outEnd < 1e-4f && abs(inEnd - 1f) < 1e-4f)
    }
}
