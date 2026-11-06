package com.villamediana.surco.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** One row of "A seguir". [key] is unique even when the same song is queued twice. */
internal data class QueueEntry(val key: String, val track: Track)

internal fun queueEntries(tracks: List<Track>): List<QueueEntry> {
    val seen = HashMap<Long, Int>()
    return tracks.map { t ->
        val n = (seen[t.id] ?: 0) + 1
        seen[t.id] = n
        QueueEntry("${t.id}#$n", t)
    }
}

/**
 * Drag-to-reorder for a LazyColumn: the dragged row follows the finger (it is translated, not
 * re-laid out), swaps with the row under its middle, and the list scrolls when it nears an edge.
 */
@Stable
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxStepPx: Float,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** Row settling back into its slot after a drop. */
    var settlingKey by mutableStateOf<Any?>(null)
        private set
    private val settle = Animatable(0f)

    private var initialOffset = 0
    private var delta by mutableFloatStateOf(0f)
    private var autoScroll: Job? = null

    var canSwapWith: (Any) -> Boolean = { true }
    var onStart: (key: Any) -> Unit = {}
    var onSwap: (from: Any, to: Any) -> Unit = { _, _ -> }
    var onDrop: (key: Any) -> Unit = {}

    val active: Boolean get() = draggingKey != null

    private fun info(key: Any): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** Vertical translation to draw row [key] with (read it in a graphicsLayer block). */
    fun translation(key: Any): Float = when (key) {
        draggingKey -> info(key)?.let { initialOffset + delta - it.offset } ?: delta
        settlingKey -> settle.value
        else -> 0f
    }

    fun start(key: Any) {
        val item = info(key) ?: return
        settlingKey = null
        draggingKey = key
        initialOffset = item.offset
        delta = 0f
        onStart(key)
    }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        delta += dy
        trySwap(key)
        ensureAutoScroll(key)
    }

    fun end() {
        val key = draggingKey ?: return
        val residual = translation(key)
        draggingKey = null
        delta = 0f
        autoScroll?.cancel()
        onDrop(key)
        settlingKey = key
        scope.launch {
            settle.snapTo(residual)
            settle.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f))
            if (settlingKey == key) settlingKey = null
        }
    }

    private fun trySwap(key: Any) {
        val dragged = info(key) ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val candidates = visible.filter { it.key != key && canSwapWith(it.key) }
            .map { PlayerMath.Span(it.index, it.offset, it.offset + it.size) }
        val targetIndex = PlayerMath.reorderTarget(
            PlayerMath.Span(dragged.index, dragged.offset, dragged.offset + dragged.size),
            initialOffset + delta - dragged.offset,
            candidates,
        ) ?: return
        val target = visible.first { it.index == targetIndex }
        // The list keeps its first visible item by key: pin the scroll so swapping with it doesn't drag the list along.
        if (dragged.index == listState.firstVisibleItemIndex || target.index == listState.firstVisibleItemIndex) {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            scope.launch { listState.scrollToItem(index, offset) }
        }
        onSwap(key, target.key)
    }

    private fun ensureAutoScroll(key: Any) {
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (isActive && draggingKey == key) {
                val item = info(key) ?: break
                val top = item.offset + translation(key)
                val bottom = top + item.size
                val start = listState.layoutInfo.viewportStartOffset
                val end = listState.layoutInfo.viewportEndOffset
                val step = when {
                    top < start + edgePx -> -((start + edgePx - top) / edgePx).coerceIn(0f, 1f) * maxStepPx
                    bottom > end - edgePx -> ((bottom - (end - edgePx)) / edgePx).coerceIn(0f, 1f) * maxStepPx
                    else -> 0f
                }
                if (step == 0f) break
                val moved = listState.scrollBy(step)
                trySwap(key)
                if (moved == 0f) break
                withFrameNanos { }
            }
        }
    }
}

/** Grip for [ReorderState]: drag starts right away (no long-press), with a haptic tick. */
@Composable
internal fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier {
    val haptic = LocalHapticFeedback.current
    return pointerInput(state, key) {
        detectDragGestures(
            onDragStart = {
                state.start(key)
                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
        )
    }
}

/**
 * Horizontal swipe that removes the row: past ~40% of the width (or a fling) it slides out and
 * calls [onRemove]; otherwise it springs back. The revealed side shows a delete mark.
 */
@Composable
internal fun SwipeToRemove(onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val flingPx = with(LocalDensity.current) { 900.dp.toPx() }
    val threshold = width * 0.4f

    Box(modifier.onSizeChanged { width = it.width }) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (width == 0) 0f else (abs(offset.value) / (width * 0.4f)).coerceIn(0f, 1f) }
                .background(SurcoColors.Danger.copy(alpha = 0.22f)),
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                null,
                tint = SurcoColors.Danger,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .size(22.dp)
                    .graphicsLayer { alpha = if (offset.value > 0f) 1f else 0f },
            )
            Icon(
                Icons.Rounded.DeleteOutline,
                null,
                tint = SurcoColors.Danger,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .size(22.dp)
                    .graphicsLayer { alpha = if (offset.value < 0f) 1f else 0f },
            )
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .background(SurcoColors.Surface2)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d ->
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(offset.value + d) }
                        val past = abs(offset.value) > threshold
                        if (past != armed) {
                            armed = past
                            if (past) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    },
                    onDragStopped = { velocity ->
                        val o = offset.value
                        val fling = abs(velocity) > flingPx && sign(velocity) == sign(o)
                        if (width > 0 && o != 0f && (abs(o) > threshold || fling)) {
                            offset.animateTo(sign(o) * width, tween(170))
                            onRemove()
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
                        }
                        armed = false
                    },
                ),
        ) { content() }
    }
}
