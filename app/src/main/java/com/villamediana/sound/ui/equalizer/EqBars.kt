package com.villamediana.sound.ui.equalizer

import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.playback.EqBand
import com.villamediana.sound.ui.theme.SoundColors

/**
 * One vertical slider per band, like a mixing desk: dB on top, frequency below, the level as a
 * mint bar growing from the 0 dB line. Drag up/down (0 dB is magnetic); preset changes glide.
 */
@Composable
internal fun EqBars(
    bands: List<EqBand>,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    onBandChange: (band: Int, levelMb: Int) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 230.dp,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        bands.forEach { band ->
            BandSlider(band, minMb, maxMb, enabled, onBandChange, trackHeight, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BandSlider(
    band: EqBand,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    onBandChange: (Int, Int) -> Unit,
    trackHeight: Dp,
    modifier: Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = remember { Animatable(band.levelMb.toFloat()) }
    // Under the finger the bar is where the finger is, and stays there when it lets go; any other
    // change (a preset, "Restaurar") glides there.
    LaunchedEffect(band.levelMb, dragging) {
        val held = dragging
        if (held != null) shown.snapTo(held.toFloat())
        else shown.animateTo(band.levelMb.toFloat(), spring(dampingRatio = 0.8f, stiffness = 300f))
    }
    val change by rememberUpdatedState(onBandChange)
    val level = dragging ?: band.levelMb
    val range = (maxMb - minMb).coerceAtLeast(1)
    val alpha = if (enabled) 1f else 0.38f

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatDb(level, locale),
            style = MaterialTheme.typography.labelMedium,
            color = SoundColors.Text.copy(alpha = 0.72f * alpha),
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .testTag("eq_band_${band.index}")
                .semantics {
                    contentDescription = formatHz(band.centerHz, locale) + " Hz"
                    stateDescription = formatDb(level, locale) + " dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), minMb.toFloat()..maxMb.toFloat())
                    if (enabled) {
                        setProgress { v -> change(band.index, dragLevel(v, minMb, maxMb)); true }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(enabled, minMb, maxMb) {
                    if (!enabled) return@pointerInput
                    fun at(y: Float) = dragLevel(maxMb - (y / size.height) * range, minMb, maxMb)
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            val v = at(o.y)
                            dragging = v
                        },
                        onDragEnd = {
                            dragging?.let { change(band.index, it) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                        onVerticalDrag = { c, _ ->
                            c.consume()
                            val prev = dragging ?: band.levelMb
                            val v = at(c.position.y)
                            if (v != prev) {
                                if (crossesZero(prev, v)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                dragging = v
                                change(band.index, v)
                            }
                        },
                    )
                }
                .pointerInput(enabled, minMb, maxMb) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o -> change(band.index, dragLevel(maxMb - (o.y / size.height) * range, minMb, maxMb)) }
                }
                .drawBehind {
                    val cx = size.width / 2
                    fun y(mb: Float) = (maxMb - mb) / range * size.height
                    val v = dragging?.toFloat() ?: shown.value
                    // the track
                    drawLine(SoundColors.Text.copy(alpha = 0.16f * alpha), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    // the level, from 0 dB
                    val bar = 11.dp.toPx()
                    val y0 = y(0f)
                    val yv = y(v)
                    val color = SoundColors.Mint.copy(alpha = alpha)
                    if (kotlin.math.abs(yv - y0) < bar * 0.6f) {
                        drawCircle(color, radius = bar / 2 + 1.dp.toPx(), center = Offset(cx, y0))
                    } else {
                        drawLine(color, Offset(cx, y0), Offset(cx, yv), strokeWidth = bar, cap = StrokeCap.Round)
                    }
                },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            formatHz(band.centerHz, locale).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = SoundColors.Text.copy(alpha = 0.72f * alpha),
            maxLines = 1,
        )
    }
}
