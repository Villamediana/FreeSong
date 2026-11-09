package com.villamediana.sound.ui.components

import android.graphics.CornerPathEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * The play/pause glyph of the whole app: a soft triangle that turns into two round-ended bars (the
 * same roundness as the skip arrows beside it), morphing instead of swapping.
 */
@Composable
fun PlayPauseGlyph(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 650f), label = "glyph")
    Canvas(modifier) { drawPlayPause(t, tint) }
}

/**
 * Two quads (x0,y0 … x3,y3, unit square) that are the play triangle at [t] = 0 (split in two
 * halves) and the pause bars at [t] = 1.
 */
fun playPauseQuads(t: Float): Pair<FloatArray, FloatArray> {
    val k = t.coerceIn(0f, 1f)
    fun mix(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * k }
    return mix(PLAY_LEFT, PAUSE_LEFT) to mix(PLAY_RIGHT, PAUSE_RIGHT)
}

// Play: triangle (0.34,0.2) (0.34,0.8) (0.82,0.5), cut vertically at x = 0.58.
private val PLAY_LEFT = floatArrayOf(0.34f, 0.20f, 0.58f, 0.35f, 0.58f, 0.65f, 0.34f, 0.80f)
private val PLAY_RIGHT = floatArrayOf(0.58f, 0.35f, 0.82f, 0.50f, 0.82f, 0.50f, 0.58f, 0.65f)
private val PAUSE_LEFT = floatArrayOf(0.27f, 0.22f, 0.43f, 0.22f, 0.43f, 0.78f, 0.27f, 0.78f)
private val PAUSE_RIGHT = floatArrayOf(0.57f, 0.22f, 0.73f, 0.22f, 0.73f, 0.78f, 0.57f, 0.78f)

/** Corner radius, of the glyph's width: half a pause bar (round ends) and a soft triangle. */
private const val CORNER = 0.13f

private val glyphPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }

/** Draws the glyph at [t] (0 play … 1 pause) filling this area, corners rounded. */
fun DrawScope.drawPlayPause(t: Float, color: Color) {
    val w = size.width
    val h = size.height
    glyphPaint.color = color.toArgb()
    glyphPaint.pathEffect = CornerPathEffect(w * CORNER)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        if (t <= 0.001f) {
            // At rest the play is one triangle (two halves would show a notch where they meet).
            val path = android.graphics.Path().apply {
                moveTo(0.34f * w, 0.20f * h)
                lineTo(0.82f * w, 0.50f * h)
                lineTo(0.34f * w, 0.80f * h)
                close()
            }
            native.drawPath(path, glyphPaint)
            return@drawIntoCanvas
        }
        val (a, b) = playPauseQuads(t)
        for (q in arrayOf(a, b)) {
            val path = android.graphics.Path().apply {
                moveTo(q[0] * w, q[1] * h)
                lineTo(q[2] * w, q[3] * h)
                lineTo(q[4] * w, q[5] * h)
                lineTo(q[6] * w, q[7] * h)
                close()
            }
            native.drawPath(path, glyphPaint)
        }
    }
}
