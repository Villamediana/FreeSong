package com.villamediana.sound.ui.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChordNameTest {
    private fun parts(name: String) = ChordName.split(name)!!.let { Triple(it.head, it.extension, it.bass) }

    @Test fun theChordAtFullSizeWhatColorsItRaised() {
        assertEquals(Triple("A", "7M", ""), parts("A7M"))
        assertEquals(Triple("Bm", "7(9)", ""), parts("Bm7(9)"))
        assertEquals(Triple("A#", "7(b5)", ""), parts("A#7(b5)"))
        assertEquals(Triple("Bb", "", ""), parts("Bb"))
        assertEquals(Triple("Ebm", "", ""), parts("Ebm"))
        assertEquals(Triple("G", "º", ""), parts("Gº"))
        assertEquals(Triple("C", "sus4", ""), parts("Csus4"))
    }

    @Test fun majIsAnExtensionNotMinor() {
        assertEquals(Triple("C", "maj7", ""), parts("Cmaj7"))
        assertEquals(Triple("Cm", "maj7", ""), parts("Cmmaj7"))
    }

    @Test fun aBassAfterTheSlashButNotSevenSlashNine() {
        assertEquals(Triple("D", "", "/F#"), parts("D/F#"))
        assertEquals(Triple("E", "7", "/G#"), parts("E7/G#"))
        assertEquals(Triple("A", "7/9", ""), parts("A7/9"))
    }

    @Test fun notAChordStaysAsItIs() {
        assertNull(ChordName.split("N.C."))
        assertNull(ChordName.split(""))
        assertEquals("N.C.", ChordName.typeset("N.C.").text)
        // The whole name is kept: screen readers and taps see "Bm7(9)".
        assertEquals("Bm7(9)", ChordName.typeset("Bm7(9)").text)
        assertEquals(1, ChordName.typeset("Bm7(9)").spanStyles.size)
        assertEquals(0, ChordName.typeset("D/F#").spanStyles.size)
    }
}
