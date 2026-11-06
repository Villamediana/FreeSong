package com.villamediana.sound.ui.detail

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a dragged row of equal-height rows belongs (pure, unit-tested). */
object ReorderMath {
    /**
     * Index the row dragged from [startIndex] should occupy after moving [displacementPx]
     * (finger movement + list scroll) in a list of [count] rows of [rowPx]: it takes the next slot
     * once its middle crosses the middle of the neighbour.
     */
    fun targetIndex(startIndex: Int, displacementPx: Float, rowPx: Float, count: Int): Int {
        if (count <= 0) return 0
        if (rowPx <= 0f) return startIndex.coerceIn(0, count - 1)
        return (startIndex + (displacementPx / rowPx).roundToInt()).coerceIn(0, count - 1)
    }
}

/**
 * Drag-to-reorder for a LazyColumn of equal-height rows, driven by per-row handles. The target
 * slot is computed from the displacement (so fast drags never skip or stall), the dragged row
 * follows the finger while the others slide with animateItem, and the list auto-scrolls when the
 * row is pulled against the top bar or the bottom chrome.
 */
@Stable
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    /** Keys of the reorderable rows, in their current order. */
    private val keysInOrder: () -> List<Any>,
    private val onMove: (fromKey: Any, toKey: Any) -> Boolean,
    private val onDragStart: (key: Any) -> Unit,
    private val onDragEnd: (key: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var draggedDelta by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)
    private var scrolledSinceStart = 0f
    private var startIndex = -1
    private var rowPx = 1f
    private var autoScroll: Job? = null

    /** Viewport band where the row is fully visible (set by the screen, px). */
    var topEdgePx = 0f
    var bottomInsetPx = 0f
    var edgeZonePx = 1f
    var maxSpeedPx = 1f

    private fun info(key: Any?): LazyListItemInfo? =
        if (key == null) null else listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** Translation of the dragged row relative to where the list lays it out. */
    val draggingOffset: Float
        get() = info(draggingKey)?.let { initialOffset + draggedDelta - it.offset } ?: 0f

    fun isDragging(key: Any): Boolean = draggingKey == key

    fun start(key: Any) {
        val item = info(key) ?: return
        onDragStart(key)
        startIndex = keysInOrder().indexOf(key)
        if (startIndex < 0) return
        draggingKey = key
        initialOffset = item.offset
        rowPx = item.size.toFloat().coerceAtLeast(1f)
        draggedDelta = 0f
        scrolledSinceStart = 0f
        autoScroll?.cancel()
        autoScroll = scope.launch { autoScrollLoop() }
    }

    fun drag(dy: Float) {
        if (draggingKey == null) return
        draggedDelta += dy
        settle()
    }

    fun end() {
        val key = draggingKey ?: return
        settle()
        draggingKey = null
        draggedDelta = 0f
        autoScroll?.cancel()
        autoScroll = null
        onDragEnd(key)
    }

    /** Moves the dragged row to the slot its displacement points at. */
    private fun settle() {
        val key = draggingKey ?: return
        val keys = keysInOrder()
        val current = keys.indexOf(key)
        if (current < 0) return
        val target = ReorderMath.targetIndex(startIndex, draggedDelta + scrolledSinceStart, rowPx, keys.size)
        if (target == current) return
        val first = listState.firstVisibleItemIndex
        val currentInfo = info(key)
        if (currentInfo != null && currentInfo.index == first) {
            // Keep the viewport anchored, otherwise moving the first visible row drags the list along.
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
        onMove(key, keys[target])
    }

    private suspend fun autoScrollLoop() {
        while (draggingKey != null) {
            val speed = overscrollSpeed()
            if (speed != 0f) {
                scrolledSinceStart += listState.scrollBy(speed)
                settle()
            }
            withFrameNanos { }
        }
    }

    private fun overscrollSpeed(): Float {
        val current = info(draggingKey) ?: return 0f
        val top = current.offset + draggingOffset
        val bottom = top + current.size
        val bottomEdge = listState.layoutInfo.viewportEndOffset - bottomInsetPx
        return when {
            top < topEdgePx -> -((topEdgePx - top) / edgeZonePx).coerceIn(0f, 1f) * maxSpeedPx
            bottom > bottomEdge -> ((bottom - bottomEdge) / edgeZonePx).coerceIn(0f, 1f) * maxSpeedPx
            else -> 0f
        }
    }
}

@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    topEdgePx: Float,
    bottomInsetPx: Float,
    keysInOrder: () -> List<Any>,
    onMove: (fromKey: Any, toKey: Any) -> Boolean,
    onDragStart: (key: Any) -> Unit,
    onDragEnd: (key: Any) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keys by rememberUpdatedState(keysInOrder)
    val move by rememberUpdatedState(onMove)
    val begin by rememberUpdatedState(onDragStart)
    val end by rememberUpdatedState(onDragEnd)
    val state = remember(listState) {
        ReorderState(listState, scope, { keys() }, { a, b -> move(a, b) }, { begin(it) }, { end(it) })
    }
    state.topEdgePx = topEdgePx
    state.bottomInsetPx = bottomInsetPx
    with(density) {
        state.edgeZonePx = 72.dp.toPx()
        state.maxSpeedPx = 22.dp.toPx()
    }
    return state
}

/** Put on the row's drag handle. Only [state] and [key] are captured (both stable for the row). */
internal fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier =
    pointerInput(state, key) {
        detectDragGestures(
            onDragStart = { state.start(key) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
        )
    }
