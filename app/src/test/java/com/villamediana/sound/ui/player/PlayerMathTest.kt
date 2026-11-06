package com.villamediana.sound.ui.player

import androidx.compose.ui.geometry.Rect
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.RepeatMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerMathTest {
    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A", filePath = "/x/$id.m4a")
    private fun state(size: Int, index: Int, repeat: RepeatMode = RepeatMode.OFF, smart: Boolean = false) =
        PlaybackState(current = track(index.toLong()), queue = (0 until size).map { track(it.toLong()) }, queueIndex = index, repeat = repeat, smart = smart)

    @Test fun `settle - flings decide on their own`() {
        assertEquals(1f, PlayerMath.settleTarget(0.05f, 2f, 0f))
        assertEquals(0f, PlayerMath.settleTarget(0.95f, -2f, 1f))
    }

    @Test fun `settle - small pulls snap back to where they started`() {
        assertEquals(0f, PlayerMath.settleTarget(0.15f, 0f, 0f))
        assertEquals(1f, PlayerMath.settleTarget(0.3f, 0f, 0f))
        assertEquals(1f, PlayerMath.settleTarget(0.85f, 0f, 1f))
        assertEquals(0f, PlayerMath.settleTarget(0.7f, 0f, 1f))
        // Grabbed mid-animation: nearest end.
        assertEquals(1f, PlayerMath.settleTarget(0.55f, 0f, 0.4f))
        assertEquals(0f, PlayerMath.settleTarget(0.45f, 0f, 0.6f))
    }

    @Test fun `sheet frame goes from the mini card to the whole layer`() {
        val mini = PlayerMath.sheetFrame(0f, 1080f, 2400f, miniTop = 2000f, miniHeight = 160f, sideMargin = 20f, miniCorner = 30f)
        assertEquals(PlayerMath.Frame(20f, 2000f, 1060f, 2160f, 30f), mini)
        val full = PlayerMath.sheetFrame(1f, 1080f, 2400f, 2000f, 160f, 20f, 30f)
        assertEquals(PlayerMath.Frame(0f, 0f, 1080f, 2400f, 0f), full)
        val half = PlayerMath.sheetFrame(0.5f, 1080f, 2400f, 2000f, 160f, 20f, 30f)
        assertEquals(1000f, half.top, 0.001f)
        assertEquals(2280f, half.bottom, 0.001f)
        // Out-of-range progress (spring overshoot) is clamped.
        assertEquals(full, PlayerMath.sheetFrame(1.04f, 1080f, 2400f, 2000f, 160f, 20f, 30f))
    }

    @Test fun `rect helpers`() {
        val a = Rect(0f, 0f, 10f, 10f)
        val b = Rect(100f, 200f, 300f, 400f)
        assertEquals(Rect(50f, 100f, 155f, 205f), PlayerMath.lerpRect(a, b, 0.5f))
        assertEquals(Rect(10f, 10f, 90f, 90f), PlayerMath.scaleAroundCenter(Rect(0f, 0f, 100f, 100f), 0.8f))
    }

    @Test fun `position interpolation is capped and clamped`() {
        assertEquals(1_250L, PlayerMath.interpolate(1_000, 250, playing = true, durationMs = 10_000))
        assertEquals(1_000L, PlayerMath.interpolate(1_000, 250, playing = false, durationMs = 10_000))
        assertEquals(1_000L + PlayerMath.MAX_EXTRAPOLATION_MS, PlayerMath.interpolate(1_000, 60_000, true, 100_000))
        assertEquals(10_000L, PlayerMath.interpolate(9_900, 500, true, 10_000))
        assertEquals(0L, PlayerMath.interpolate(-5, 0, false, 0))
    }

    @Test fun `monotonic holds tiny backward corrections only`() {
        assertEquals(5_000L, PlayerMath.monotonic(5_000, 4_900))
        assertEquals(3_000L, PlayerMath.monotonic(5_000, 3_000))
        assertEquals(5_100L, PlayerMath.monotonic(5_000, 5_100))
    }

    @Test fun `times`() {
        assertEquals(0.25f, PlayerMath.fraction(2_500, 10_000))
        assertEquals(0f, PlayerMath.fraction(2_500, 0))
        assertEquals(1f, PlayerMath.fraction(12_000, 10_000))
        assertEquals("−2:30", PlayerMath.remaining(30_000, 180_000))
        assertEquals("−0:00", PlayerMath.remaining(200_000, 180_000))
        assertEquals("0:01", PlayerMath.countdown(1))
        assertEquals("12:00", PlayerMath.countdown(719_001))
        assertEquals("0:00", PlayerMath.countdown(-50))
        assertEquals(1, PlayerMath.sleepMinutesLeft(1))
        assertEquals(15, PlayerMath.sleepMinutesLeft(15 * 60_000L))
        assertEquals(16, PlayerMath.sleepMinutesLeft(15 * 60_000L + 1))
        assertEquals(0, PlayerMath.sleepMinutesLeft(-10))
    }

    @Test fun `queue index mapping and moves`() {
        assertEquals(5, PlayerMath.upNextToQueueIndex(queueIndex = 3, upNextIndex = 1))
        assertEquals(listOf("b", "c", "a"), PlayerMath.moved(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), PlayerMath.moved(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), PlayerMath.moved(listOf("a", "b"), 0, 5))
    }

    @Test fun `reorder target is the row under the dragged row's middle`() {
        val rows = listOf(PlayerMath.Span(3, 0, 100), PlayerMath.Span(4, 100, 200), PlayerMath.Span(5, 200, 300))
        val dragged = rows[0]
        assertNull(PlayerMath.reorderTarget(dragged, 20f, rows))
        assertEquals(4, PlayerMath.reorderTarget(dragged, 60f, rows))
        assertEquals(5, PlayerMath.reorderTarget(dragged, 170f, rows))
        assertEquals(3, PlayerMath.reorderTarget(rows[2], -230f, rows))
    }

    @Test fun `queue entries get unique keys for repeated songs`() {
        val entries = queueEntries(listOf(track(1), track(2), track(1)))
        assertEquals(listOf("1#1", "2#1", "1#2"), entries.map { it.key })
    }

    @Test fun `next and previous availability`() {
        assertEquals(true, PlayerMath.hasNext(state(3, 1)))
        assertEquals(false, PlayerMath.hasNext(state(3, 2)))
        assertEquals(true, PlayerMath.hasNext(state(3, 2, RepeatMode.ALL)))
        assertEquals(true, PlayerMath.hasNext(state(3, 2, smart = true)))
        assertEquals(false, PlayerMath.hasNext(PlaybackState()))
        assertEquals(0, PlayerMath.previousIndex(state(3, 1)))
        assertNull(PlayerMath.previousIndex(state(3, 0)))
        assertEquals(2, PlayerMath.previousIndex(state(3, 0, RepeatMode.ALL)))
        assertNull(PlayerMath.previousIndex(state(1, 0, RepeatMode.ALL)))
    }

    @Test fun `mini swipe needs distance or a fling in the same direction`() {
        val w = 1000f
        val fling = 2000f
        assertEquals(PlayerMath.Swipe.NONE, PlayerMath.miniSwipe(-200f, w, 0f, fling, canNext = true, canPrevious = true))
        assertEquals(PlayerMath.Swipe.NEXT, PlayerMath.miniSwipe(-400f, w, 0f, fling, true, true))
        assertEquals(PlayerMath.Swipe.NEXT, PlayerMath.miniSwipe(-80f, w, -3000f, fling, true, true))
        assertEquals(PlayerMath.Swipe.NONE, PlayerMath.miniSwipe(-80f, w, 3000f, fling, true, true))
        assertEquals(PlayerMath.Swipe.PREVIOUS, PlayerMath.miniSwipe(400f, w, 0f, fling, true, true))
        assertEquals(PlayerMath.Swipe.NONE, PlayerMath.miniSwipe(400f, w, 0f, fling, true, canPrevious = false))
        assertEquals(PlayerMath.Swipe.NONE, PlayerMath.miniSwipe(-400f, w, 0f, fling, canNext = false, canPrevious = true))
    }

    @Test fun `play pause morph endpoints`() {
        val (playL, playR) = PlayerMath.playPauseQuads(0f)
        val (pauseL, pauseR) = PlayerMath.playPauseQuads(1f)
        // Play: the two halves meet at the cut and the right one ends in the tip.
        assertEquals(playL[2], playR[0])
        assertEquals(0.82f, playR[2])
        assertEquals(playR[2], playR[4])
        // Pause: two separate bars of equal width.
        assertEquals(pauseL[2] - pauseL[0], pauseR[2] - pauseR[0], 0.0001f)
        assertEquals(true, pauseR[0] > pauseL[2])
        // Clamped outside 0..1.
        assertArrayEquals(pauseL, PlayerMath.playPauseQuads(3f).first, 0f)
    }

    @Test fun `segment`() {
        assertEquals(0f, PlayerMath.segment(0.1f, 0.2f, 0.6f))
        assertEquals(0.5f, PlayerMath.segment(0.4f, 0.2f, 0.6f), 0.0001f)
        assertEquals(1f, PlayerMath.segment(0.9f, 0.2f, 0.6f))
    }
}
