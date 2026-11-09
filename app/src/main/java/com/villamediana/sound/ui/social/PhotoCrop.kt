package com.villamediana.sound.ui.social

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
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
 * "Ajustar foto": the picked photo edge to edge behind a big round frame — drag it, pinch or use
 * the bar to zoom — with a live preview of how friends will see it, then "Aplicar" (the square
 * around the circle becomes the photo); the × leaves it as it was.
 */
@Composable
internal fun PhotoCropDialog(photo: ImageBitmap, onCancel: () -> Unit, onApply: (Rect) -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PhotoCropContent(photo, onCancel, onApply)
    }
}

private val CropInk = Color(0xFF07080B)

/** What the dialog shows (apart from the dialog, so the tests can draw it). */
@Composable
internal fun PhotoCropContent(photo: ImageBitmap, onCancel: () -> Unit, onApply: (Rect) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(CropInk)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // The frame: the whole width (the photo runs edge to edge), at most a comfortable size.
        val frameDp = maxWidth.coerceAtMost(460.dp)
        // The circle sits a little inside the frame (room for its halo): the framing is the circle's.
        val ringInset = 14.dp
        val insetPx = with(LocalDensity.current) { ringInset.toPx() }
        val frame = with(LocalDensity.current) { (frameDp - ringInset * 2).toPx() }
        val math = remember(photo, frame) { FrameMath(photo.width, photo.height, frame) }
        var framing by remember(math) { mutableStateOf(math.start()) }
        val cropLabel = stringResource(R.string.crop_title)

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // × · title
            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Text(
                    stringResource(R.string.crop_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Spacer(Modifier.weight(0.6f))
            Canvas(
                Modifier
                    .size(frameDp)
                    .clipToBounds()
                    .semantics { contentDescription = cropLabel }
                    .pointerInput(math) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            framing = math.gesture(framing, pan, zoom, centroid - Offset(insetPx, insetPx))
                        }
                    },
            ) {
                translate(insetPx, insetPx) { drawFramed(photo, math, framing) }
                // Outside the circle almost dark: what stays is what friends will see.
                val inset = insetPx
                val circle = Rect(Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset))
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(circle)
                }
                drawPath(mask, CropInk.copy(alpha = 0.78f))
                drawCircle(Color.White.copy(alpha = 0.16f), radius = circle.width / 2f + 5.dp.toPx(), center = circle.center, style = Stroke(width = 8.dp.toPx()))
                drawCircle(Color.White, radius = circle.width / 2f, center = circle.center, style = Stroke(width = 2.dp.toPx()))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.crop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Dimens.Gutter),
            )
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ZoomOut, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                ZoomBar(
                    value = (framing.zoom - 1f) / (FrameMath.MAX_ZOOM - 1f),
                    onValue = { framing = math.zoomTo(framing, 1f + it * (FrameMath.MAX_ZOOM - 1f)) },
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                )
                Icon(Icons.Rounded.ZoomIn, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(0.4f))
            // How it will look: on your card, in the friends list, in a jam.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(64.dp, 44.dp, 30.dp).forEach { side ->
                    Canvas(Modifier.size(side).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                        val k = size.width / frame
                        scale(k, pivot = Offset.Zero) { drawFramed(photo, math, framing) }
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text(stringResource(R.string.crop_preview), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f))
            }
            Spacer(Modifier.height(22.dp))
            PersonButton(
                stringResource(R.string.crop_apply),
                onClick = { onApply(math.cut(framing)) },
                filled = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter),
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** The photo where the framing puts it, in frame pixels (the canvas clips it to its own shape). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFramed(photo: ImageBitmap, math: FrameMath, framing: Framing) {
    val sc = math.scale(framing.zoom)
    drawImage(
        photo,
        dstOffset = IntOffset(framing.offset.x.toInt(), framing.offset.y.toInt()),
        dstSize = IntSize((photo.width * sc).toInt(), (photo.height * sc).toInt()),
    )
}

/** A thin bar with a round handle: drag or tap anywhere on it. [value] 0..1. */
@Composable
private fun ZoomBar(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { onValue((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onValue((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
        Box(Modifier.width(width * value).height(4.dp).clip(CircleShape).background(SoundColors.Accent))
        Box(
            Modifier
                .padding(start = (width - 22.dp) * value)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
