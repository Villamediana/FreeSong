package com.villamediana.sound.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockTest {
    private val ms = 1_000_000L

    @Test fun `glides between ticks while playing`() {
        val clock = PlaybackClock()
        clock.onState(playing = true, durationMs = 200_000, nowNanos = 0)
        clock.onTick(10_000, nowNanos = 0)
        clock.onFrame(nowNanos = 100 * ms)
        assertEquals(10_100L, clock.positionMs)
        clock.onFrame(nowNanos = 240 * ms)
        assertEquals(10_240L, clock.positionMs)
        // Next tick lands a little behind what we showed: hold, don't stutter back.
        clock.onTick(10_200, nowNanos = 240 * ms)
        assertEquals(10_240L, clock.positionMs)
        clock.onFrame(nowNanos = 300 * ms)
        assertEquals(10_260L, clock.positionMs)
    }

    @Test fun `does not move while paused`() {
        val clock = PlaybackClock()
        clock.onState(playing = false, durationMs = 200_000, nowNanos = 0)
        clock.onTick(42_000, nowNanos = 0)
        clock.onFrame(nowNanos = 5_000 * ms)
        assertEquals(42_000L, clock.positionMs)
    }

    @Test fun `pausing re-bases on what is shown`() {
        val clock = PlaybackClock()
        clock.onState(true, 200_000, 0)
        clock.onTick(1_000, 0)
        clock.onFrame(200 * ms)
        clock.onState(false, 200_000, 200 * ms)
        clock.onFrame(900 * ms)
        assertEquals(1_200L, clock.positionMs)
    }

    @Test fun `optimistic seek ignores stale ticks for a moment`() {
        val clock = PlaybackClock()
        clock.onState(true, 200_000, 0)
        clock.onTick(5_000, 0)
        clock.seek(90_000, nowNanos = 10 * ms)
        assertEquals(90_000L, clock.positionMs)
        clock.onTick(5_250, nowNanos = 20 * ms) // in flight before the seek landed
        assertEquals(90_000L, clock.positionMs)
        clock.onTick(90_050, nowNanos = 260 * ms)
        assertEquals(90_050L, clock.positionMs)
    }

    @Test fun `a stale seek gives up after the hold`() {
        val clock = PlaybackClock()
        clock.onState(false, 200_000, 0)
        clock.seek(90_000, nowNanos = 0)
        clock.onTick(4_000, nowNanos = 2_000 * ms)
        assertEquals(4_000L, clock.positionMs)
    }

    @Test fun `reset jumps without smoothing`() {
        val clock = PlaybackClock()
        clock.onState(true, 200_000, 0)
        clock.onTick(150_000, 0)
        clock.reset(0, 10 * ms)
        assertEquals(0L, clock.positionMs)
        assertEquals(0f, clock.fraction())
    }
}
