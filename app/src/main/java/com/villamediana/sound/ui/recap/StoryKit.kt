package com.villamediana.sound.ui.recap

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.sound.ui.components.Cover
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The story's night: always dark, whatever the app's theme (a screening, not a page). */
internal val StoryInk = Color(0xFF07080C)
internal val StoryText = Color(0xFFF7F7FA)
internal val StoryMuted = Color(0xFFF7F7FA).copy(alpha = 0.66f)

// ---------------------------------------------------------------- time and easing
// Every scene is a function of t (seconds into its chapter): pausing stops t, and a test can show any moment.

internal fun clamp01(x: Float) = x.coerceIn(0f, 1f)

/** 0→1 between [start] and [start] + [dur]. */
internal fun phase(t: Float, start: Float, dur: Float) = clamp01((t - start) / dur)

internal fun easeOut(x: Float): Float = 1f - (1f - clamp01(x)).pow(3)

internal fun easeOutExpo(x: Float): Float = clamp01(x).let { if (it >= 1f) 1f else 1f - 2f.pow(-10f * it) }

internal fun easeInOut(x: Float): Float = clamp01(x).let { if (it < 0.5f) 4f * it * it * it else 1f - (-2f * it + 2f).pow(3) / 2f }

/** Overshoots a little and settles. */
internal fun backOut(x: Float, s: Float = 1.6f): Float = (clamp01(x) - 1f).let { c -> c * c * ((s + 1f) * c + s) + 1f }

/** Fades and lifts in from [start]. */
internal fun Modifier.rise(t: Float, start: Float, dur: Float = 0.7f, distance: Dp = 26.dp): Modifier = graphicsLayer {
    val e = easeOut(phase(t, start, dur))
    alpha = e
    translationY = (1f - e) * distance.toPx()
}

/** Grows in from [from] with a small overshoot. */
internal fun Modifier.pop(t: Float, start: Float, dur: Float = 0.9f, from: Float = 0.55f): Modifier = graphicsLayer {
    val p = phase(t, start, dur)
    val s = from + (1f - from) * backOut(p)
    scaleX = s
    scaleY = s
    alpha = easeOut(p * 1.8f)
}

/** Slides in sideways from [start] ([fromRight]: from the right edge). */
internal fun Modifier.slide(t: Float, start: Float, dur: Float = 0.75f, distance: Dp = 90.dp, fromRight: Boolean = true): Modifier = graphicsLayer {
    val e = easeOut(phase(t, start, dur))
    alpha = e
    translationX = (1f - e) * distance.toPx() * (if (fromRight) 1f else -1f)
}

internal fun formatCount(n: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

// ---------------------------------------------------------------- color

/** [c] turned [degrees] around the color wheel, kept vivid and mid-bright for the dark. */
internal fun hueShift(c: Color, degrees: Float, saturation: Float = 0.62f, value: Float = 0.78f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    hsv[1] = max(hsv[1], saturation).coerceAtMost(0.9f)
    hsv[2] = value
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Three colors of a scene's light. */
internal data class Glow(val a: Color, val b: Color, val c: Color)

// ---------------------------------------------------------------- the backdrop

private val GrainA: List<Offset> = Random(7).let { r -> List(900) { Offset(r.nextFloat(), r.nextFloat()) } }
private val GrainB: List<Offset> = Random(11).let { r -> List(900) { Offset(r.nextFloat(), r.nextFloat()) } }

/**
 * Slow light behind every chapter: three soft blobs drifting, the colors gliding from chapter to
 * chapter, film grain and a vignette, drifting with the story's clock.
 */
@Composable
internal fun Backdrop(glow: Glow, story: StoryState, modifier: Modifier = Modifier) {
    // Read only while drawing: the drift redraws, it never recomposes.
    val glowA = animateColorAsState(glow.a, tween(1100), label = "glowA")
    val glowB = animateColorAsState(glow.b, tween(1100), label = "glowB")
    val glowC = animateColorAsState(glow.c, tween(1100), label = "glowC")
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            // Worked out once per size: the grain fields in pixels and the vignette.
            val w = size.width
            val h = size.height
            val r = max(w, h)
            val grainA = GrainA.map { Offset(it.x * w, it.y * h) }
            val grainB = GrainB.map { Offset(it.x * w, it.y * h) }
            val vignette = Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)), Offset(w / 2f, h * 0.45f), r * 0.82f)
            val grainSize = 1.6.dp.toPx()
            onDrawBehind {
                val clock = story.clock
                val a = glowA.value
                val b = glowB.value
                val c = glowC.value
                drawRect(StoryInk)
                blob(a, Offset(w * (0.22f + 0.14f * sin(clock * 0.31f)), h * (0.20f + 0.08f * cos(clock * 0.27f))), r * 0.72f, 0.62f)
                blob(b, Offset(w * (0.88f + 0.10f * cos(clock * 0.23f)), h * (0.55f + 0.12f * sin(clock * 0.19f))), r * 0.66f, 0.50f)
                blob(c, Offset(w * (0.30f + 0.18f * sin(clock * 0.17f + 1f)), h * (0.98f + 0.05f * cos(clock * 0.29f))), r * 0.62f, 0.46f)
                // Grain: two fixed fields swapped 12 times a second (film, not static).
                drawPoints(if ((clock * 12f).toInt() % 2 == 0) grainA else grainB, PointMode.Points, Color.White.copy(alpha = 0.05f), strokeWidth = grainSize)
                drawRect(vignette)
            }
        },
    )
}

