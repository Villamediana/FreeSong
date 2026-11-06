package com.villamediana.surco.ui.equalizer

import androidx.annotation.StringRes
import com.villamediana.surco.R
import com.villamediana.surco.playback.EqPreset
import com.villamediana.surco.playback.EqState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign

// ------------------------------------------------------------------ labels

@StringRes
internal fun presetLabel(preset: EqPreset?): Int = when (preset) {
    null -> R.string.eq_preset_custom
    EqPreset.FLAT -> R.string.eq_preset_flat
    EqPreset.BASS -> R.string.eq_preset_bass
    EqPreset.TREBLE -> R.string.eq_preset_treble
    EqPreset.VOCAL -> R.string.eq_preset_vocal
    EqPreset.ROCK -> R.string.eq_preset_rock
    EqPreset.POP -> R.string.eq_preset_pop
    EqPreset.ELECTRONIC -> R.string.eq_preset_electronic
    EqPreset.HIPHOP -> R.string.eq_preset_hiphop
    EqPreset.LATIN -> R.string.eq_preset_latin
    EqPreset.JAZZ -> R.string.eq_preset_jazz
    EqPreset.CLASSICAL -> R.string.eq_preset_classical
    EqPreset.ACOUSTIC -> R.string.eq_preset_acoustic
    EqPreset.LATE_NIGHT -> R.string.eq_preset_late_night
}

/** "60", "230", "910", "3,6k", "14k" (decimal separator of [locale]). */
internal fun formatHz(hz: Int, locale: Locale): String {
    if (hz < 1000) return hz.toString()
    val tenths = (hz / 100.0).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}k" else String.format(locale, "%.1fk", tenths / 10.0)
}

/** Level in dB from mB: "0", "+3", "+3,5", "−12" (true minus sign, one decimal only when needed). */
internal fun formatDb(levelMb: Int, locale: Locale): String {
    val tenths = (levelMb / 10.0).roundToInt()
    if (tenths == 0) return "0"
    val sign = if (tenths > 0) "+" else "−"
    val a = abs(tenths)
    val number = if (a % 10 == 0) (a / 10).toString() else String.format(locale, "%.1f", a / 10.0)
    return sign + number
}

/** 0..1000 → 0..100 %. */
internal fun strengthPercent(strength: Int): Int = (strength.coerceIn(0, 1000) / 10.0).roundToInt()

/** "Restaurar" does something only when the sound differs from the defaults. */
internal fun canReset(s: EqState): Boolean = s.preset != EqPreset.FLAT || s.bassBoost > 0 || s.virtualizer > 0

// ------------------------------------------------------------------ dragging

/** Levels within this distance of 0 dB stick to it, so flat is easy to find by touch. */
internal const val ZERO_SNAP_MB = 40f

/** A dragged level: clamped, magnetic around 0 dB, in 0.1 dB steps. */
internal fun dragLevel(rawMb: Float, minMb: Int, maxMb: Int): Int {
    val clamped = rawMb.coerceIn(minMb.toFloat(), maxMb.toFloat())
    if (abs(clamped) <= ZERO_SNAP_MB) return 0
    return ((clamped / 10f).roundToInt() * 10).coerceIn(minMb, maxMb)
}

/** True when going from [from] to [to] lands on or passes through 0 dB (one haptic tick). */
internal fun crossesZero(from: Int, to: Int): Boolean =
    from != to && (to == 0 || (from != 0 && from.sign != to.sign))

// ------------------------------------------------------------------ geometry

/**
 * Where things sit in the curve graph: bands over a log-frequency axis with half a step of air on
 * each side, levels on a linear dB axis. Pure, so the drawing and the drag agree exactly.
 */
internal class EqGeometry(
    centersHz: List<Int>,
    private val minMb: Int,
    private val maxMb: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    private val logs = centersHz.map { ln(it.coerceAtLeast(1).toDouble()) }
    private val lo: Double
    private val hi: Double

    init {
        if (logs.isEmpty()) {
            lo = 0.0; hi = 1.0
        } else {
            val span = logs.last() - logs.first()
            val half = if (logs.size > 1 && span > 0) span / (logs.size - 1) / 2 else 1.0
            lo = logs.first() - half
            hi = logs.last() + half
        }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val bandCount: Int get() = logs.size

    fun bandX(index: Int): Float = left + ((logs[index] - lo) / (hi - lo)).toFloat() * width

    fun levelY(levelMb: Float): Float {
        val range = (maxMb - minMb).toFloat().takeIf { it > 0f } ?: 1f
        return top + (maxMb - levelMb) / range * height
    }

    /** mB per pixel of vertical drag (dragging up raises the level). */
    fun mbPerPx(): Float = (maxMb - minMb) / height.coerceAtLeast(1f)

    /** The band whose column is closest to [x]. */
    fun nearestBand(x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until bandCount) {
            val d = abs(bandX(i) - x)
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }
}

/**
 * Tangents for a monotone cubic Hermite spline through ([xs], [ys]) (Fritsch–Carlson): smooth,
 * and never overshoots between two points — the drawn curve never promises a boost the phone
 * isn't doing. [xs] ascending; equal xs give flat tangents.
 */
internal fun monotoneTangents(xs: FloatArray, ys: FloatArray): FloatArray {
    val n = xs.size
    val m = FloatArray(n)
    if (n < 2) return m
    val d = FloatArray(n - 1) { i ->
        val h = xs[i + 1] - xs[i]
        if (h > 0f) (ys[i + 1] - ys[i]) / h else 0f
    }
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
            continue
        }
        val a = m[i] / d[i]
        val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / kotlin.math.sqrt(s)
            m[i] = t * a * d[i]
            m[i + 1] = t * b * d[i]
        }
    }
    return m
}

/** Point of the Hermite segment i..i+1 at t ∈ [0,1] (for tests and hit math). */
internal fun hermite(xs: FloatArray, ys: FloatArray, m: FloatArray, i: Int, t: Float): Float {
    val h = xs[i + 1] - xs[i]
    val t2 = t * t
    val t3 = t2 * t
    return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
}
