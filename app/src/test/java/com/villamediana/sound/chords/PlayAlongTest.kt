package com.villamediana.sound.chords

import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayAlongTest {
    private fun heard(vararg chords: Pair<Long, String>, end: Long): ChordChart = ChordChart(
        chords.mapIndexed { i, (start, name) ->
            val c = Chord.parse(name)!!
            TimedChord(start, chords.getOrNull(i + 1)?.first ?: end, c.root, c.quality, c.bass)
        },
        key = MusicKey(7, false),
    )

    private fun lyrics(vararg lines: Pair<Long, String>) = ParsedLyrics(true, lines.map { LyricLine(it.first, it.second) })

    private fun sheet(vararg lines: SheetLine) = ChordChart(
        emptyList(),
        MusicKey(7, false),
        sheet = WrittenSheet(lines.toList(), source = "test", url = "https://example.com"),
        looked = true,
    )

    @Test fun heardChordsAreTheLaneAsTheyAre() {
        val chart = heard(0L to "G", 4_000L to "D", 8_000L to "Em", end = 12_000)
        val l = lyrics(4_000L to "Quando a gente ama")
        val lane = PlayAlong.lane(ChordLayout.view(l, chart, 12_000), chart, 12_000)!!
        assertEquals(listOf(0L, 4_000L, 8_000L), lane.chords.map { it.startMs })
        assertEquals(listOf(4_000L, 8_000L, 12_000L), lane.chords.map { it.endMs })
        assertEquals(-1, lane.at(-1))
        assertEquals(0, lane.at(0))
        assertEquals(1, lane.at(7_999))
        assertEquals(2, lane.at(60_000))
        assertEquals(1, lane.next(0))
        assertEquals(-1, lane.next(2))
        // Before the first chord, the first one is next.
        assertEquals(0, lane.next(-1))
    }

    @Test fun heardChordsFollowEvenWithoutLyricTimes() {
        // The lines are guessed, the chords aren't: the lane is the song's own.
        val chart = heard(0L to "G", 4_000L to "D", end = 8_000)
        val plain = ParsedLyrics(false, listOf(LyricLine(0, "Quando a gente ama")))
        val lane = PlayAlong.lane(ChordLayout.view(plain, chart, 8_000), chart, 8_000)
        assertNotNull(lane)
        assertEquals(2, lane!!.chords.size)
    }

    @Test fun noChordsNoLane() {
        val chart = ChordChart(emptyList())
        assertNull(PlayAlong.lane(ChordLayout.View(emptyList(), true), chart, 10_000))
    }

    @Test fun aSheetOverSyncedLyricsComesWhenItsWordIsReached() {
        // "Olha que coisa mais linda": G7M on "Olha", A7(13) on "linda" (column 21 of 26).
        val chart = sheet(
            SheetLine("", listOf(SheetChord(0, "G7M"), SheetChord(5, "D7"))),
            SheetLine("Olha que coisa mais linda", listOf(SheetChord(0, "G7M"), SheetChord(21, "A7(13)"))),
            SheetLine("Mais cheia de graça", listOf(SheetChord(0, "Am7"))),
        )
        val l = lyrics(10_000L to "Olha que coisa mais linda", 16_000L to "Mais cheia de graça")
        val view = ChordLayout.view(l, chart, 30_000)
        assertTrue(view.follows)
        val lane = PlayAlong.lane(view, chart, 30_000)!!
        val names = chart.labels()
        assertEquals(listOf("G7M", "D7", "G7M", "A7(13)", "Am7"), lane.chords.map { names[it.index] })
        // The intro shares the time before the first line.
        assertEquals(0L, lane.chords[0].startMs)
        assertEquals(5_000L, lane.chords[1].startMs)
        // The line's first chord with the voice; the second near its word, "linda", but early enough
        // to ring a while before the next line (its word is at 84% of the line, it'd last under a second).
        assertEquals(10_000L, lane.chords[2].startMs)
        assertEquals(16_000L - 1_200L, lane.chords[3].startMs)
        assertEquals(16_000L, lane.chords[4].startMs)
        // The last one holds to the end.
        assertEquals(30_000L, lane.chords.last().endMs)
        // In order, never overlapping.
        lane.chords.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs == b.startMs && a.startMs < b.startMs) }
    }

    @Test fun aSheetWithoutTimesCantBeFollowed() {
        val chart = sheet(SheetLine("Olha que coisa mais linda", listOf(SheetChord(0, "G7M"))))
        val plain = ParsedLyrics(false, listOf(LyricLine(0, "Olha que coisa mais linda")))
        assertNull(PlayAlong.lane(ChordLayout.view(plain, chart, 30_000), chart, 30_000))
    }

    @Test fun theSameChordWrittenAgainIsNoChange() {
        val chart = sheet(
            SheetLine("Olha que coisa", listOf(SheetChord(0, "G"))),
            SheetLine("Mais cheia de graça", listOf(SheetChord(0, "G"), SheetChord(10, "C"))),
        )
        val l = lyrics(1_000L to "Olha que coisa", 5_000L to "Mais cheia de graça")
        val lane = PlayAlong.lane(ChordLayout.view(l, chart, 20_000), chart, 20_000)!!
        assertEquals(3, lane.chords.size)
        assertEquals(lane.run[0], lane.run[1])
        // From the first G, the next change is C, not the G written again.
        assertEquals(2, lane.next(0))
        assertEquals(2, lane.next(1))
        assertEquals(0, lane.runStart(1))
        assertEquals(lane.chords[2].startMs, lane.runEnd(0))
    }

    @Test fun rowsStartingTogetherShareTheirTime() {
        val rows = listOf(
            ChordRow.Instrumental(0, listOf(0, 1)),
            ChordRow.Instrumental(0, listOf(2, 3)),
            ChordRow.Lyric(0, "Olha", 8_000, listOf(PlacedChord(0, 4))),
        )
        val starts = PlayAlong.fromRows(rows, 20_000)
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L), starts.map { it.first })
        assertEquals(listOf(0, 1, 2, 3, 4), starts.map { it.second })
    }

    @Test fun chordsAfterTheWordsShareTheRestOfTheLine() {
        // Maná, "Se pudiera ya borrar  G D G D": D over the words, four quick ones after them. They
        // used to come all at once at the end (D held, then four flashes).
        val row = ChordRow.Lyric(0, "Se pudiera ya borrar", 10_000, listOf(PlacedChord(0, 0)) + (1..4).map { PlacedChord(21, it) })
        val times = PlayAlong.lyricTimes(row, 10_000, 18_000).map { it.first }
        assertEquals(10_000L, times[0])
        // Each of the four gets the same share of what's left after the singing, two beats or so.
        val after = times.drop(1)
        val steps = after.zipWithNext { a, b -> b - a }
        assertTrue(steps.all { it == steps[0] && it >= 800 })
        assertTrue(after.first() - times[0] >= 3_000) // the words still have their time
        assertTrue(18_000 - after.last() >= 800) // and the last one rings before the next line
    }

    @Test fun chordsWrittenTogetherNeverFlashBy() {
        // Two chords over the same word: they share the time to the next chord, not a millisecond each.
        val row = ChordRow.Lyric(0, "Vuelve corazón que te quiero", 0, listOf(PlacedChord(0, 0), PlacedChord(7, 1), PlacedChord(7, 2), PlacedChord(20, 3)))
        val times = PlayAlong.lyricTimes(row, 0, 6_000).map { it.first }
        times.zipWithNext().forEach { (a, b) -> assertTrue("$a → $b", b - a >= 300) }
        assertEquals(listOf(0, 1, 2, 3), PlayAlong.lyricTimes(row, 0, 6_000).map { it.second })
    }

    @Test fun chordsAtTheEndOfTheLineGetTimeToBeSeen() {
        // Luis Enrique, "No sé si después de amanecer": A/B over "amanecer", Bm after it. A/B used
        // to come at 85% of the line and Bm right after: half a second of A/B.
        val text = "No sé si después de amanecer"
        val row = ChordRow.Lyric(0, text, 30_000, listOf(PlacedChord(text.indexOf("amanecer"), 0), PlacedChord(text.length, 1)))
        val times = PlayAlong.lyricTimes(row, 30_000, 34_500).map { it.first }
        assertTrue(times[1] - times[0] >= 1_200)
        assertTrue(34_500 - times[1] >= 1_200)
        // Still on the line's second half: it's where the words put them.
        assertTrue(times[0] > 31_500)
    }

    @Test fun moreChordsThanFitShareTheLineEvenly() {
        val row = ChordRow.Lyric(0, "Uh uh", 0, (0 until 6).map { PlacedChord(it, it) })
        val times = PlayAlong.lyricTimes(row, 0, 3_000).map { it.first }
        times.zipWithNext().forEach { (a, b) -> assertTrue("$a → $b", b - a >= 500) }
        assertTrue(times.first() >= 0 && times.last() <= 2_500)
    }

    @Test fun chordsAtTheSameMomentKeepTheirOrder() {
        val rows = listOf(ChordRow.Lyric(0, "", 1_000, listOf(PlacedChord(0, 0), PlacedChord(0, 1))))
        val starts = PlayAlong.fromRows(rows, 10_000)
        assertEquals(listOf(0, 1), starts.map { it.second })
        assertTrue(starts[1].first > starts[0].first)
    }
}
