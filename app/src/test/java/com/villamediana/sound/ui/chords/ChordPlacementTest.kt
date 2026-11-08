package com.villamediana.sound.ui.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class ChordPlacementTest {
    private val pad = 15
    private val gap = 10

    /** One row, left to right: each chord after the one before, none touching, none past the edges. */
    private fun assertOneRowInOrder(p: ChordPlacement.Line, widths: IntArray, width: Int) {
        val w = IntArray(widths.size) { ceil(widths[it] * p.scale).toInt() }
        for (i in widths.indices) {
            assertTrue("chord $i past the edge: ${p.x[i]}+${w[i]} > $width", p.x[i] + w[i] <= width)
            assertTrue("chord $i past the left: ${p.x[i]}", p.x[i] >= -pad)
            if (i > 0) assertTrue("chord $i not after ${i - 1}: ${p.x.toList()}", p.x[i - 1] + w[i - 1] <= p.x[i])
        }
    }

    @Test fun chordsThatFitStayOverTheirWordsAtFullSize() {
        val p = ChordPlacement.place(intArrayOf(-15, 400), intArrayOf(120, 100), width = 800, pad = pad, gap = gap)
        assertEquals(listOf(-15, 400), p.x.toList())
        assertEquals(1f, p.scale)
    }

    @Test fun theLastOnesComeBackFromTheEdgeWhenTheyFit() {
        val widths = intArrayOf(120, 100, 100)
        val p = ChordPlacement.place(intArrayOf(-15, 600, 720), widths, width = 800, pad = pad, gap = gap)
        assertEquals(1f, p.scale)
        assertEquals(800, p.x[2] + widths[2])
        assertOneRowInOrder(p, widths, 800)
    }

    /** Sparks: "I say, oh" under E7M(6/9) Em Em7 F6(9/11+) C#m7(5-/9) C7M(9) — they went one over another. */
    @Test fun moreChordsThanTheLineIsWideAreSetSmallerInOneRow() {
        val widths = intArrayOf(210, 95, 105, 160, 210, 140)
        val p = ChordPlacement.place(IntArray(widths.size) { 300 }, widths, width = 790, pad = pad, gap = gap)
        assertTrue(p.scale < 1f)
        // Only as small as needed.
        assertTrue(p.scale > 0.8f)
        assertOneRowInOrder(p, widths, 790)
    }

    @Test fun evenAFarLongerRunStaysInOrder() {
        val widths = IntArray(14) { 150 }
        val p = ChordPlacement.place(IntArray(14) { it * 40 }, widths, width = 700, pad = pad, gap = gap)
        assertOneRowInOrder(p, widths, 700)
    }
}
