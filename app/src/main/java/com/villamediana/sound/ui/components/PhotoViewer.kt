package com.villamediana.sound.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.villamediana.sound.R
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A photo (a person's, an artist's) on the whole screen, over black: pinch or double-tap to come
 * closer, drag it around once closer; at its size, drag it down (or tap the X) to close.
 */
@Composable
fun PhotoViewer(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val scope = rememberCoroutineScope()
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val drop = remember { Animatable(0f) }
        val fade = (1f - abs(drop.value) / 900f).coerceIn(0.3f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = fade))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { at ->
                            if (scale > 1.05f) {
                                scale = 1f
                                pan = Offset.Zero
                            } else {
                                scale = 2.5f
                                pan = (Offset(size.width / 2f, size.height / 2f) - at) * 1.5f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val move = event.calculatePan()
                            if (event.changes.size > 1 || scale > 1.02f) {
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                val limitX = size.width * (scale - 1f) / 2f
                                val limitY = size.height * (scale - 1f) / 2f
                                pan = Offset((pan.x + move.x).coerceIn(-limitX, limitX), (pan.y + move.y).coerceIn(-limitY, limitY))
                                if (scale <= 1.02f) pan = Offset.Zero
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (abs(move.y) > 0f) {
                                dragging = true
                                scope.launch { drop.snapTo(drop.value + move.y) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (dragging) {
                            if (abs(drop.value) > size.height * 0.18f) onDismiss()
                            else scope.launch { drop.animateTo(0f) }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y + drop.value
                    },
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .semantics { contentDescription = "photo-close" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.photo_close), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
