package com.villamediana.surco.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLogicTest {

    // ------------------------------------------------------------------ auto-hide

    @Test fun controlsHideThreeSecondsAfterTheLastTouchWhilePlaying() {
        val s = OverlayState(visible = true, touchedAtMs = 1_000)
        assertEquals(4_000L, s.hideAtMs(playing = true, pinned = false))
        assertTrue(s.visibleAt(3_999, playing = true, pinned = false))
        assertFalse(s.visibleAt(4_000, playing = true, pinned = false))
    }

    @Test fun touchingRestartsTheTimer() {
        val s = OverlayState(visible = true, touchedAtMs = 1_000).touch(3_500)
        assertEquals(6_500L, s.hideAtMs(playing = true, pinned = false))
        assertTrue(s.visibleAt(6_000, playing = true, pinned = false))
    }

    @Test fun pausedLoadingOrScrubbingKeepsTheControlsUp() {
        val s = OverlayState(visible = true, touchedAtMs = 0)
        assertNull(s.hideAtMs(playing = false, pinned = false))
        assertNull(s.hideAtMs(playing = true, pinned = true))
        assertTrue(s.visibleAt(60_000, playing = false, pinned = false))
        assertTrue(s.visibleAt(60_000, playing = true, pinned = true))
    }

    @Test fun tapTogglesAndHiddenControlsHaveNoTimer() {
        val hidden = OverlayState(visible = true, touchedAtMs = 0).toggle(500)
        assertFalse(hidden.visible)
        assertNull(hidden.hideAtMs(playing = true, pinned = false))
        assertFalse(hidden.visibleAt(600, playing = true, pinned = false))
        val shown = hidden.toggle(2_000)
        assertTrue(shown.visible)
        assertEquals(5_000L, shown.hideAtMs(playing = true, pinned = false))
    }

    @Test fun hideKeepsTheLastTouch() {
        val s = OverlayState(visible = true, touchedAtMs = 700).hide()
        assertFalse(s.visible)
        assertEquals(700L, s.touchedAtMs)
    }

    // ------------------------------------------------------------------ double-tap seek

    @Test fun sidesSplitTheStageInHalves() {
        assertEquals(SeekSide.BACK, VideoSeek.sideOf(10f, 400f))
        assertEquals(SeekSide.BACK, VideoSeek.sideOf(199f, 400f))
        assertEquals(SeekSide.FORWARD, VideoSeek.sideOf(200f, 400f))
        assertEquals(SeekSide.FORWARD, VideoSeek.sideOf(399f, 400f))
    }

    @Test fun seekJumpsTenSecondsAndStaysInsideTheVideo() {
        assertEquals(40_000L, VideoSeek.target(30_000, 200_000, SeekSide.FORWARD))
        assertEquals(20_000L, VideoSeek.target(30_000, 200_000, SeekSide.BACK))
        assertEquals(0L, VideoSeek.target(4_000, 200_000, SeekSide.BACK))
        assertEquals(200_000L, VideoSeek.target(195_000, 200_000, SeekSide.FORWARD))
        assertEquals(200_000L, VideoSeek.target(200_000, 200_000, SeekSide.FORWARD))
    }

    @Test fun unknownLengthOnlyClampsAtZero() {
        // ExoPlayer reports C.TIME_UNSET (a large negative number) before the length is known.
        assertEquals(1_010_000L, VideoSeek.target(1_000_000, Long.MIN_VALUE + 1, SeekSide.FORWARD))
        assertEquals(10_000L, VideoSeek.target(0, 0, SeekSide.FORWARD))
        assertEquals(0L, VideoSeek.target(-500, 0, SeekSide.BACK))
    }

    @Test fun quickTapsOnTheSameSideAddUp() {
        val first = VideoSeek.tap(null, SeekSide.FORWARD, nowMs = 1_000, positionMs = 30_000, durationMs = 200_000)
        assertEquals(1, first.taps)
        assertEquals(40_000L, first.targetMs)
        assertEquals(10L, first.seconds)
        // The player still says 30 s: the streak jumps from its own last target.
        val second = VideoSeek.tap(first, SeekSide.FORWARD, nowMs = 1_400, positionMs = 30_000, durationMs = 200_000)
        assertEquals(2, second.taps)
        assertEquals(50_000L, second.targetMs)
        assertEquals(20L, second.seconds)
    }

    @Test fun streakEndsAfterTheWindowOrOnTheOtherSide() {
        val first = VideoSeek.tap(null, SeekSide.FORWARD, nowMs = 1_000, positionMs = 30_000, durationMs = 200_000)
        assertTrue(VideoSeek.continues(first, SeekSide.FORWARD, 1_000 + VideoSeek.STREAK_WINDOW_MS))
        assertFalse(VideoSeek.continues(first, SeekSide.FORWARD, 1_001 + VideoSeek.STREAK_WINDOW_MS))
        assertFalse(VideoSeek.continues(first, SeekSide.BACK, 1_100))
        assertFalse(VideoSeek.continues(null, SeekSide.FORWARD, 1_100))

        val late = VideoSeek.tap(first, SeekSide.FORWARD, nowMs = 5_000, positionMs = 41_000, durationMs = 200_000)
        assertEquals(1, late.taps)
        assertEquals(51_000L, late.targetMs)

        val other = VideoSeek.tap(first, SeekSide.BACK, nowMs = 1_200, positionMs = 40_000, durationMs = 200_000)
        assertEquals(SeekSide.BACK, other.side)
        assertEquals(1, other.taps)
        assertEquals(30_000L, other.targetMs)
    }

    @Test fun streakClampsAtTheEdges() {
        var s = VideoSeek.tap(null, SeekSide.BACK, nowMs = 0, positionMs = 15_000, durationMs = 200_000)
        assertEquals(5_000L, s.targetMs)
        s = VideoSeek.tap(s, SeekSide.BACK, nowMs = 300, positionMs = 15_000, durationMs = 200_000)
        assertEquals(0L, s.targetMs)
        s = VideoSeek.tap(s, SeekSide.BACK, nowMs = 600, positionMs = 15_000, durationMs = 200_000)
        assertEquals(0L, s.targetMs)
        assertEquals(3, s.taps)

        var f = VideoSeek.tap(null, SeekSide.FORWARD, nowMs = 0, positionMs = 185_000, durationMs = 200_000)
        f = VideoSeek.tap(f, SeekSide.FORWARD, nowMs = 300, positionMs = 185_000, durationMs = 200_000)
        assertEquals(200_000L, f.targetMs)
    }

    // ------------------------------------------------------------------ times

    @Test fun timesUseMinutesAndSeconds() {
        assertEquals("0:00", VideoTime.format(0))
        assertEquals("0:09", VideoTime.format(9_999))
        assertEquals("1:05", VideoTime.format(65_000))
        assertEquals("12:00", VideoTime.format(720_000))
        assertEquals("59:59", VideoTime.format(3_599_000))
    }

    @Test fun hourLongVideosPadBothSides() {
        assertEquals("1:02:05", VideoTime.format(3_725_000))
        assertEquals("0:01:05", VideoTime.format(65_000, totalMs = 3_725_000))
        assertEquals("1:05", VideoTime.format(65_000, totalMs = 3_599_000))
    }

    @Test fun negativeOrUnknownTimes() {
        assertEquals("0:00", VideoTime.format(-1_000))
        assertEquals("0:00", VideoTime.format(Long.MIN_VALUE + 1))
        assertEquals(VideoTime.UNKNOWN, VideoTime.formatTotal(0))
        assertEquals(VideoTime.UNKNOWN, VideoTime.formatTotal(Long.MIN_VALUE + 1))
        assertEquals("3:51", VideoTime.formatTotal(231_000))
    }

    @Test fun fractionIsClampedAndZeroWhenLengthUnknown() {
        assertEquals(0.25f, VideoTime.fraction(50_000, 200_000), 0.0001f)
        assertEquals(1f, VideoTime.fraction(250_000, 200_000), 0.0001f)
        assertEquals(0f, VideoTime.fraction(-5, 200_000), 0.0001f)
        assertEquals(0f, VideoTime.fraction(50_000, 0), 0.0001f)
        assertEquals(0f, VideoTime.fraction(50_000, Long.MIN_VALUE + 1), 0.0001f)
    }

    // ------------------------------------------------------------------ seek hold, ratio, start

    @Test fun pendingSeekHoldsUntilThePlayerCatchesUp() {
        val p = PendingSeek(targetMs = 90_000, atMs = 1_000)
        assertEquals(90_000L, p.shown(reportedMs = 30_000, nowMs = 1_100))
        assertNull(p.shown(reportedMs = 89_400, nowMs = 1_100))
        assertNull(p.shown(reportedMs = 30_000, nowMs = 1_000 + PendingSeek.HOLD_MS + 1))
    }

    @Test fun aspectRatioFallsBackToWidescreen() {
        assertEquals(16f / 9f, safeAspectRatio(0f), 0.0001f)
        assertEquals(16f / 9f, safeAspectRatio(Float.NaN), 0.0001f)
        assertEquals(16f / 9f, safeAspectRatio(-1f), 0.0001f)
        assertEquals(4f / 3f, safeAspectRatio(4f / 3f), 0.0001f)
        assertEquals(3f, safeAspectRatio(10f), 0.0001f)
        assertEquals(0.4f, safeAspectRatio(0.1f), 0.0001f)
    }

    @Test fun videoStartsWhereTheSongIsOnlyForTheSameVideo() {
        assertEquals(42_000L, videoStartMs("abc", "abc", 42_000))
        assertEquals(0L, videoStartMs("xyz", "abc", 42_000))
        assertEquals(0L, videoStartMs(null, "abc", 42_000))
        assertEquals(0L, videoStartMs("abc", "abc", -3))
    }
}
