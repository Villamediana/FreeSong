package com.villamediana.sound.ui.equalizer

import com.villamediana.sound.R
import com.villamediana.sound.playback.EqPreset
import com.villamediana.sound.playback.EqState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class EqMathTest {
    private val br = Locale.forLanguageTag("pt-BR")
    private val es = Locale.forLanguageTag("es")

    @Test
    fun hertzLabels() {
        assertEquals("60", formatHz(60, br))
        assertEquals("230", formatHz(230, br))
        assertEquals("910", formatHz(910, br))
        assertEquals("1k", formatHz(1_000, br))
        assertEquals("3,6k", formatHz(3_600, br))
        assertEquals("3,6k", formatHz(3_600, es))
        assertEquals("3.6k", formatHz(3_600, Locale.US))
        assertEquals("14k", formatHz(14_000, br))
        assertEquals("16k", formatHz(15_990, br))
    }

    @Test
    fun decibelLabels() {
        assertEquals("0", formatDb(0, br))
        assertEquals("0", formatDb(4, br))
        assertEquals("+3", formatDb(300, br))
        assertEquals("+3,5", formatDb(350, br))
        assertEquals("−12", formatDb(-1200, br))
        assertEquals("−0,8", formatDb(-80, br))
        assertEquals("+15", formatDb(1500, br))
    }

    @Test
    fun presetNamesAreAllMapped() {
        val ids = EqPreset.entries.map { presetLabel(it) } + presetLabel(null)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.eq_preset_custom, presetLabel(null))
        assertEquals(R.string.eq_preset_late_night, presetLabel(EqPreset.LATE_NIGHT))
    }

    @Test
    fun percentAndReset() {
        assertEquals(0, strengthPercent(0))
        assertEquals(45, strengthPercent(450))
        assertEquals(100, strengthPercent(1000))
        assertEquals(100, strengthPercent(2000))
        assertFalse(canReset(EqState(preset = EqPreset.FLAT)))
        assertTrue(canReset(EqState(preset = EqPreset.ROCK)))
        assertTrue(canReset(EqState(preset = null)))
        assertTrue(canReset(EqState(bassBoost = 10)))
        assertTrue(canReset(EqState(virtualizer = 10)))
    }

    @Test
    fun draggingIsMagneticAtZeroAndClamped() {
        assertEquals(0, dragLevel(30f, -1500, 1500))
        assertEquals(0, dragLevel(-39f, -1500, 1500))
        assertEquals(50, dragLevel(46f, -1500, 1500))
        assertEquals(-120, dragLevel(-123f, -1500, 1500))
        assertEquals(1500, dragLevel(9_000f, -1500, 1500))
        assertEquals(-1200, dragLevel(-9_000f, -1200, 1200))
    }

    @Test
    fun zeroCrossingTicksOnce() {
        assertTrue(crossesZero(120, 0))
        assertTrue(crossesZero(-50, 60))
        assertTrue(crossesZero(60, -50))
        assertFalse(crossesZero(0, 50))
        assertFalse(crossesZero(50, 60))
        assertFalse(crossesZero(0, 0))
    }

    @Test
    fun geometryPutsBandsOnALogAxis() {
        val geo = EqGeometry(listOf(60, 230, 910, 3_600, 14_000), -1500, 1500, left = 0f, top = 0f, right = 1000f, bottom = 300f)
        val xs = (0 until 5).map { geo.bandX(it) }
        // The stock bands are ~2 octaves apart: almost evenly spaced in log scale.
        val gaps = xs.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { abs(it - gaps.average()) < 12 })
        // Half a step of air at each side.
        assertEquals(1000f - xs.last(), xs.first(), 1f)
        assertEquals(geo.top, geo.levelY(1500f), 0.01f)
        assertEquals(geo.bottom, geo.levelY(-1500f), 0.01f)
        assertEquals(150f, geo.levelY(0f), 0.01f)
        assertEquals(10f, geo.mbPerPx(), 0.001f)
        assertEquals(0, geo.nearestBand(-50f))
        assertEquals(2, geo.nearestBand(500f))
        assertEquals(4, geo.nearestBand(2_000f))
    }

    @Test
    fun aSingleBandStillHasAPlace() {
        val geo = EqGeometry(listOf(1_000), -1500, 1500, left = 0f, top = 0f, right = 100f, bottom = 100f)
        assertEquals(50f, geo.bandX(0), 0.01f)
    }

    @Test
    fun theCurveNeverOvershootsItsPoints() {
        val xs = floatArrayOf(0f, 40f, 100f, 180f, 260f, 340f, 400f)
        val ys = floatArrayOf(150f, 150f, 30f, 200f, 210f, 90f, 90f)
        val m = monotoneTangents(xs, ys)
        for (i in 0 until xs.size - 1) {
            val lo = minOf(ys[i], ys[i + 1])
            val hi = maxOf(ys[i], ys[i + 1])
            for (step in 0..20) {
                val y = hermite(xs, ys, m, i, step / 20f)
                assertTrue("segment $i at $step: $y outside $lo..$hi", y >= lo - 0.01f && y <= hi + 0.01f)
            }
        }
        // Flat ends stay flat, peaks are flat on top.
        assertEquals(0f, m[0], 0f)
        assertEquals(0f, m[2], 0f)
        assertEquals(0f, m[xs.size - 1], 0f)
    }
}