private fun DrawScope.blob(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center, radius), radius, center)
}

/** Rings opening out of [center] (fraction of the area), one after another. */
@Composable
internal fun Ripples(clock: Float, color: Color, modifier: Modifier = Modifier, center: Offset = Offset(0.5f, 0.5f), count: Int = 4, period: Float = 3.2f, strength: Float = 1f) {
    Canvas(modifier.fillMaxSize()) {
        val c = Offset(size.width * center.x, size.height * center.y)
        val far = max(size.width, size.height) * 0.75f
        repeat(count) { i ->
            val p = ((clock / period) + i.toFloat() / count) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.32f * strength), radius = 40.dp.toPx() + p * far, center = c, style = Stroke((1.5f + (1f - p) * 2f).dp.toPx()))
        }
    }
}

/** A burst of white that fades: the "here it is" of a reveal. */
@Composable
internal fun Flash(t: Float, at: Float, modifier: Modifier = Modifier) {
    val p = phase(t, at, 0.55f)
    if (p <= 0f || p >= 1f) return
    Box(modifier.fillMaxSize().graphicsLayer { alpha = (1f - p) * 0.55f }.background(Color.White))
}

// ---------------------------------------------------------------- type

/** Words arriving one after another, centered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Kinetic(
    text: String,
    t: Float,
    start: Float,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = StoryText,
    step: Float = 0.075f,
) {
    val words = remember(text) { text.split(' ').filter { it.isNotEmpty() } }
    val gap = with(LocalDensity.current) { (style.fontSize * 0.26f).toDp() }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
    ) {
        words.forEachIndexed { i, w ->
            Text(w, style = style, color = color, textAlign = TextAlign.Center, modifier = Modifier.rise(t, start + i * step, 0.6f, 20.dp))
        }
    }
}

// ---------------------------------------------------------------- objects

/** A record turning, the cover on its label; the light on it stays still. */
@Composable
internal fun Vinyl(cover: String?, seed: String, size: Dp, angle: Float, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).graphicsLayer { rotationZ = angle }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f
                drawCircle(Color(0xFF0C0C11), r)
                for (i in 0 until 28) {
                    drawCircle(Color.White.copy(alpha = 0.03f + (i % 3) * 0.012f), r * (0.40f + i * 0.021f), style = Stroke(1.dp.toPx()))
                }
                drawCircle(Color.White.copy(alpha = 0.10f), r, style = Stroke(1.5.dp.toPx()))
            }
            Cover(cover, Modifier.size(size * 0.42f), seed = seed, shape = CircleShape)
            Box(Modifier.size(size * 0.045f).clip(CircleShape).background(StoryInk))
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent, 0.10f to Color.White.copy(alpha = 0.10f), 0.22f to Color.Transparent,
                    0.55f to Color.Transparent, 0.62f to Color.White.copy(alpha = 0.07f), 0.74f to Color.Transparent, 1f to Color.Transparent,
                ),
                this.size.minDimension / 2f,
            )
        }
    }
}

/** Bars dancing like a meter; [energy] 0 (still) to 1 (loud). */
@Composable
internal fun Meter(clock: Float, energy: Float, color: Color, modifier: Modifier = Modifier, count: Int = 26) {
    Canvas(modifier) {
        val gap = 4.dp.toPx()
        val bw = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val wobble = abs(sin(clock * (3.0f + (i % 5) * 0.65f) + i * 0.9f)) * (0.55f + 0.45f * abs(sin(i * 1.7f)))
            val h = (0.10f + 0.90f * energy * wobble) * size.height
            drawRoundRect(
                color,
                topLeft = Offset(i * (bw + gap), (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f),
            )
        }
    }
}

/** A sound wave drawn across, left to right ([drawn] 0→1), breathing with [clock]. */
@Composable
internal fun WaveLine(clock: Float, drawn: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val steps = 160
        val mid = size.height / 2f
        val path = androidx.compose.ui.graphics.Path()
        val last = (steps * clamp01(drawn)).toInt()
        for (i in 0..last) {
            val x = size.width * i / steps
            val u = i.toFloat() / steps
            val envelope = sin(u * Math.PI).toFloat()
            val y = mid + envelope * mid * 0.85f * (0.6f * sin(u * 18f + clock * 2.4f) + 0.4f * sin(u * 41f - clock * 3.1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
