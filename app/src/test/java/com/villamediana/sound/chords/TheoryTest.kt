package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TheoryTest {
    private fun p(s: String) = Chord.parse(s)

    @Test fun readsTheWaysChordsAreWritten() {
        assertEquals(Chord(9, ChordQuality.MINOR), p("Am"))
        assertEquals(Chord(9, ChordQuality.MINOR), p("A-"))
        assertEquals(Chord(7, ChordQuality.DOM7), p("G7"))
        assertEquals(Chord(2, ChordQuality.MIN7), p("Dm7"))
        assertEquals(Chord(0, ChordQuality.MAJ7), p("C7M"))
        assertEquals(Chord(0, ChordQuality.MAJ7), p("Cmaj7"))
        assertEquals(Chord(11, ChordQuality.HALF_DIM), p("Bm7(b5)"))
        assertEquals(Chord(11, ChordQuality.HALF_DIM), p("Bø"))
        assertEquals(Chord(1, ChordQuality.DIM), p("C#°"))
        assertEquals(Chord(10, ChordQuality.MAJOR), p("Bb"))
        assertEquals(Chord(10, ChordQuality.MAJOR), p("A#"))
        assertEquals(Chord(2, ChordQuality.SUS4), p("D4"))
        assertEquals(Chord(9, ChordQuality.DOM9), p("A7(9)"))
        // Brazilian sheets: C9 is C with an added ninth.
        assertEquals(Chord(0, ChordQuality.ADD9), p("C9"))
        assertEquals(Chord(0, ChordQuality.MAJOR, bass = 4), p("C/E"))
        assertEquals(Chord(9, ChordQuality.MINOR, bass = 7), p("Am/G"))
        assertEquals(Chord(4, ChordQuality.AUG), p("E+"))
    }

    @Test fun refusesWhatIsNotAChord() {
        assertNull(p("Ver"))
        assertNull(p("Autor:"))
        assertNull(p("H7"))
        assertNull(p("Cxyz"))
        assertNull(p(""))
    }

    @Test fun namesFollowTheKey() {
        val gSharp = Chord(8, ChordQuality.MINOR)
        // In E major it is G#m; in a flat key, Abm.
        assertEquals("G#m", gSharp.name(Spelling.forKey(MusicKey(4, false))))
        assertEquals("Abm", gSharp.name(Spelling.forKey(MusicKey(3, false))))
        // C / Am: the usual mix, Bb and F#.
        assertEquals("Bb", Chord(10, ChordQuality.MAJOR).name(Spelling.forKey(MusicKey(0, false))))
        assertEquals("F#m", Chord(6, ChordQuality.MINOR).name(Spelling.forKey(MusicKey(9, true))))
        assertEquals("Dm", Chord(2, ChordQuality.MINOR).name(Spelling.forKey(MusicKey(2, true))))
        assertEquals("Bb7M", Chord(10, ChordQuality.MAJ7).name(Spelling.FLATS))
        assertEquals("C/E", Chord(0, ChordQuality.MAJOR, 4).name())
    }

    @Test fun saysTheChordInPortuguese() {
        assertEquals("Lá menor com sétima", ChordSpeech.say(Chord(9, ChordQuality.MIN7), Spelling.NEUTRAL))
        assertEquals("Sol com sétima", ChordSpeech.say(Chord(7, ChordQuality.DOM7), Spelling.NEUTRAL))
        assertEquals("Si bemol maior", ChordSpeech.say(Chord(10, ChordQuality.MAJOR), Spelling.FLATS))
        assertEquals("Fá sustenido menor", ChordSpeech.say(Chord(6, ChordQuality.MINOR), Spelling.SHARPS))
        assertEquals("Dó maior com baixo em Mi", ChordSpeech.say(Chord(0, ChordQuality.MAJOR, 4), Spelling.NEUTRAL))
        assertEquals("La menor con séptima", ChordSpeech.say(Chord(9, ChordQuality.MIN7), Spelling.NEUTRAL, "es"))
        assertEquals("Si bemol mayor", ChordSpeech.say(Chord(10, ChordQuality.MAJOR), Spelling.FLATS, "es"))
    }

    @Test fun tonesAndTransposition() {
        assertEquals(listOf(9, 0, 4, 7), Chord(9, ChordQuality.MIN7).tones)
        assertEquals(listOf(4, 0, 7), Chord(0, ChordQuality.MAJOR, 4).tones)
        assertEquals(Chord(11, ChordQuality.MINOR), Chord(9, ChordQuality.MINOR).transpose(2))
        assertEquals(Chord(10, ChordQuality.DOM7, 2), Chord(0, ChordQuality.DOM7, 4).transpose(-2))
    }

    @Test fun keyOfAProgression() {
        val pop = listOf("C", "G", "Am", "F").map { p(it)!! }
        assertEquals(MusicKey(0, false), Keys.ofChords(pop))
        val minor = listOf("Am", "Dm", "E7", "Am").map { p(it)!! }
        assertEquals(MusicKey(9, true), Keys.ofChords(minor))
    }

    @Test fun readsWhatWrittenSheetsUse() {
        assertEquals(Chord(9, ChordQuality.MIN9), p("Am7(9)"))
        assertEquals(Chord(5, ChordQuality.MAJ9), p("F7M(9)"))
        assertEquals(Chord(7, ChordQuality.DOM13), p("G7(13)"))
        assertEquals(Chord(2, ChordQuality.SIX9), p("D6(9)"))
        assertEquals(Chord(4, ChordQuality.MIN11), p("Em7(11)"))
        assertEquals(Chord(11, ChordQuality.DOM7_FLAT9), p("B7(b9)"))
        assertEquals(Chord(4, ChordQuality.AUG7, bass = 8), p("E7(#5)/G#"))
        assertEquals("F7M(9)", Chord(5, ChordQuality.MAJ9).name())
    }

    @Test fun anyWrittenNamePlaysItsClosestChord() {
        assertEquals(p("F7M"), Chord.closest("F7M(2/4+)"))
        assertEquals(p("Bb5"), Chord.closest("Bb5-"))
        assertEquals(p("E7/G#"), Chord.closest("E7(4/9)/G#"))
        assertEquals(p("C#m7"), Chord.closest("C#m7(9/11)"))
        assertEquals(p("A"), Chord.closest("Aadd11"))
        assertEquals(p("Am"), Chord.closest("Am"))
        assertEquals(null, Chord.closest("Refrão"))
    }

    @Test fun writtenNamesMoveWithTheirExtrasKept() {
        assertEquals("D#m7(9)/A#", Chord.transposeName("C#m7(9)/G#", 2, Spelling.SHARPS))
        assertEquals("Ebm7(9)/Bb", Chord.transposeName("C#m7(9)/G#", 2, Spelling.FLATS))
        assertEquals("G7M(2/4+)", Chord.transposeName("F7M(2/4+)", 2, Spelling.NEUTRAL))
        assertEquals("A", Chord.transposeName("Bb", -1, Spelling.NEUTRAL))
        assertEquals("Refrão", Chord.transposeName("Refrão", 3, Spelling.NEUTRAL))
    }
}

