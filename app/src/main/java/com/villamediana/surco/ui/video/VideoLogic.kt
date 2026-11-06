package com.villamediana.surco.ui.video

import java.util.Locale
import kotlin.math.abs

/*
 * Pure rules of the video screen (no Android, no Compose): when the controls hide, where a
 * double-tap seeks to, how times are written, and what the seek bar shows right after a seek.
 */

/** The overlay controls: shown on enter, toggled by a tap, hidden after [AUTO_HIDE_MS] of calm. */
internal data class OverlayState(val visible: Boolean = true, val touchedAtMs: Long = 0L) {
    /** A tap on the stage: shows or hides the controls (and restarts the timer). */
    fun toggle(nowMs: Long): OverlayState = OverlayState(!visible, nowMs)

    /** Any interaction with the controls keeps them up for another [AUTO_HIDE_MS]. */
    fun touch(nowMs: Long): OverlayState = OverlayState(true, nowMs)

    fun hide(): OverlayState = copy(visible = false)

    /**
     * When the controls should hide by themselves, or null while they must stay: hidden already,
     * paused (nothing to watch behind them), or [pinned] (loading, error, scrubbing the bar).
     */
    fun hideAtMs(playing: Boolean, pinned: Boolean): Long? =
        if (visible && playing && !pinned) touchedAtMs + AUTO_HIDE_MS else null

    /** Whether the controls are still meant to be up at [nowMs]. */
    fun visibleAt(nowMs: Long, playing: Boolean, pinned: Boolean): Boolean {
        if (!visible) return false
        val at = hideAtMs(playing, pinned) ?: return true
        return nowMs < at
    }

    companion object {
        const val AUTO_HIDE_MS = 3_000L
    }
}

internal enum class SeekSide { BACK, FORWARD }

/**
 * A run of double-taps on one side of the video ("10 s", "20 s", "30 s"…). Taps that come within
 * [VideoSeek.STREAK_WINDOW_MS] of the previous one on the same side keep adding to the same jump.
 */
internal data class SeekStreak(val side: SeekSide, val taps: Int, val lastTapMs: Long, val targetMs: Long) {
    val seconds: Long get() = taps * VideoSeek.STEP_MS / 1000
}

internal object VideoSeek {
    const val STEP_MS = 10_000L
    const val STREAK_WINDOW_MS = 700L

    /** Left half seeks back, right half forward. */
    fun sideOf(x: Float, width: Float): SeekSide = if (x < width / 2f) SeekSide.BACK else SeekSide.FORWARD

    /** [positionMs] ± [stepMs], kept inside the video (or just above zero when the length is unknown). */
    fun target(positionMs: Long, durationMs: Long, side: SeekSide, stepMs: Long = STEP_MS): Long {
        val from = positionMs.coerceAtLeast(0)
        val raw = if (side == SeekSide.FORWARD) from + stepMs else from - stepMs
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return raw.coerceIn(0L, max)
    }

    /** True when a tap at [nowMs] on [side] belongs to [streak]. */
    fun continues(streak: SeekStreak?, side: SeekSide, nowMs: Long): Boolean =
        streak != null && streak.side == side && nowMs - streak.lastTapMs in 0..STREAK_WINDOW_MS

    /**
     * Registers one more seek tap. A continuing streak jumps from its own last target (the player
     * may not have reported the previous seek yet); a new one from the current [positionMs].
     */
    fun tap(streak: SeekStreak?, side: SeekSide, nowMs: Long, positionMs: Long, durationMs: Long): SeekStreak {
        val continuing = continues(streak, side, nowMs)
        val base = if (continuing) streak!!.targetMs else positionMs
        val taps = if (continuing) streak!!.taps + 1 else 1
        return SeekStreak(side, taps, nowMs, target(base, durationMs, side))
    }
}

/** Times on the video seek bar. */
internal object VideoTime {
    /** "3:07", or "0:03:07" when the whole video is an hour or longer (so both sides line up). */
    fun format(ms: Long, totalMs: Long = 0L): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0 || totalMs >= 3_600_000L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }

    /** The length of the video, or "--:--" while it is unknown. */
    fun formatTotal(totalMs: Long): String = if (totalMs > 0) format(totalMs, totalMs) else UNKNOWN

    /** 0..1 of the way through; 0 when the length is unknown. */
    fun fraction(positionMs: Long, totalMs: Long): Float =
        if (totalMs <= 0) 0f else (positionMs.toFloat() / totalMs).coerceIn(0f, 1f)

    const val UNKNOWN = "--:--"
}

/**
 * Right after a seek the player may still report the old position for a tick or two; until it
 * catches up (or [HOLD_MS] pass) the bar keeps showing where the user sent it.
 */
internal data class PendingSeek(val targetMs: Long, val atMs: Long) {
    /** The position to show for a reported one, or null when the hold is over. */
    fun shown(reportedMs: Long, nowMs: Long): Long? {
        if (abs(reportedMs - targetMs) <= MATCH_MS) return null
        if (nowMs - atMs > HOLD_MS) return null
        return targetMs
    }

    companion object {
        const val MATCH_MS = 1_500L
        const val HOLD_MS = 1_200L
    }
}

/** The video's width / height, kept sane for layout (unknown → 16:9). */
internal fun safeAspectRatio(ratio: Float): Float =
    if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.4f, 3f) else 16f / 9f

/**
 * Where the video starts: at the song's position when the song playing is this very video
 * (so the clip picks up where the music was), otherwise from the top.
 */
internal fun videoStartMs(currentVideoId: String?, routeVideoId: String, musicPositionMs: Long): Long =
    if (currentVideoId != null && currentVideoId == routeVideoId) musicPositionMs.coerceAtLeast(0) else 0L
