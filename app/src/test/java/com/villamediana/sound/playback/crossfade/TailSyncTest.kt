package com.villamediana.sound.playback.crossfade

import com.villamediana.sound.playback.crossfade.TailSync.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailSyncTest {
    private val far = 6_000L
    private val settle = TailSync.SETTLE_START_MS

    /** Looks every 50 ms from [from] with [offset] until something other than None comes (or [until]). */
    private fun look(s: TailSync, from: Long, offset: (Long) -> Long?, until: Long = from + 5_000, toDecide: (Long) -> Long = { far }): Pair<Long, Action> {
        var now = from
        while (now <= until) {
            val a = s.step(now, offset(now), toDecide(now))
            if (a != Action.None) return now to a
            now += 50
        }
        return now to Action.None
    }

    private fun look(s: TailSync, from: Long, offset: Long?, until: Long = from + 5_000) = look(s, from, { offset }, until)

    @Test fun aTailBehindIsNudgedFasterThenBackToItsSpeed() {
        val s = TailSync()
        s.start(0)
        // Nothing while it settles; then five looks, then a nudge that makes up 120 ms in 800 ms.
        val (at, nudge) = look(s, 0, 120)
        assertEquals(settle + 4 * 50, at)
        assertEquals(Action.Speed(1.15f), nudge)
        assertEquals(120L, s.lastOffsetMs)
        assertEquals(TailSync.NUDGE_MS, s.lookInMs(at, 5_000))
        assertEquals(Action.None, s.step(at + 400, 60, far))
        assertEquals(Action.Speed(1f), s.step(at + TailSync.NUDGE_MS, 0, far))
        // Measured again once the output has played what was nudged: in step.
        assertEquals(Action.None, look(s, at + TailSync.NUDGE_MS + 50, 3, until = at + 3_000).second)
        assertEquals(3L, s.lastOffsetMs)
    }

    @Test fun aTailAheadIsSlowed() {
        val s = TailSync()
        s.start(0)
        assertEquals(Action.Speed(0.95f), look(s, 0, -40).second)
    }

    @Test fun oneOddPositionDoesntCount() {
        val s = TailSync()
        s.start(0)
        val offsets = listOf(80L, 80L, 85L, 80L, 80L, 80L)
        var now = settle
        var last: Action = Action.None
        for (o in offsets) {
            last = s.step(now, o, far)
            if (last != Action.None) break
            now += 50
        }
        assertEquals(Action.Speed(1.1f), last)
        // A single wild one among steady ones: the middle one counts once they agree again.
        val t = TailSync()
        t.start(0)
        var a: Action = Action.None
        for ((k, o) in listOf(80L, 80L, 900L, 80L, 80L, 80L, 80L, 80L).withIndex()) {
            a = t.step(settle + k * 50L, o, far)
            if (a != Action.None) break
        }
        assertEquals(Action.Speed(1.1f), a)
    }

    @Test fun aPositionStillSettlingIsWaitedFor() {
        val s = TailSync()
        s.start(0)
        // Coming down 30 ms a look (the player's own smoothing), then still at 40: measured only once
        // the last five agree.
        val (at, nudge) = look(s, 0, { now -> if (now < settle + 300) 200 - (now - settle) / 50 * 30 else 40L })
        assertEquals(settle + 450, at)
        assertEquals(Action.Speed(1.05f), nudge)
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
        assertEquals(Action.None, s.step(at + settle - 50, 90, far))
    }

    @Test fun inStepItIsCheckedAgainNowAndThen() {
        val s = TailSync()
        s.start(0)
        val (at, _) = look(s, 0, 5, until = settle + 300)
        assertEquals(5L, s.lastOffsetMs)
        // Some seconds on (a long roll) it has drifted: put right again.
        val (_, nudge) = look(s, at, 64, until = at + TailSync.RECHECK_MS + 1_000)
        assertEquals(Action.Speed(1.08f), nudge)
    }

    @Test fun noTimeLeftNoNudgeButTheOffsetIsKnown() {
        val s = TailSync()
        s.start(0)
        assertEquals(Action.None, look(s, 0, { 120L }, toDecide = { 1_800 - it }).second)
        assertEquals(120L, s.lastOffsetMs)
        // More time given (the fade moved later): measured and put right at once.
        s.again()
        val (_, nudge) = look(s, 1_400, { 120L }, toDecide = { 3_800 - it })
        assertEquals(Action.Speed(1.15f), nudge)
    }

    @Test fun aTailNotSoundingYetWaits() {
        val s = TailSync()
        s.start(0)
        // Still loading for 2 s: settling counts from the last look it wasn't playing.
        for (now in 0L..2_000L step 50) assertEquals(Action.None, s.step(now, null, far))
        val (at, nudge) = look(s, 2_050, 120)
        assertEquals(2_000 + settle + 4 * 50, at)
        assertEquals(Action.Speed(1.15f), nudge)
    }

    @Test fun stoppedMidNudgeGoesBackToItsSpeed() {
        val s = TailSync()
        s.start(0)
        val (at, _) = look(s, 0, 120)
        assertEquals(Action.Speed(1f), s.step(at + 100, null, far))
    }

    @Test fun stoppedDoesNothingAndForgets() {
        val s = TailSync()
        s.start(0)
        look(s, 0, 5, until = settle + 300)
        s.stop()
        assertNull(s.lastOffsetMs)
        assertEquals(Action.None, look(s, 0, 300).second)
    }
}
