package com.villamediana.sound.playback.effects

import com.villamediana.sound.playback.EqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class EqCurvesTest {

    /** Stock Android equalizer (most phones). */
    private val five = listOf(60, 230, 910, 3_600, 14_000)
    /** Octave equalizer (some Samsung / Xiaomi / Pixel builds). */
    private val ten = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    @Test
    fun everyPresetFitsBothDevices() {
        for (preset in EqPreset.entries) {
            for (bands in listOf(five, ten)) {
                val levels = EqCurves.presetLevels(preset, bands, -1500, 1500)
                assertEquals("$preset on ${bands.size} bands", bands.size, levels.size)
                assertTrue("$preset stays gentle", levels.all { it in -600..600 })
            }
        }
        assertEquals(List(5) { 0 }, EqCurves.presetLevels(EqPreset.FLAT, five, -1500, 1500))
        assertEquals(List(10) { 0 }, EqCurves.presetLevels(EqPreset.FLAT, ten, -1500, 1500))
    }

    @Test
    fun bassBoostsOnlyBelow250Hz() {
        val l5 = EqCurves.presetLevels(EqPreset.BASS, five, -1500, 1500)
        assertTrue(l5[0] > 0 && l5[1] > 0)
        assertEquals(listOf(0, 0, 0), l5.subList(2, 5))
        assertTrue("60 Hz gets more than 230 Hz", l5[0] > l5[1])

        val l10 = EqCurves.presetLevels(EqPreset.BASS, ten, -1500, 1500)
        ten.zip(l10).forEach { (hz, mb) ->
            if (hz < 250) assertTrue("$hz Hz boosted", mb > 0)
            if (hz >= 500) assertEquals("$hz Hz untouched", 0, mb)
        }
    }

    @Test
    fun fiveBandValuesAreInterpolatedInLogFrequency() {
        // 230 Hz sits between 125 Hz (+4.5 dB) and 250 Hz (+2 dB) of the BASS curve.
        val t = ln(230.0 / 125.0) / ln(250.0 / 125.0)
        val expected = (4.5 + (2.0 - 4.5) * t) * 100
        val actual = EqCurves.presetLevels(EqPreset.BASS, five, -1500, 1500)[1]
        assertEquals(expected, actual.toDouble(), 1.0)
        // 60 Hz between 31.5 (+6) and 63 (+6) → exactly +6 dB.
        assertEquals(600, EqCurves.presetLevels(EqPreset.BASS, five, -1500, 1500)[0])
    }

    @Test
    fun tenBandDeviceGetsTheGridValues() {
        val rock = EqCurves.presetLevels(EqPreset.ROCK, ten, -1500, 1500)
        // 62 Hz and 31 Hz are a hair off the grid; the rest are exact.
        assertEquals(listOf(300, 100, -100, -150, 50, 250, 400, 450), rock.subList(2, 10))
        assertEquals(500, rock[0])
    }

    @Test
    fun presetsHaveTheirCharacter() {
        val vocal10 = EqCurves.presetLevels(EqPreset.VOCAL, ten, -1500, 1500)
        val peak = vocal10.indices.maxBy { vocal10[it] }
        assertTrue("vocal peaks in 1–4 kHz", ten[peak] in 1_000..4_000)
        val vocal5 = EqCurves.presetLevels(EqPreset.VOCAL, five, -1500, 1500)
        assertTrue(vocal5[2] > 0 && vocal5[3] > 0 && vocal5[0] < 0)

        val treble = EqCurves.presetLevels(EqPreset.TREBLE, ten, -1500, 1500)
        assertEquals(treble.sorted(), treble)

        val latin = EqCurves.presetLevels(EqPreset.LATIN, five, -1500, 1500)
        assertTrue("punchy lows", latin[0] > latin[2])
        assertTrue("crisp highs", latin[3] > latin[2] && latin[4] > latin[2])

        val night = EqCurves.presetLevels(EqPreset.LATE_NIGHT, five, -1500, 1500)
        assertTrue("softer lows", night[0] < 0)
        assertTrue("softer highs", night[4] < 0)
        assertTrue("mids kept", night[2] > 0)
    }

    @Test
    fun levelsAreClampedToTheDeviceRange() {
        val narrow = EqCurves.presetLevels(EqPreset.BASS, five, -300, 300)
        assertEquals(300, narrow[0])
        assertTrue(narrow.all { it in -300..300 })
        val night = EqCurves.presetLevels(EqPreset.LATE_NIGHT, five, -200, 200)
        assertEquals(-200, night[0])

        assertEquals(1500, EqCurves.clampLevel(4000, -1500, 1500))
        assertEquals(-1200, EqCurves.clampLevel(-4000, -1200, 1200))
        assertEquals(35, EqCurves.clampLevel(35, -1500, 1500))
    }

    @Test
    fun customLevelsRoundTripOnTheSameDevice() {
        val levels = listOf(300, -200, 0, 450, -1500)
        val saved = EqCurves.levelsByFrequency(five, levels)
        assertEquals(mapOf(60 to 300, 230 to -200, 910 to 0, 3_600 to 450, 14_000 to -1500), saved)
        assertEquals(levels, EqCurves.levelsFromFrequencies(saved, five, -1500, 1500))

        val tenLevels = listOf(100, 200, 300, 400, 500, -100, -200, -300, -400, -500)
        assertEquals(tenLevels, EqCurves.levelsFromFrequencies(EqCurves.levelsByFrequency(ten, tenLevels), ten, -1500, 1500))
    }

    @Test
    fun customLevelsCrossToADeviceWithOtherBands() {
        val saved = EqCurves.levelsByFrequency(five, listOf(600, 0, -300, 300, 900))
        val on10 = EqCurves.levelsFromFrequencies(saved, ten, -1500, 1500)
        assertEquals(10, on10.size)
        // Below the lowest saved band: flat at its value.
        assertEquals(600, on10[0])
        // 125 Hz is between 60 (+6) and 230 (0) → somewhere in between, closer to 230 in log scale.
        assertTrue(on10[2] in 1..599)
        val t = ln(125.0 / 60.0) / ln(230.0 / 60.0)
        assertEquals(600 * (1 - t), on10[2].toDouble(), 1.0)
        // 16 kHz is above the highest saved band (14 kHz): flat.
        assertEquals(900, on10[9])

        // And back: the 5-band curve survives the trip through a 10-band phone within 1 dB.
        val original = listOf(600, 0, -300, 300, 900)
        val back = EqCurves.levelsFromFrequencies(EqCurves.levelsByFrequency(ten, on10), five, -1500, 1500)
        original.zip(back).forEach { (a, b) -> assertTrue("$a → $b", kotlin.math.abs(a - b) <= 100) }
    }

    @Test
    fun customLevelsAreClampedAndEmptyMeansFlat() {
        val saved = mapOf(60 to 1500, 14_000 to -1500)
        assertEquals(listOf(900, -900), EqCurves.levelsFromFrequencies(saved, listOf(60, 14_000), -900, 900))
        assertEquals(List(5) { 0 }, EqCurves.levelsFromFrequencies(emptyMap(), five, -1500, 1500))
    }

    @Test
    fun interpolationIsFlatOutsideAndExactOnPoints() {
        val xs = doubleArrayOf(100.0, 1000.0)
        val ys = doubleArrayOf(-3.0, 3.0)
        assertEquals(-3.0, EqCurves.interpolateLog(xs, ys, 20.0), 1e-9)
        assertEquals(3.0, EqCurves.interpolateLog(xs, ys, 20_000.0), 1e-9)
        assertEquals(0.0, EqCurves.interpolateLog(xs, ys, Math.sqrt(100.0 * 1000.0)), 1e-9)
        assertEquals(-3.0, EqCurves.interpolateLog(xs, ys, 100.0), 1e-9)
    }
}
