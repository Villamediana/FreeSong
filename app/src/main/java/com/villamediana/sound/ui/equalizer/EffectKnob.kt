package com.villamediana.sound.ui.equalizer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.villamediana.sound.R
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_DEG = 135f
private const val SWEEP_DEG = 270f
private const val STEP = 10            // 1 % of 0..1000
private val KnobSize = 116.dp

/**
 * A 270° dial for a 0..1000 strength: drag up or right to turn it up. Ticks every 10 %, a haptic
 * tick on each, a firmer one at the ends; springs to new values set from outside (reset).
 */
@Composable
internal fun EffectKnob(
    label: String,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "",
) {
    val haptic = LocalHapticFeedback.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val change by rememberUpdatedState(onChange)

    val animated = remember { Animatable(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableIntStateOf(value) }
    LaunchedEffect(value) {
        if (dragging) animated.snapTo(value.toFloat())
        else animated.animateTo(value.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
    }
    val shown = if (dragging) dragValue.toFloat() else animated.value

    val accent by animateColorAsState(if (enabled) SoundColors.Accent else SoundColors.TextFaint, Motion.enter(), label = "knob")
    val grip by animateFloatAsState(if (dragging) 1.04f else 1f, Motion.bouncy(), label = "grip")
    val percent = strengthPercent(shown.roundToInt())
    val percentText = resources.getString(R.string.eq_percent, percent)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(KnobSize)
                .scale(grip)
                .testTag(testTag)
                .semantics {
                    contentDescription = label
                    stateDescription = percentText
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..1000f)
                    if (enabled) {
                        setProgress { v ->
                            change(((v / STEP).roundToInt() * STEP).coerceIn(0, 1000))
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val perPx = 1000f / (180.dp.toPx())
                    var raw = 0f
                    fun finish() {
                        val v = dragValue.toFloat()
                        scope.launch { animated.snapTo(v) }
                        dragging = false
                    }
                    detectDragGestures(
                        onDragStart = {
                            raw = animated.value
                            dragValue = raw.roundToInt()
                            dragging = true
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                        onDrag = { pointer, delta ->
                            pointer.consume()
                            raw = (raw + (delta.x - delta.y) * perPx).coerceIn(0f, 1000f)
                            val next = ((raw / STEP).roundToInt() * STEP).coerceIn(0, 1000)
                            if (next != dragValue) {
                                val tick = when {
                                    next == 0 || next == 1000 -> HapticFeedbackType.SegmentTick
                                    next / 100 != dragValue / 100 -> HapticFeedbackType.SegmentFrequentTick
                                    else -> null
                                }
                                tick?.let { haptic.performHapticFeedback(it) }
                                dragValue = next
                                change(next)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val tickRoom = 9.dp.toPx()
                val arcInset = tickRoom + stroke / 2
                val arcSize = Size(size.width - arcInset * 2, size.height - arcInset * 2)
                val arcTopLeft = Offset(arcInset, arcInset)
                val c = center
                val outer = size.minDimension / 2
                val fraction = (shown / 1000f).coerceIn(0f, 1f)

                // Ticks every 10 %, lit up to the current value.
                for (t in 0..10) {
                    val deg = START_DEG + SWEEP_DEG * t / 10f
                    val rad = deg * PI.toFloat() / 180f
                    val lit = t / 10f <= fraction + 0.001f && fraction > 0f
                    val r1 = outer - 1.dp.toPx()
                    val r0 = outer - (if (t % 5 == 0) 5.dp else 3.5.dp).toPx()
                    drawLine(
                        if (lit) accent.copy(alpha = 0.7f) else SoundColors.Hairline,
                        Offset(c.x + cos(rad) * r0, c.y + sin(rad) * r0),
                        Offset(c.x + cos(rad) * r1, c.y + sin(rad) * r1),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                drawArc(SoundColors.Surface3, START_DEG, SWEEP_DEG, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0f) {
                    drawArc(accent, START_DEG, SWEEP_DEG * fraction, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                // The knob itself, with its pointer.
                val disc = arcSize.width / 2 - stroke - 5.dp.toPx()
                drawCircle(SoundColors.Surface1, radius = disc, center = c)
                drawCircle(SoundColors.Hairline, radius = disc, center = c, style = Stroke(1.dp.toPx()))
                val rad = (START_DEG + SWEEP_DEG * fraction) * PI.toFloat() / 180f
                val pr = disc - 6.dp.toPx()
                drawCircle(
                    if (enabled) SoundColors.Text else SoundColors.TextFaint,
                    radius = 3.dp.toPx(),
                    center = Offset(c.x + cos(rad) * pr, c.y + sin(rad) * pr),
                )
            }
            Text(
                percentText,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = if (enabled) SoundColors.Text else SoundColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = SoundColors.TextMuted, textAlign = TextAlign.Center)
    }
}
