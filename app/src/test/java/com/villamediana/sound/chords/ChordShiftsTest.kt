package com.villamediana.sound.chords

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChordShiftsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun eachSongKeepsItsKey() = runTest {
        val file = tmp.root.resolve("shifts.json")
        val shifts = ChordShifts(file)
        assertEquals(0, shifts.of("a").first())
        shifts.set("a", -3)
        shifts.set("b", 2)
        assertEquals(-3, shifts.of("a").first())
        // Another session reads them back.
        val again = ChordShifts(file)
        assertEquals(-3, again.current("a"))
        assertEquals(2, again.current("b"))
        // Back to its own key: not kept; never past ±11.
        again.set("b", 0)
        again.set("a", 40)
        assertFalse(file.readText().contains("\"b\""))
        assertEquals(11, ChordShifts(file).current("a"))
    }
}
