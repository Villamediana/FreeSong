package com.villamediana.sound.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTrackerTest {
    private var now = 1_000L
    private val tracker = ListenTracker { now }

    @Test
    fun `counts only the time actually playing`() {
        tracker.start(7, durationMs = 200_000, context = "ALBUM", playing = true)
        now += 30_000
        tracker.setPlaying(false)
        now += 60_000 // paused: doesn't count
        tracker.setPlaying(true)
        now += 15_000
        assertEquals(45_000, tracker.listenedMs)
        assertEquals(ListenTracker.Session(7, 45_000, 200_000, "ALBUM"), tracker.finish())
        assertNull(tracker.finish())
    }

    @Test
    fun `starting paused waits for play, repeated play calls don't double count`() {
        tracker.start(1, durationMs = 100_000, context = null, playing = false)
        now += 10_000
        tracker.setPlaying(true)
        tracker.setPlaying(true)
        now += 5_000
        tracker.setPlaying(false)
        tracker.setPlaying(false)
        assertEquals(5_000, tracker.finish()!!.listenedMs)
    }

    @Test
    fun `never more than the duration, which the player can correct`() {
        tracker.start(1, durationMs = 0, context = "MIX", playing = true)
        tracker.updateDuration(180_000)
        now += 400_000
        val s = tracker.finish()!!
        assertEquals(180_000, s.listenedMs)
        assertEquals(180_000, s.durationMs)
    }

    @Test
    fun `nothing tracked means nothing to finish`() {
        tracker.setPlaying(true)
        now += 10_000
        assertNull(tracker.finish())
        assertEquals(0, tracker.listenedMs)
    }

    @Test
    fun `context can change mid song (queue replaced by the same song)`() {
        tracker.start(3, durationMs = 100_000, context = "SONGS", playing = true)
        tracker.updateContext("RADIO")
        now += 1_000
        assertEquals("RADIO", tracker.finish()!!.context)
    }
}
