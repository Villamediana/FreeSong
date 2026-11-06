package com.villamediana.sound.ui.equalizer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R
import com.villamediana.sound.playback.EqBand
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val GraphHeight = 252.dp
private val HandleRadius = 7.dp
private val HandleGrow = 3.dp

/**
 * The equalizer as a curve: a monotone spline through every band over a log-frequency axis,
 * softly filled underneath, with a handle per band that follows a vertical drag (sticky at 0 dB,
 * one haptic tick when crossing it, its value floating above while held). Preset changes arrive
 * as new targets and each band springs to its own.
 */
@Composable
internal fun EqCurveGraph(
    bands: List<EqBand>,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    onBandChange: (band: Int, levelMb: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val centers = bands.map { it.centerHz }
    key(centers, minMb, maxMb) {
        CurveGraph(bands, centers, minMb, maxMb, enabled, onBandChange, modifier)
    }
}

@Composable
private fun CurveGraph(
    bands: List<EqBand>,
    centers: List<Int>,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    onBandChange: (band: Int, levelMb: Int) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val onChange by rememberUpdatedState(onBandChange)

    val animated = remember { bands.map { Animatable(it.levelMb.toFloat()) } }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragValue by remember { mutableIntStateOf(0) }

    // Each band springs to its own target (presets, reset); the band under the finger just follows.
    bands.forEachIndexed { i, band ->
        key(i) {
            LaunchedEffect(band.levelMb) {
                val target = band.levelMb.toFloat()
                if (dragIndex == i) animated[i].snapTo(target)
                else animated[i].animateTo(target, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    val lifts: List<State<Float>> = bands.indices.map { i ->
        key(i) { animateFloatAsState(if (dragIndex == i) 1f else 0f, Motion.bouncy(), label = "lift") }
    }

    val line by animateColorAsState(if (enabled) SoundColors.Accent else SoundColors.TextFaint, Motion.enter(), label = "line")
    val fillAlpha by animateFloatAsState(if (enabled) 0.11f else 0.05f, Motion.enter(), label = "fill")

    fun levelOf(i: Int): Float = if (dragIndex == i) dragValue.toFloat() else animated[i].value

    BoxWithConstraints(modifier.fillMaxWidth().height(GraphHeight)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val geo = remember(w, h, density) {
            with(density) {
                EqGeometry(
                    centersHz = centers, minMb = minMb, maxMb = maxMb,
                    left = Dimens.Gutter.toPx() + 30.dp.toPx(),
                    top = 40.dp.toPx(),
                    right = w - Dimens.Gutter.toPx() - 4.dp.toPx(),
                    bottom = h - 30.dp.toPx(),
                )
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, geo) {
                    if (!enabled) return@pointerInput
                    var band = -1
                    var raw = 0f
                    fun finish() {
                        if (band < 0) return
                        val b = band
                        val v = dragValue.toFloat()
                        scope.launch { animated[b].snapTo(v) }
                        dragIndex = -1
                        band = -1
                    }
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            band = geo.nearestBand(offset.x)
                            raw = animated[band].value
                            dragValue = dragLevel(raw, minMb, maxMb)
                            dragIndex = band
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                        onVerticalDrag = { change, dy ->
                            if (band < 0) return@detectVerticalDragGestures
                            change.consume()
                            raw = (raw - dy * geo.mbPerPx()).coerceIn(minMb.toFloat(), maxMb.toFloat())
                            val next = dragLevel(raw, minMb, maxMb)
                            if (next != dragValue) {
                                if (crossesZero(dragValue, next)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                dragValue = next
                                onChange(band, next)
                            }
                        },
                    )
                },
        ) {
            if (geo.bandCount == 0) return@Canvas
            val ys = FloatArray(geo.bandCount) { geo.levelY(levelOf(it)) }
            drawGrid(geo, minMb, maxMb, locale, measurer)
            drawCurve(geo, ys, line, fillAlpha)
            for (i in 0 until geo.bandCount) {
                val lift = lifts[i].value
                drawHzLabel(geo, i, formatHz(centers[i], locale), lerp(SoundColors.TextFaint, SoundColors.Text, lift), measurer)
                drawHandle(Offset(geo.bandX(i), ys[i]), lift, line)
            }
            for (i in 0 until geo.bandCount) {
                val lift = lifts[i].value
                if (lift > 0.01f) {
                    val label = resources.getString(R.string.eq_db, formatDb(levelOf(i).roundToInt(), locale))
                    drawValueBubble(Offset(geo.bandX(i), ys[i]), label, lift, measurer)
                }
            }
        }

        // One accessible slider per band (TalkBack: frequency, level, adjustable).
        val columnWidth = 44.dp
        val halfColumnPx = with(density) { columnWidth.toPx() / 2 }
        bands.forEachIndexed { i, band ->
            val hz = resources.getString(R.string.eq_hz, formatHz(band.centerHz, locale))
            val db = resources.getString(R.string.eq_db, formatDb(band.levelMb, locale))
            Box(
                Modifier
                    .offset { IntOffset((geo.bandX(i) - halfColumnPx).roundToInt(), 0) }
                    .width(columnWidth)
                    .fillMaxHeight()
                    .testTag("eq_band_$i")
                    .semantics {
                        contentDescription = hz
                        stateDescription = db
                        progressBarRangeInfo = ProgressBarRangeInfo(band.levelMb.toFloat(), minMb.toFloat()..maxMb.toFloat())
                        if (enabled) {
                            setProgress { v ->
                                onChange(i, dragLevel(v, minMb, maxMb))
                                true
                            }
                        } else {
                            disabled()
                        }
                    },
            )
        }
    }
}

private val AxisStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.sp)
private val BubbleStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SoundColors.Ink)

private fun DrawScope.drawGrid(geo: EqGeometry, minMb: Int, maxMb: Int, locale: java.util.Locale, measurer: androidx.compose.ui.text.TextMeasurer) {
    val hair = 1.dp.toPx().coerceAtMost(1.5f)
    // Band columns.
    for (i in 0 until geo.bandCount) {
        val x = geo.bandX(i)
        drawLine(SoundColors.Hairline.copy(alpha = 0.55f), Offset(x, geo.top), Offset(x, geo.bottom), strokeWidth = hair)
    }
    // Level rows: the ends, halfway, and 0 dB a little stronger.
    val rows = buildList {
        add(maxMb); add(minMb)
        if (0 in minMb..maxMb) {
            add(0)
            if (maxMb > 0) add(maxMb / 2)
            if (minMb < 0) add(minMb / 2)
        }
    }.distinct()
    for (mb in rows) {
        val y = geo.levelY(mb.toFloat())
        val zero = mb == 0
        drawLine(
            if (zero) SoundColors.TextFaint.copy(alpha = 0.6f) else SoundColors.Hairline,
            Offset(geo.left, y), Offset(geo.right, y),
            strokeWidth = if (zero) 1.dp.toPx() else hair,
        )
        if (mb == maxMb || mb == minMb || zero) {
            val text = measurer.measure(formatDb(mb, locale), AxisStyle.copy(color = SoundColors.TextFaint))
            drawText(text, topLeft = Offset(geo.left - 8.dp.toPx() - text.size.width, y - text.size.height / 2f))
        }
    }
}

private fun DrawScope.drawCurve(geo: EqGeometry, ys: FloatArray, line: Color, fillAlpha: Float) {
    val n = ys.size
    // Flat beyond the outer bands, like the phone's own response.
    val kx = FloatArray(n + 2)
    val ky = FloatArray(n + 2)
    kx[0] = geo.left; ky[0] = ys[0]
    for (i in 0 until n) {
        kx[i + 1] = geo.bandX(i); ky[i + 1] = ys[i]
    }
    kx[n + 1] = geo.right; ky[n + 1] = ys[n - 1]
    val m = monotoneTangents(kx, ky)
    val curve = Path().apply {
        moveTo(kx[0], ky[0])
        for (i in 0 until kx.size - 1) {
            val dx = kx[i + 1] - kx[i]
            if (dx <= 0f) {
                lineTo(kx[i + 1], ky[i + 1])
                continue
            }
            cubicTo(
                kx[i] + dx / 3f, ky[i] + m[i] * dx / 3f,
                kx[i + 1] - dx / 3f, ky[i + 1] - m[i + 1] * dx / 3f,
                kx[i + 1], ky[i + 1],
            )
        }
    }
    val fill = Path().apply {
        addPath(curve)
        lineTo(geo.right, geo.bottom)
        lineTo(geo.left, geo.bottom)
        close()
    }
    drawPath(fill, line.copy(alpha = fillAlpha))
    drawPath(curve, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawHzLabel(geo: EqGeometry, i: Int, text: String, color: Color, measurer: androidx.compose.ui.text.TextMeasurer) {
    val layout = measurer.measure(text, AxisStyle.copy(color = color))
    drawText(layout, topLeft = Offset(geo.bandX(i) - layout.size.width / 2f, geo.bottom + 10.dp.toPx()))
}

private fun DrawScope.drawHandle(center: Offset, lift: Float, line: Color) {
    if (lift > 0f) drawCircle(line.copy(alpha = 0.14f * lift), radius = (HandleRadius + 13.dp * lift).toPx(), center = center)
    val r = (HandleRadius + HandleGrow * lift).toPx()
    drawCircle(lerp(SoundColors.Ink, line, lift), radius = r, center = center)
    drawCircle(line, radius = r, center = center, style = Stroke(width = 2.5.dp.toPx()))
}

private fun DrawScope.drawValueBubble(anchor: Offset, label: String, lift: Float, measurer: androidx.compose.ui.text.TextMeasurer) {
    val text = measurer.measure(label, BubbleStyle)
    val padH = 10.dp.toPx()
    val padV = 5.dp.toPx()
    val bubble = Size(text.size.width + padH * 2, text.size.height + padV * 2)
    val gap = (HandleRadius + HandleGrow + 10.dp).toPx()
    var top = anchor.y - gap - bubble.height + (1f - lift) * 6.dp.toPx()
    if (top < 0f) top = anchor.y + gap
    val left = (anchor.x - bubble.width / 2f).coerceIn(0f, size.width - bubble.width)
    drawRoundRect(
        SoundColors.Text.copy(alpha = lift),
        topLeft = Offset(left, top),
        size = bubble,
        cornerRadius = CornerRadius(bubble.height / 2f),
    )
    drawText(text, topLeft = Offset(left + padH, top + padV), alpha = lift)
}
