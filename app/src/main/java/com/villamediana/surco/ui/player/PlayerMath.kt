package com.villamediana.surco.ui.player

import androidx.compose.ui.geometry.Rect
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.RepeatMode
import com.villamediana.surco.ui.components.formatDuration
import kotlin.math.abs
import kotlin.math.ceil

/** Pure math behind the player layer: drag/settle decisions, geometry, time formatting. */
internal object PlayerMath {

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Maps [t] inside [start]..[end] to 0..1 (clamped). */
    fun segment(t: Float, start: Float, end: Float): Float =
        if (end <= start) (if (t >= end) 1f else 0f) else ((t - start) / (end - start)).coerceIn(0f, 1f)

    // ------------------------------------------------------------------ expand / collapse

    /** Fling speed (in progress units per second) that decides the direction on its own. */
    const val FLING_PROGRESS_PER_SEC = 0.25f

    /**
     * Where a released expand/collapse drag settles: 1 = full player, 0 = mini player.
     * [velocity] is in progress units per second (positive = towards expanded); [startProgress]
     * is where the drag began — small pulls away from a resting state snap back.
     */
    fun settleTarget(progress: Float, velocity: Float, startProgress: Float): Float {
        if (velocity > FLING_PROGRESS_PER_SEC) return 1f
        if (velocity < -FLING_PROGRESS_PER_SEC) return 0f
        return when {
            startProgress >= 0.99f -> if (progress < 0.8f) 0f else 1f
            startProgress <= 0.01f -> if (progress > 0.2f) 1f else 0f
            else -> if (progress >= 0.5f) 1f else 0f
        }
    }

    /** The card that grows from the mini player into the full screen, in layer pixels. */
    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float, val corner: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun sheetFrame(
        progress: Float,
        layerWidth: Float,
        layerHeight: Float,
        miniTop: Float,
        miniHeight: Float,
        sideMargin: Float,
        miniCorner: Float,
    ): Frame {
        val p = progress.coerceIn(0f, 1f)
        return Frame(
            left = lerp(sideMargin, 0f, p),
            top = lerp(miniTop, 0f, p),
            right = lerp(layerWidth - sideMargin, layerWidth, p),
            bottom = lerp(miniTop + miniHeight, layerHeight, p),
            corner = lerp(miniCorner, 0f, p),
        )
    }

    fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
        lerp(a.left, b.left, t),
        lerp(a.top, b.top, t),
        lerp(a.right, b.right, t),
        lerp(a.bottom, b.bottom, t),
    )

    /** [r] scaled by [scale] around its own center (the big cover shrinks a little when paused). */
    fun scaleAroundCenter(r: Rect, scale: Float): Rect {
        val cx = r.center.x
        val cy = r.center.y
        val hw = r.width * scale / 2f
        val hh = r.height * scale / 2f
        return Rect(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    // ------------------------------------------------------------------ position

    /** Never extrapolate further than this past the last tick (a stalled ticker must not run away). */
    const val MAX_EXTRAPOLATION_MS = 1_500L

    /** Below this, a tick landing behind the interpolated position is held instead of jumping back. */
    const val BACKWARD_TOLERANCE_MS = 150L

    fun interpolate(basePositionMs: Long, elapsedMs: Long, playing: Boolean, durationMs: Long): Long {
        val p = if (playing) basePositionMs + elapsedMs.coerceIn(0L, MAX_EXTRAPOLATION_MS) else basePositionMs
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }

    /** Smooths tiny backwards corrections while playing so the clock never visibly stutters back. */
    fun monotonic(previous: Long, next: Long, tolerance: Long = BACKWARD_TOLERANCE_MS): Long =
        if (next < previous && previous - next <= tolerance) previous else next

    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** "−2:31" — time left, with a real minus sign. */
    fun remaining(positionMs: Long, durationMs: Long): String =
        "−" + formatDuration((durationMs - positionMs).coerceAtLeast(0L))

    /** Countdown that rounds up, so it reads 0:01 until the very end. */
    fun countdown(remainingMs: Long): String {
        val ms = remainingMs.coerceAtLeast(0L)
        return formatDuration(((ms + 999) / 1000) * 1000)
    }

    /** Minutes shown next to the sleep timer icon (rounded up). */
    fun sleepMinutesLeft(remainingMs: Long): Int = ceil(remainingMs.coerceAtLeast(0L) / 60_000.0).toInt()

    // ------------------------------------------------------------------ lyrics

    /** Lines light up a hair early: by the time the eye reaches them the voice is there. */
    const val LYRICS_LEAD_MS = 180L

    // ------------------------------------------------------------------ queue

    /** Position inside [PlaybackState.queue] of the [upNextIndex]-th item of [PlaybackState.upNext]. */
    fun upNextToQueueIndex(queueIndex: Int, upNextIndex: Int): Int = queueIndex + 1 + upNextIndex

    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** A laid-out row of a list: [index] in the list, [start]/[end] in pixels along the scroll axis. */
    data class Span(val index: Int, val start: Int, val end: Int)

    /**
     * Row the dragged one should swap with: the first other row that contains the dragged row's
     * middle (after applying [dragOffset]). Null = stay.
     */
    fun reorderTarget(dragged: Span, dragOffset: Float, candidates: List<Span>): Int? {
        val middle = (dragged.start + dragOffset + (dragged.end - dragged.start) / 2f).toInt()
        return candidates.firstOrNull { it.index != dragged.index && middle in it.start..it.end }?.index
    }

    // ------------------------------------------------------------------ mini player swipe

    enum class Swipe { NEXT, PREVIOUS, NONE }

    fun hasNext(state: PlaybackState): Boolean =
        state.queueIndex in state.queue.indices &&
            (state.queueIndex < state.queue.lastIndex || state.repeat == RepeatMode.ALL || state.smart)

    /** Queue index a swipe to the right goes to, or null when there is nothing before. */
    fun previousIndex(state: PlaybackState): Int? = when {
        state.queueIndex !in state.queue.indices -> null
        state.queueIndex > 0 -> state.queueIndex - 1
        state.repeat == RepeatMode.ALL && state.queue.size > 1 -> state.queue.lastIndex
        else -> null
    }

    /**
     * What a released horizontal swipe on the mini player does. [offset] < 0 = dragged left
     * (next). Passes when dragged a third of the width or flung faster than [flingVelocity].
     */
    fun miniSwipe(offset: Float, width: Float, velocity: Float, flingVelocity: Float, canNext: Boolean, canPrevious: Boolean): Swipe {
        if (width <= 0f) return Swipe.NONE
        val far = abs(offset) > width / 3f
        val fastLeft = velocity < -flingVelocity && offset < 0f
        val fastRight = velocity > flingVelocity && offset > 0f
        return when {
            offset < 0f && (far || fastLeft) && canNext -> Swipe.NEXT
            offset > 0f && (far || fastRight) && canPrevious -> Swipe.PREVIOUS
            else -> Swipe.NONE
        }
    }

    // ------------------------------------------------------------------ play/pause morph

    /**
     * Two quads (x0,y0 … x3,y3, unit square) that are the play triangle at [t] = 0 (split in two
     * halves) and the pause bars at [t] = 1.
     */
    fun playPauseQuads(t: Float): Pair<FloatArray, FloatArray> {
        val k = t.coerceIn(0f, 1f)
        fun mix(a: FloatArray, b: FloatArray) = FloatArray(a.size) { lerp(a[it], b[it], k) }
        return mix(PLAY_LEFT, PAUSE_LEFT) to mix(PLAY_RIGHT, PAUSE_RIGHT)
    }

    // Play: triangle (0.34,0.2) (0.34,0.8) (0.82,0.5), cut vertically at x = 0.58.
    private val PLAY_LEFT = floatArrayOf(0.34f, 0.20f, 0.58f, 0.35f, 0.58f, 0.65f, 0.34f, 0.80f)
    private val PLAY_RIGHT = floatArrayOf(0.58f, 0.35f, 0.82f, 0.50f, 0.82f, 0.50f, 0.58f, 0.65f)
    private val PAUSE_LEFT = floatArrayOf(0.27f, 0.22f, 0.43f, 0.22f, 0.43f, 0.78f, 0.27f, 0.78f)
    private val PAUSE_RIGHT = floatArrayOf(0.57f, 0.22f, 0.73f, 0.22f, 0.73f, 0.78f, 0.57f, 0.78f)
}
