package com.villamediana.sound.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopFadeTest {
    @Test fun theClipDissolvesAtTheEndOfItsLoopAndComesBackAtTheStart() {
        val d = 10_000L
        assertEquals(0f, PlayerMath.loopFade(0, d), 1e-4f)
        assertEquals(1f, PlayerMath.loopFade(5_000, d), 1e-4f)
        assertEquals(0f, PlayerMath.loopFade(d, d), 1e-4f)
        val rising = listOf(100L, 300L, 500L, 700L).map { PlayerMath.loopFade(it, d) }
        assertTrue(rising.zipWithNext().all { (a, b) -> b > a })
        val falling = listOf(9_300L, 9_500L, 9_700L, 9_900L).map { PlayerMath.loopFade(it, d) }
        assertTrue(falling.zipWithNext().all { (a, b) -> b < a })
        assertEquals(1f, PlayerMath.loopFade(PlayerMath.LOOP_FADE_MS, d), 1e-4f)
    }

    @Test fun unknownLengthShowsTheClip() {
        assertEquals(1f, PlayerMath.loopFade(0, androidx.media3.common.C.TIME_UNSET), 0f)
        assertEquals(1f, PlayerMath.loopFade(0, 0), 0f)
    }
}
