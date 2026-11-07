package com.villamediana.sound.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Horizontal swipe that removes the row: past ~40% of the width (or a fling) it slides out and
 * calls [onRemove]; otherwise it springs back. The revealed side shows a delete mark. The queue
 * and the playlists use it.
 */
@Composable
fun SwipeToRemove(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the row sits on (the list's own background), so the red underneath shows only as it slides. */
    background: Color = SoundColors.Surface2,
    /** False: the row stays put (pages where songs can't be taken out). */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
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
                .background(SoundColors.Danger.copy(alpha = 0.22f)),
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                null,
                tint = SoundColors.Danger,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .size(22.dp)
                    .graphicsLayer { alpha = if (offset.value > 0f) 1f else 0f },
            )
            Icon(
                Icons.Rounded.DeleteOutline,
                null,
                tint = SoundColors.Danger,
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
                .background(background)
                .draggable(
                    enabled = enabled,
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
