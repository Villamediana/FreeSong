package com.villamediana.sound.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The single 0..1 progress shared by the mini player and the full player (0 = mini, 1 = full).
 * Drags write it directly; releases settle it with a spring that carries the finger's velocity.
 * Read [progress] only in layout/draw lambdas so the transition never recomposes the player.
 */
@Stable
internal class ExpandState(initial: Float, private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(initial)
        private set

    /** Where the player is resting or heading (0 or 1). */
    var target by mutableFloatStateOf(initial)
        private set

    var dragging by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var dragStart = initial

    fun startDrag() {
        job?.cancel()
        dragging = true
        dragStart = progress
    }

    /** [deltaPx] > 0 = finger moving down (towards collapsed). */
    fun dragBy(deltaPx: Float, rangePx: Float) {
        if (!dragging) startDrag()
        if (rangePx <= 0f) return
        progress = (progress - deltaPx / rangePx).coerceIn(0f, 1f)
    }

    /** [velocityPx] in px/s, > 0 = downwards. */
    fun endDrag(velocityPx: Float, rangePx: Float) {
        if (!dragging) return
        dragging = false
        val v = if (rangePx > 0f) -velocityPx / rangePx else 0f
        animateTo(PlayerMath.settleTarget(progress, v, dragStart), v)
    }

    fun animateTo(value: Float, velocity: Float = 0f) {
        job?.cancel()
        dragging = false
        target = value
        val from = progress
        if (from == value) return
        job = scope.launch {
            animate(from, value, velocity, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = STIFFNESS)) { v, _ ->
                progress = v.coerceIn(0f, 1f)
            }
            progress = value
        }
    }

    fun snapTo(value: Float) {
        job?.cancel()
        dragging = false
        progress = value.coerceIn(0f, 1f)
        target = if (progress >= 0.5f) 1f else 0f
    }

    /** Follows a predictive-back gesture: the player shrinks a little while the back swipe is held. */
    fun backProgress(fraction: Float) {
        job?.cancel()
        progress = 1f - fraction.coerceIn(0f, 1f) * BACK_PEEK
    }

    private companion object {
        const val STIFFNESS = 420f
        const val BACK_PEEK = 0.14f
    }
}
