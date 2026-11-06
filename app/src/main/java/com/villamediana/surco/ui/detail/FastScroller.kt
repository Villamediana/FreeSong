package com.villamediana.surco.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ThumbTouchHeight = 64.dp
private val ThumbTouchWidth = 40.dp

/**
 * Fast scroller for long song lists: a slim thumb that appears while the list moves; grab it and
 * a bubble shows where you are (a letter for A–Z sorts, a month, a play count…). Items
 * [firstItemIndex] until [firstItemIndex] + [itemCount] of the LazyColumn are the scrollable songs.
 */
@Composable
internal fun BoxScope.FastScroller(
    listState: LazyListState,
    firstItemIndex: Int,
    itemCount: Int,
    label: (Int) -> String,
    topPadding: Dp,
    bottomPadding: Dp,
    minItems: Int = 40,
) {
    if (itemCount < minItems) return
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val thumbPx = with(density) { ThumbTouchHeight.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastBubble by remember { mutableStateOf("") }
    val labelFor by rememberUpdatedState(label)
    val count by rememberUpdatedState(itemCount)
    val first by rememberUpdatedState(firstItemIndex)

    val listFraction by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visibleSongs = info.visibleItemsInfo.count { it.index >= first && it.index < first + count }.coerceAtLeast(1)
            val firstSong = (listState.firstVisibleItemIndex - first).coerceAtLeast(0)
            if (listState.firstVisibleItemIndex < first) 0f else ScrollIndex.fractionOf(firstSong, count, visibleSongs)
        }
    }
    val fraction = if (dragging) dragFraction else listFraction

    var shown by remember { mutableStateOf(false) }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else {
            delay(1400)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scroller")
    val thumbWidth by animateDpAsState(if (dragging) 6.dp else 4.dp, label = "thumb")

    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = topPadding + 8.dp, bottom = bottomPadding + 8.dp)
            .width(ThumbTouchWidth)
            .onSizeChanged { trackPx = it.height },
    ) {
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val y = (fraction * travel).roundToInt()
        if (alpha > 0.01f) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y) }
                    .size(ThumbTouchWidth, ThumbTouchHeight)
                    .graphicsLayer { this.alpha = alpha }
                    .pointerInput(Unit) {
                        var lastIndex = -1
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragFraction = listFraction
                                lastBubble = ""
                                dragging = true
                                lastIndex = -1
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val travelNow = (trackPx - thumbPx).coerceAtLeast(1f)
                                dragFraction = (dragFraction + dy / travelNow).coerceIn(0f, 1f)
                                val i = ScrollIndex.indexAt(dragFraction, count)
                                if (i != lastIndex) {
                                    lastIndex = i
                                    listState.requestScrollToItem(first + i)
                                }
                            },
                        )
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 5.dp)
                        .width(thumbWidth)
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(if (dragging) SurcoColors.Accent else SurcoColors.TextMuted.copy(alpha = 0.75f)),
                )
            }
        }
    }

    // Bubble, left of the thumb, centred on it. Keeps its last text while it fades out.
    val bubbleText = labelFor(ScrollIndex.indexAt(dragFraction, itemCount))
    LaunchedEffect(bubbleText, dragging) {
        if (!dragging) return@LaunchedEffect
        if (lastBubble.isNotEmpty() && lastBubble != bubbleText) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        lastBubble = bubbleText
    }
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = topPadding + 8.dp, end = ThumbTouchWidth + 6.dp),
    ) {
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val bubbleHalf = with(density) { 30.dp.toPx() }
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier.offset { IntOffset(0, (dragFraction * travel + thumbPx / 2 - bubbleHalf).roundToInt()) },
        ) {
            Box(
                Modifier
                    .heightIn(min = 60.dp)
                    .widthIn(min = 60.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurcoColors.Surface3)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (dragging) bubbleText else lastBubble,
                    style = MaterialTheme.typography.headlineMedium,
                    color = SurcoColors.Text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
