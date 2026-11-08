package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ChordReadingTest {
    /** Frames that each say one class with probability [p] (the rest shared). */
    private fun frames(vararg classes: Int, p: Double = 0.8) = Array(classes.size) { t ->
        FloatArray(Btc.CLASSES) { c -> ln(if (c == classes[t]) p else (1 - p) / (Btc.CLASSES - 1)).toFloat() }
    }

    private val c = 0 * 14 + 1 // C
    private val am = 9 * 14 + 0 // Am
    private val g7 = 7 * 14 + 9 // G7

    @Test fun aOneFrameDoubtDoesNotFlicker() {
        // C for a second, one frame of Am, C again: still C.
        val path = ChordReading.viterbi(frames(*IntArray(11) { c } + am + IntArray(11) { c }), ChordReading.CHANGE_PRICE)
        assertEquals(setOf(c), path.toSet())
    }

    @Test fun aRealChangeIsKeptWithItsTimes() {
        val chords = ChordReading.chords(frames(*IntArray(30) { c } + IntArray(30) { am } + IntArray(30) { g7 }))
        assertEquals(listOf(Chord(0, ChordQuality.MAJOR), Chord(9, ChordQuality.MINOR), Chord(7, ChordQuality.DOM7)), chords.map { it.chord })
        // 30 frames of 10/108 s.
        assertEquals(2_778L, chords[1].startMs)
        assertEquals(5_556L, chords[1].endMs)
    }

    @Test fun noChordIsLeftEmpty() {
        val chords = ChordReading.chords(frames(*IntArray(20) { Btc.NO_CHORD } + IntArray(30) { c } + IntArray(20) { Btc.NO_CHORD }))
        assertEquals(1, chords.size)
        assertEquals(1_852L, chords[0].startMs)
    }

    @Test fun theKeyIsWhatRingsLongest() {
        val chart = listOf(TimedChord(0, 8_000, 9, ChordQuality.MINOR), TimedChord(8_000, 10_000, 4, ChordQuality.DOM7), TimedChord(10_000, 18_000, 9, ChordQuality.MINOR))
        assertEquals(MusicKey(9, true), ChordReading.key(chart))
    }
}
