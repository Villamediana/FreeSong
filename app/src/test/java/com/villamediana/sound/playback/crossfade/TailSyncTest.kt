package com.villamediana.sound.playback.crossfade

import com.villamediana.sound.playback.crossfade.TailSync.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailSyncTest {
    private val far = 6_000L

    /** Looks every 50 ms from [from] with [offset] until something other than None comes (or [until]). */
    private fun look(s: TailSync, from: Long, offset: Long?, until: Long = from + 5_000, toHandover: (Long) -> Long = { far }): Pair<Long, Action> {
        var now = from
        while (now <= until) {
            val a = s.step(now, offset, toHandover(now))
            if (a != Action.None) return now to a
            now += 50
        }
        return now to Action.None
    }

    @Test fun aTailBehindIsNudgedFasterThenBackToItsSpeed() {
        val s = TailSync()
        s.start(0)
        // Nothing while it settles; then five looks, then a nudge that makes up 120 ms in 800 ms.
        val (at, nudge) = look(s, 0, 120)
        assertEquals(TailSync.SETTLE_START_MS + 4 * 50, at)
        assertEquals(Action.Speed(1.15f), nudge)
        assertEquals(TailSync.NUDGE_MS, s.lookInMs(at, 5_000))
        assertEquals(Action.None, s.step(at + 400, 60, far))
        assertEquals(Action.Speed(1f), s.step(at + TailSync.NUDGE_MS, 0, far))
        // Measured again once the output has played what was nudged: in step, done.
        val (_, after) = look(s, at + TailSync.NUDGE_MS + 50, 3, until = at + 3_000)
        assertEquals(Action.None, after)
        assertTrue(s.inStep)
    }

    @Test fun aTailAheadIsSlowed() {
        val s = TailSync()
        s.start(0)
        assertEquals(Action.Speed(0.95f), look(s, 0, -40).second)
    }

    @Test fun oneOddPositionDoesntCount() {
        val s = TailSync()
        s.start(0)
        var now = TailSync.SETTLE_START_MS
        val offsets = listOf(80L, 80L, 900L, 80L, 80L)
        var last: Action = Action.None
        for (o in offsets) {
            last = s.step(now, o, far)
            now += 50
        }
        assertEquals(Action.Speed(1.1f), last)
    }

    @Test fun aNudgeIsNeverTooWild() {
        val s = TailSync()
        s.start(0)
        assertEquals(Action.Speed(1f + TailSync.MAX_NUDGE), look(s, 0, 500).second)
    }

    @Test fun farOffItJumpsThenSettlesAgain() {
        val s = TailSync()
        s.start(0)
        val (at, jump) = look(s, 0, 2_000)
        assertEquals(Action.Jump(2_000 + TailSync.JUMP_LEAD_MS), jump)
        // A jump starts the tail over: it settles before it is measured again.
        assertEquals(Action.None, s.step(at + 50, 90, far))
        assertEquals(Action.None, s.step(at + TailSync.SETTLE_START_MS - 50, 90, far))
    }

    @Test fun inStepFromTheStartNothingToDo() {
        val s = TailSync()
        s.start(0)
        assertEquals(Action.None, look(s, 0, 5).second)
        assertTrue(s.inStep)
    }

    @Test fun noTimeLeftNoNudge() {
        val s = TailSync()
        s.start(0)
        // Started late: by the time it's measured the fade is too close to nudge and settle.
        assertEquals(Action.None, look(s, 0, 120, toHandover = { 2_500 - it }).second)
        assertFalse(s.inStep)
        assertEquals(Action.None, s.step(3_000, 120, 1_000))
    }

    @Test fun aTailNotSoundingYetWaits() {
        val s = TailSync()
        s.start(0)
        // Still loading for 2 s: settling counts from the last look it wasn't playing.
        for (now in 0L..2_000L step 50) assertEquals(Action.None, s.step(now, null, far))
        val (at, nudge) = look(s, 2_050, 120)
        assertEquals(2_000 + TailSync.SETTLE_START_MS + 4 * 50, at)
        assertEquals(Action.Speed(1.15f), nudge)
    }

    @Test fun stoppedMidNudgeGoesBackToItsSpeed() {
        val s = TailSync()
        s.start(0)
        val (at, _) = look(s, 0, 120)
        assertEquals(Action.Speed(1f), s.step(at + 100, null, far))
    }

    @Test fun stoppedDoesNothing() {
        val s = TailSync()
        s.start(0)
        s.stop()
        assertEquals(Action.None, look(s, 0, 300).second)
    }
}
