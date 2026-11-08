package com.villamediana.sound.chords

import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A written sheet over the app's lyrics (a made-up song). */
class SheetLayoutTest {
    /**
     * As a sheet writes it: the chorus on one line and only once, the second verse broken in two
     * lines, a solo and an ending of chords alone.
     */
    private val sheet = WrittenSheet(
        lines = listOf(
            SheetLine("", listOf(SheetChord(0, "G"), SheetChord(3, "D"), SheetChord(6, "Em"), SheetChord(10, "C"))), // 0–3
            SheetLine("Acordei com o sol na janela", listOf(SheetChord(0, "G"), SheetChord(14, "D"))), // 4, 5
            SheetLine("E o café esfriando na mesa", listOf(SheetChord(0, "Em"), SheetChord(9, "C"))), // 6, 7
            SheetLine(""),
            SheetLine("Vem dançar comigo, até a noite acabar", listOf(SheetChord(0, "G"), SheetChord(4, "D"), SheetChord(19, "Em"), SheetChord(25, "C"))), // 8–11
            SheetLine(""),
            SheetLine("", listOf(SheetChord(0, "Am"), SheetChord(4, "C"), SheetChord(7, "D"))), // 12–14
            SheetLine("Acordei de novo", listOf(SheetChord(0, "G"))), // 15
            SheetLine("sem pressa", listOf(SheetChord(4, "D"))), // 16
            SheetLine("", listOf(SheetChord(0, "G"))), // 17
        ),
        source = "Cifra Club",
        url = "https://example.com/cifra",
    )

    /** As the lyrics site has it: lines broken otherwise, the chorus twice, a word spelled differently. */
    private val lyrics = ParsedLyrics(
        synced = true,
        lines = listOf(
            LyricLine(10_000, "Acordei com o sol na janela"),
            LyricLine(14_000, "E o cafe esfriano na mesa"),
            LyricLine(18_000, "Vem dançar comigo"),
            LyricLine(21_000, "Até a noite acabar"),
            LyricLine(26_000, ""),
            LyricLine(40_000, "Acordei de novo sem pressa"),
            LyricLine(44_000, "Vem dançar comigo"),
            LyricLine(47_000, "Até a noite acabar"),
        ),
    )

    @Test fun theChordsGoOverTheWordsTheyWereWrittenOver() {
        val view = ChordLayout.view(lyrics, chart(), 60_000)
        assertTrue(view.follows)
        assertEquals(
            listOf(
                ChordRow.Instrumental(0, listOf(0, 1, 2, 3)), // the intro, from the start
                ChordRow.Lyric(0, "Acordei com o sol na janela", 10_000, listOf(PlacedChord(0, 4), PlacedChord(14, 5))),
                ChordRow.Lyric(1, "E o cafe esfriano na mesa", 14_000, listOf(PlacedChord(0, 6), PlacedChord(9, 7))),
                // One line of the sheet, two of the lyrics: each half takes its chords.
                ChordRow.Lyric(2, "Vem dançar comigo", 18_000, listOf(PlacedChord(0, 8), PlacedChord(4, 9))),
                ChordRow.Lyric(3, "Até a noite acabar", 21_000, listOf(PlacedChord(0, 10), PlacedChord(6, 11))),
                // The solo on the lyrics' blank line, at its time.
                ChordRow.Instrumental(26_000, listOf(12, 13, 14)),
                // Two lines of the sheet, one of the lyrics.
                ChordRow.Lyric(5, "Acordei de novo sem pressa", 40_000, listOf(PlacedChord(0, 15), PlacedChord(20, 16))),
                // The chorus again, though the sheet writes it once.
                ChordRow.Lyric(6, "Vem dançar comigo", 44_000, listOf(PlacedChord(0, 8), PlacedChord(4, 9))),
                ChordRow.Lyric(7, "Até a noite acabar", 47_000, listOf(PlacedChord(0, 10), PlacedChord(6, 11))),
                // The ending after the last line has been sung.
                ChordRow.Instrumental(47_000 + ChordLayout.sungMs("Até a noite acabar"), listOf(17)),
            ),
            view.rows,
        )
    }

    @Test fun lyricsWithoutTimesGetTheSameChordsAndStayStill() {
        val plain = ParsedLyrics(false, lyrics.lines.map { LyricLine(0, it.text) })
        val view = ChordLayout.view(plain, chart(), 60_000)
        assertFalse(view.follows)
        val lines = view.rows.filterIsInstance<ChordRow.Lyric>().associate { it.line to it.chords }
        assertEquals(listOf(PlacedChord(0, 15), PlacedChord(20, 16)), lines[5])
        assertEquals(listOf(PlacedChord(0, 10), PlacedChord(6, 11)), lines[7])
    }

    @Test fun lyricsOfAnotherSongShowTheSheetAsWritten() {
        val other = ParsedLyrics(true, listOf(LyricLine(1_000, "Nada a ver com isso aqui"), LyricLine(5_000, "Outra canção qualquer")))
        assertFalse(SheetLayout.fits(other, sheet))
        assertTrue(SheetLayout.fits(lyrics, sheet))
        for (l in listOf(other, ParsedLyrics(false, emptyList()))) {
            val view = ChordLayout.view(l, chart(), 60_000)
            assertFalse(view.follows)
            val rows = view.rows
            assertEquals(ChordRow.Instrumental(0, listOf(0, 1, 2, 3)), rows.first())
            assertEquals(listOf(PlacedChord(0, 4), PlacedChord(14, 5)), (rows[1] as ChordRow.Lyric).chords)
            assertEquals("Acordei com o sol na janela", (rows[1] as ChordRow.Lyric).text)
            assertEquals(ChordRow.Instrumental(0, listOf(17)), rows.last())
        }
    }

    @Test fun theChartNamesItsChordsAsWrittenAndPlaysTheClosestShape() {
        val chart = ChordChart(emptyList(), MusicKey(7, false), sheet = WrittenSheet(listOf(SheetLine("la", listOf(SheetChord(0, "F7M(2/4+)"), SheetChord(1, "Bb5-")))), "x", "u"))
        assertEquals(listOf("F7M(2/4+)", "Bb5-"), chart.labels())
        assertEquals(Chord.parse("F7M"), chart.chordAt(0))
        assertEquals(Chord.parse("Bb5"), chart.chordAt(1))
        assertEquals(listOf(Chord.parse("F7M"), Chord.parse("Bb5")), chart.distinct)
        assertFalse(chart.empty)
    }

    private fun chart() = ChordChart(emptyList(), MusicKey(7, false), sheet = sheet, looked = true)
}
