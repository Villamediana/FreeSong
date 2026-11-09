package com.villamediana.sound.ui.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.villamediana.sound.R
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlin.math.max
import kotlin.math.min

/**
 * The framing of a photo inside the round frame: how much it is zoomed ([zoom], 1 = the photo just
 * covers the frame) and where its top-left corner sits ([offset], frame pixels).
 */
internal data class Framing(val zoom: Float, val offset: Offset)

/**
 * The arithmetic of framing a photo of [width]×[height] px in a square frame of [frame] px: the
 * photo always covers the frame (no empty edges), zooming keeps the point under the fingers still,
 * and the square cut comes out in the photo's own pixels. Pure.
 */
internal class FrameMath(private val width: Int, private val height: Int, private val frame: Float) {
    /** The scale at zoom 1: the shorter side fills the frame. */
    private val base = frame / min(width, height)

    fun scale(zoom: Float) = base * zoom

    /** The photo centered, zoom 1. */
    fun start(): Framing = Framing(1f, Offset((frame - width * base) / 2f, (frame - height * base) / 2f))

    /** Moved and zoomed back inside the rules: zoom between 1 and [MAX_ZOOM], the frame always covered. */
    fun clamp(f: Framing): Framing {
        val zoom = f.zoom.coerceIn(1f, MAX_ZOOM)
        val s = scale(zoom)
        val x = f.offset.x.coerceIn(frame - width * s, 0f)
        val y = f.offset.y.coerceIn(frame - height * s, 0f)
        return Framing(zoom, Offset(x, y))
    }

    /** [f] moved by [pan] and zoomed by [factor] around [focus] (frame pixels), the point under it staying put. */
    fun gesture(f: Framing, pan: Offset, factor: Float, focus: Offset): Framing {
        val zoom = (f.zoom * factor).coerceIn(1f, MAX_ZOOM)
        val ratio = zoom / f.zoom
        val offset = Offset(focus.x - (focus.x - f.offset.x) * ratio, focus.y - (focus.y - f.offset.y) * ratio) + pan
        return clamp(Framing(zoom, offset))
    }

    /** Zoom set directly (the slider), around the frame's center. */
    fun zoomTo(f: Framing, zoom: Float): Framing = gesture(f, Offset.Zero, zoom / f.zoom, Offset(frame / 2f, frame / 2f))

    /** The square of the photo inside the frame, in the photo's pixels (what becomes the profile photo). */
    fun cut(f: Framing): Rect {
        val s = scale(f.zoom)
        val side = min(frame / s, min(width, height).toFloat())
        val left = (-f.offset.x / s).coerceIn(0f, width - side)
        val top = (-f.offset.y / s).coerceIn(0f, height - side)
        return Rect(left, top, left + side, top + side)
    }

    companion object {
        const val MAX_ZOOM = 5f
    }
}

/**
 * "Ajustar foto": the picked photo behind the round frame — drag it, pinch or slide to zoom — then
 * "Aplicar" (the square around the circle becomes the photo) or "Cancelar".
 */
@Composable
internal fun PhotoCropDialog(photo: ImageBitmap, onCancel: () -> Unit, onApply: (Rect) -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PhotoCropContent(photo, onCancel, onApply)
    }
}

/** What the dialog shows (apart from the dialog, so the tests can draw it). */
@Composable
internal fun PhotoCropContent(photo: ImageBitmap, onCancel: () -> Unit, onApply: (Rect) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C10))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // The round frame: the screen's width (inside the gutters), at most a comfortable size.
        val frameDp = (maxWidth - Dimens.Gutter * 2).coerceAtMost(420.dp)
        val frame = with(LocalDensity.current) { frameDp.toPx() }
        val math = remember(photo, frame) { FrameMath(photo.width, photo.height, frame) }
        var framing by remember(math) { mutableStateOf(math.start()) }
        val cropLabel = stringResource(R.string.crop_title)

        Column(Modifier.fillMaxSize().padding(horizontal = Dimens.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.crop_title), style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            Canvas(
                Modifier
                    .size(frameDp)
                    .semantics { contentDescription = cropLabel }
                    .pointerInput(math) {
                        detectTransformGestures { centroid, pan, zoom, _ -> framing = math.gesture(framing, pan, zoom, centroid) }
                    },
            ) {
                val sc = math.scale(framing.zoom)
                clipRect {
                    drawImage(
                        photo,
                        dstOffset = IntOffset(framing.offset.x.toInt(), framing.offset.y.toInt()),
                        dstSize = IntSize((photo.width * sc).toInt(), (photo.height * sc).toInt()),
                    )
                }
                // Dark outside the circle: what stays is what friends will see.
                val inset = 2.dp.toPx()
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset)))
                }
                drawPath(ring, Color.Black.copy(alpha = 0.62f))
                drawCircle(Color.White.copy(alpha = 0.9f), radius = size.minDimension / 2f - inset, style = Stroke(width = 2.dp.toPx()))
            }
            Spacer(Modifier.height(26.dp))
            // The zoom, also by hand.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ZoomOut, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                Slider(
                    value = framing.zoom,
                    onValueChange = { framing = math.zoomTo(framing, it) },
                    valueRange = 1f..FrameMath.MAX_ZOOM,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = SoundColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Icon(Icons.Rounded.ZoomIn, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                PersonButton(stringResource(R.string.crop_apply), onClick = { onApply(math.cut(framing)) }, filled = true, modifier = Modifier.weight(1f))
            }
        }
    }
}
