package com.villamediana.sound.playback.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopCrossfadeTest {
    private val loop = 10_000L

    @Test fun theOtherCopyStartsWhenTheLoopIsAboutToEnd() {
        assertFalse(LoopCrossfade.shouldStart(5_000, loop))
        assertFalse(LoopCrossfade.shouldStart(loop - LoopCrossfade.FADE_MS - 1, loop))
        assertTrue(LoopCrossfade.shouldStart(loop - LoopCrossfade.FADE_MS, loop))
        assertTrue(LoopCrossfade.shouldStart(loop - 100, loop))
        // Unknown or too short: no crossfade.
        assertFalse(LoopCrossfade.shouldStart(0, androidx.media3.common.C.TIME_UNSET))
        assertFalse(LoopCrossfade.shouldStart(1_000, 2_000))
    }

    @Test fun theStartTakesOverSmoothly() {
        assertEquals(0f, LoopCrossfade.progress(0), 1e-4f)
        assertEquals(0.5f, LoopCrossfade.progress(LoopCrossfade.FADE_MS / 2), 1e-4f)
        assertEquals(1f, LoopCrossfade.progress(LoopCrossfade.FADE_MS), 1e-4f)
        val steps = (0..12).map { LoopCrossfade.progress(it * 100L) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun theTopCopyComesInThenHandsBack() {
        // The top copy coming in from the loop's start: it shows more and more.
        assertEquals(0.25f, LoopCrossfade.topAlpha(incomingIsTop = true, progress = 0.25f), 1e-4f)
        // Next loop the bottom copy comes in: the top one fades away over it.
        assertEquals(0.75f, LoopCrossfade.topAlpha(incomingIsTop = false, progress = 0.25f), 1e-4f)
    }
}
