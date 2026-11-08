package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Shapes from chords-db (the real assets) and the ones worked out here, checked note by note. */
class FingeringsTest {
    private val fingerings = Fingerings { name -> File("src/main/assets/$name").takeIf { it.isFile }?.readText() }

    private fun first(name: String, instrument: Instrument) = fingerings.of(Chord.parse(name)!!, instrument).first().frets

    private fun shape(s: String) = s.map { if (it == 'x') -1 else it.digitToInt() }

    private val heardQualities = listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOM7, ChordQuality.MIN7)

    @Test fun violaoOpensWithTheShapesEveryoneLearns() {
        assertEquals(shape("x32010"), first("C", Instrument.VIOLAO))
        assertEquals(shape("320003"), first("G", Instrument.VIOLAO))
        assertEquals(shape("xx0232"), first("D", Instrument.VIOLAO))
        assertEquals(shape("x02210"), first("Am", Instrument.VIOLAO))
        assertEquals(shape("022000"), first("Em", Instrument.VIOLAO))
        assertEquals(shape("133211"), first("F", Instrument.VIOLAO))
        assertEquals(shape("x24432"), first("Bm", Instrument.VIOLAO))
    }

    @Test fun ukuleleToo() {
        assertEquals(shape("0003"), first("C", Instrument.UKULELE))
        assertEquals(shape("0232"), first("G", Instrument.UKULELE))
        assertEquals(shape("2000"), first("Am", Instrument.UKULELE))
        assertEquals(shape("2010"), first("F", Instrument.UKULELE))
    }

    @Test fun cavaquinhoShapesAreWorkedOutRight() {
        // D G B D: the shapes a cavaquinho player knows.
        assertEquals(shape("2012"), first("C", Instrument.CAVAQUINHO))
        assertEquals(shape("0000"), first("G", Instrument.CAVAQUINHO))
        assertEquals(shape("2212"), first("Am", Instrument.CAVAQUINHO))
        assertEquals(shape("3213"), first("F", Instrument.CAVAQUINHO))
        assertEquals(shape("2002"), first("Em", Instrument.CAVAQUINHO))
        assertTrue(first("D", Instrument.CAVAQUINHO) in listOf(shape("0234"), shape("4234")))
    }

    @Test fun everyShapeShownPlaysItsChord() {
        for (instrument in listOf(Instrument.VIOLAO, Instrument.UKULELE, Instrument.CAVAQUINHO)) {
            for (root in 0 until 12) for (q in ChordQuality.entries) {
                val chord = Chord(root, q)
                val shapes = fingerings.of(chord, instrument)
                if (q in heardQualities) assertTrue("$instrument has no $chord", shapes.isNotEmpty())
                for (f in shapes) {
                    assertTrue("$instrument $chord ${f.frets} doesn't play it", FretMath.plays(chord, f, instrument, strict = false))
                    assertTrue("$instrument $chord ${f.frets} needs ${f.fingers}", f.fingers.filter { it > 0 }.distinct().size <= 4)
                    assertEquals(f.frets.size, instrument.strings.size)
                }
            }
        }
    }

    @Test fun eachChordHasSeveralPlaces() {
        for (name in listOf("C", "Am", "G7", "Dm7", "F#m", "Bb")) {
            for (instrument in listOf(Instrument.VIOLAO, Instrument.UKULELE, Instrument.CAVAQUINHO)) {
                val shapes = fingerings.of(Chord.parse(name)!!, instrument)
                assertTrue("$instrument $name: ${shapes.size}", shapes.size >= 3)
                assertEquals(shapes.size, shapes.map { it.frets }.distinct().size)
            }
        }
    }

    @Test fun fingersAndBarres() {
        val (fingers, barres) = FretMath.fingers(shape("133211"))!!
        assertEquals(listOf(1, 3, 4, 2, 1, 1), fingers)
        assertEquals(listOf(Barre(1, 0, 5)), barres)
        assertEquals(listOf(0, 3, 2, 0, 1, 0), FretMath.fingers(shape("x32010"))!!.first)
        // Five notes and no barre possible: no hand plays it.
        assertEquals(null, FretMath.fingers(listOf(1, 2, 3, 4, 5, 6)))
    }

    @Test fun diagramWindow() {
        val open = Fingering(shape("x32010"), listOf(0, 3, 2, 0, 1, 0))
        assertEquals(1, open.firstFret)
        assertEquals(4, open.rows)
        val high = Fingering(listOf(-1, 5, 7, 7, 6, 5), listOf(0, 1, 3, 4, 2, 1))
        assertEquals(5, high.firstFret)
        assertEquals(4, high.rows)
    }

    @Test fun pianoVoicings() {
        val c = PianoVoicing.voicings(Chord(0, ChordQuality.MAJOR))
        assertEquals(listOf(60, 64, 67), c[0])
        assertEquals(listOf(64, 67, 72), c[1])
        assertEquals(listOf(67, 72, 76), c[2])
        assertEquals(listOf(69, 72, 76, 79), PianoVoicing.voicings(Chord(9, ChordQuality.MIN7)).first())
        assertEquals(4, PianoVoicing.voicings(Chord(9, ChordQuality.MIN7)).size)
        // A slash chord adds its bass below.
        assertEquals(listOf(52, 60, 64, 67), PianoVoicing.voicings(Chord(0, ChordQuality.MAJOR, 4)).first())
    }
}
