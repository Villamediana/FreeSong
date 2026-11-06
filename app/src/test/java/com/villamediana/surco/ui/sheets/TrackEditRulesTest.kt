package com.villamediana.surco.ui.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEditRulesTest {
    @Test fun digitsKeepsOnlyNumbersUpToMax() {
        assertEquals("199", TrackEditRules.digits("19a9", 4))
        assertEquals("1990", TrackEditRules.digits("1990s!", 4))
        assertEquals("12", TrackEditRules.digits("12345", 2))
        assertEquals("", TrackEditRules.digits("abc", 3))
    }

    @Test fun yearMustLookLikeAYear() {
        assertEquals(1990, TrackEditRules.parseYear("1990"))
        assertEquals(2026, TrackEditRules.parseYear(" 2026 "))
        assertNull(TrackEditRules.parseYear("199"))
        assertNull(TrackEditRules.parseYear(""))
        assertNull(TrackEditRules.parseYear("3001"))
    }

    @Test fun trackNumberIsPositive() {
        assertEquals(7, TrackEditRules.parseTrackNumber("7"))
        assertEquals(12, TrackEditRules.parseTrackNumber("012"))
        assertNull(TrackEditRules.parseTrackNumber("0"))
        assertNull(TrackEditRules.parseTrackNumber(""))
    }

    @Test fun validity() {
        assertTrue(TrackEditRules.isValid("De música ligera", "Soda Stereo", "1990"))
        assertTrue(TrackEditRules.isValid("De música ligera", "Soda Stereo", ""))
        assertFalse(TrackEditRules.isValid(" ", "Soda Stereo", ""))
        assertFalse(TrackEditRules.isValid("Título", "", ""))
        assertFalse(TrackEditRules.isValid("Título", "Artista", "19"))
    }

    @Test fun buildTrimsAndTurnsBlanksIntoNull() {
        val e = TrackEditRules.build(" Persiana americana ", "Soda Stereo ", " Signos", "", "  ", "1986", "")
        assertEquals("Persiana americana", e.title)
        assertEquals("Soda Stereo", e.artist)
        assertEquals("Signos", e.album)
        assertNull(e.albumArtist)
        assertNull(e.genre)
        assertEquals(1986, e.year)
        assertNull(e.trackNumber)
    }
}
