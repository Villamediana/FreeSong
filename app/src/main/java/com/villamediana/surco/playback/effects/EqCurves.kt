package com.villamediana.surco.playback.effects

import com.villamediana.surco.playback.EqPreset
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Surco's equalizer curves, independent of any phone: each preset is a target gain (dB) over
 * frequency, drawn on an octave grid. A phone exposes its own bands (5 on most, 10 on some, with
 * odd centres like 60/230/910/3600/14000 Hz), so the curve is sampled at each band's centre,
 * interpolating linearly in log-frequency, and clamped to the band level range the phone allows.
 *
 * Custom curves are kept by centre frequency (Hz → mB) for the same reason: a curve tuned on a
 * 5-band phone still means something on a 10-band one.
 */
object EqCurves {

    /** Octave grid the presets are drawn on (ISO centres, 31.5 Hz … 16 kHz). */
    val GRID_HZ: DoubleArray = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)

    /** Target gain (dB) of [preset] at each point of [GRID_HZ]. Kept within ±6 dB so nothing clips hard. */
    fun curve(preset: EqPreset): DoubleArray = when (preset) {
        //                                 31.5   63    125   250   500    1k    2k    4k    8k   16k
        EqPreset.FLAT -> doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        // Weight under 250 Hz; nothing above.
        EqPreset.BASS -> doubleArrayOf(6.0, 6.0, 4.5, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        // Air and presence, rising with frequency.
        EqPreset.TREBLE -> doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 2.0, 4.0, 5.5, 6.0)
        // Lifts 1–4 kHz where the voice lives; leaner lows so it steps forward.
        EqPreset.VOCAL -> doubleArrayOf(-2.5, -2.0, -1.0, 0.0, 1.5, 3.0, 4.0, 3.0, 1.0, -0.5)
        // The classic smile: kick and guitars' bite, scooped low mids.
        EqPreset.ROCK -> doubleArrayOf(5.0, 4.5, 3.0, 1.0, -1.0, -1.5, 0.5, 2.5, 4.0, 4.5)
        // Warm low mids, a touch of sparkle.
        EqPreset.POP -> doubleArrayOf(-1.0, -0.5, 1.0, 2.5, 3.0, 2.0, 0.5, -0.5, 1.0, 1.5)
        // Sub bass and shimmering highs, mids out of the way.
        EqPreset.ELECTRONIC -> doubleArrayOf(5.5, 5.0, 3.0, 0.0, -1.5, -1.0, 0.5, 2.0, 3.5, 4.5)
        // Deep 808s, clear vocal edge.
        EqPreset.HIPHOP -> doubleArrayOf(6.0, 5.5, 3.5, 1.0, -1.0, -0.5, 1.0, 1.5, 1.0, 2.0)
        // Punchy lows (kick, bass, congas) and crisp highs (güiro, cowbell, hats).
        EqPreset.LATIN -> doubleArrayOf(4.0, 4.5, 3.0, 0.5, -1.0, -0.5, 1.0, 2.5, 3.5, 3.5)
        // Round double bass, soft mids, silky cymbals.
        EqPreset.JAZZ -> doubleArrayOf(3.0, 2.5, 1.5, 1.0, 1.5, -0.5, -1.0, 0.0, 1.5, 2.5)
        // Hall weight and air, untouched middle.
        EqPreset.CLASSICAL -> doubleArrayOf(3.5, 3.0, 2.0, 1.0, 0.0, -0.5, -0.5, 0.0, 2.0, 3.0)
        // Body of the guitar and the detail of the strings.
        EqPreset.ACOUSTIC -> doubleArrayOf(3.0, 2.5, 1.5, 0.5, 1.0, 1.0, 1.5, 2.5, 2.5, 1.5)
        // Softened lows and highs, mids a little forward: easy listening at low volume.
        EqPreset.LATE_NIGHT -> doubleArrayOf(-4.5, -4.0, -2.5, -0.5, 0.5, 1.0, 1.0, 0.0, -2.0, -3.5)
    }

    /** Gain (dB) of [preset] at [hz]. */
    fun gainAt(preset: EqPreset, hz: Double): Double = interpolateLog(GRID_HZ, curve(preset), hz)

    /**
     * Value at [hz] of the polyline through ([xsHz], [ys]) drawn over log-frequency. Flat beyond
     * both ends. [xsHz] must be ascending and positive.
     */
    fun interpolateLog(xsHz: DoubleArray, ys: DoubleArray, hz: Double): Double {
        require(xsHz.size == ys.size && xsHz.isNotEmpty())
        if (hz <= xsHz.first()) return ys.first()
        if (hz >= xsHz.last()) return ys.last()
        var i = 1
        while (xsHz[i] < hz) i++
        val x0 = ln(xsHz[i - 1])
        val x1 = ln(xsHz[i])
        if (x1 <= x0) return ys[i]
        val t = (ln(hz) - x0) / (x1 - x0)
        return ys[i - 1] + (ys[i] - ys[i - 1]) * t
    }

    /** [preset] on a phone whose bands are centred at [centersHz], in mB within [minMb]..[maxMb]. */
    fun presetLevels(preset: EqPreset, centersHz: List<Int>, minMb: Int, maxMb: Int): List<Int> =
        centersHz.map { hz -> clampLevel((gainAt(preset, hz.toDouble()) * 100).roundToInt(), minMb, maxMb) }

    /** A phone's band levels keyed by centre frequency — the portable form of a custom curve. */
    fun levelsByFrequency(centersHz: List<Int>, levelsMb: List<Int>): Map<Int, Int> {
        require(centersHz.size == levelsMb.size)
        return centersHz.zip(levelsMb).toMap()
    }

    /**
     * A custom curve saved by frequency, rebuilt on a phone whose bands are centred at
     * [centersHz]: exact where a centre matches, interpolated in log-frequency in between, flat
     * beyond the saved range, clamped to the phone's range. Nothing saved → flat.
     */
    fun levelsFromFrequencies(saved: Map<Int, Int>, centersHz: List<Int>, minMb: Int, maxMb: Int): List<Int> {
        val points = saved.filterKeys { it > 0 }.toSortedMap()
        if (points.isEmpty()) return centersHz.map { clampLevel(0, minMb, maxMb) }
        val xs = points.keys.map { it.toDouble() }.toDoubleArray()
        val ys = points.values.map { it.toDouble() }.toDoubleArray()
        return centersHz.map { hz ->
            val exact = points[hz]
            val mb = exact ?: interpolateLog(xs, ys, hz.toDouble()).roundToInt()
            clampLevel(mb, minMb, maxMb)
        }
    }

    fun clampLevel(levelMb: Int, minMb: Int, maxMb: Int): Int =
        if (minMb > maxMb) levelMb else levelMb.coerceIn(minMb, maxMb)
}
