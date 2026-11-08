package com.villamediana.sound.chords

import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordLayoutTest {
    private fun chart(vararg chords: Pair<Long, String>, end: Long): ChordChart = ChordChart(
        chords.mapIndexed { i, (start, name) ->
            val c = Chord.parse(name)!!
            TimedChord(start, chords.getOrNull(i + 1)?.first ?: end, c.root, c.quality, c.bass)
        },
        key = MusicKey(7, false),
    )

    private fun lyrics(vararg lines: Pair<Long, String>) = ParsedLyrics(true, lines.map { LyricLine(it.first, it.second) })

    @Test fun introThenEachLineStartsOnItsChord() {
        val chart = chart(0L to "G", 4_000L to "D", 8_000L to "Em", 12_000L to "C", 16_000L to "G", end = 30_000)
        val rows = ChordLayout.rows(
            lyrics(8_000L to "Quando a gente ama", 12_000L to "não pensa em dinheiro", 16_000L to "só quer amar"),
            chart,
            durationMs = 30_000,
        )
        val intro = rows[0] as ChordRow.Instrumental
        assertEquals(listOf(0, 1), intro.chords) // G D before the voice
        val first = rows[1] as ChordRow.Lyric
        assertEquals(listOf(PlacedChord(0, 2)), first.chords) // Em on "Quando"
        val second = rows[2] as ChordRow.Lyric
        assertEquals(listOf(PlacedChord(0, 3)), second.chords)
        val last = rows[3] as ChordRow.Lyric
        assertEquals(listOf(PlacedChord(0, 4)), last.chords)
        assertEquals(4, rows.size)
    }

    @Test fun aChangeInsideTheLineGoesOverTheWordItFallsIn() {
        // The line is sung over about 4.5 s; D comes in half way.
        val chart = chart(0L to "G", 2_250L to "D", 6_000L to "C", end = 20_000)
        val text = "Eu sei que vou te amar"
        val rows = ChordLayout.rows(lyrics(0L to text, 6_000L to "por toda a minha vida"), chart, 20_000)
        val line = rows[0] as ChordRow.Lyric
        assertEquals(0, line.chords[0].at)
        val d = line.chords[1]
        assertEquals(1, d.index)
        // Over the start of a word in the middle of the line.
        assertTrue(d.at > 0 && text[d.at - 1] == ' ' && d.at < text.length)
        assertTrue("D at ${d.at}", d.at in 7..15)
    }

    @Test fun aSoloBetweenVersesIsARowOfItsOwn() {
        val chart = chart(0L to "Am", 3_000L to "F", 12_000L to "C", 14_000L to "G", 20_000L to "Am", end = 30_000)
        val rows = ChordLayout.rows(lyrics(0L to "Linha curta", 20_000L to "volta a letra"), chart, 30_000)
        assertTrue(rows[0] is ChordRow.Lyric)
        val solo = rows[1] as ChordRow.Instrumental
        assertEquals(listOf(2, 3), solo.chords) // C G while nobody sings
        assertTrue(rows[2] is ChordRow.Lyric)
        assertEquals(12_000L, solo.startMs)
    }

    @Test fun anEmptyLyricLineIsTheMusicAlone() {
        val chart = chart(0L to "C", 5_000L to "G", 9_000L to "F", end = 20_000)
        val rows = ChordLayout.rows(lyrics(0L to "Um", 5_000L to "", 10_000L to "Dois"), chart, 20_000)
        val gap = rows[1] as ChordRow.Instrumental
        assertEquals(listOf(1, 2), gap.chords)
    }

    @Test fun chordsNeverShareAWord() {
        val taken = listOf(PlacedChord(0, 0))
        assertEquals(4, ChordLayout.slot("Meu amor", 1, taken))
        assertEquals(8, ChordLayout.slot("Meu amor", 6, taken + PlacedChord(4, 1)))
    }

    @Test fun withoutLyricsTheChordsRunInRows() {
        val chart = chart(*Array(20) { (it * 2_000L) to listOf("C", "G", "Am", "F")[it % 4] }, end = 40_000).copy(bpm = 120.0)
        val rows = ChordLayout.rows(ParsedLyrics(false, emptyList()), chart, 40_000)
        assertTrue(rows.all { it is ChordRow.Instrumental })
        assertEquals(20, rows.sumOf { (it as ChordRow.Instrumental).chords.size })
        // Four bars at 120 BPM = 8 s = four chords of two seconds.
        assertEquals(4, (rows[0] as ChordRow.Instrumental).chords.size)
    }

    @Test fun theRowBeingPlayed() {
        val rows = listOf(ChordRow.Instrumental(0, listOf(0)), ChordRow.Lyric(0, "a", 5_000, emptyList()), ChordRow.Lyric(1, "b", 9_000, emptyList()))
        assertEquals(-1, ChordLayout.rowAt(rows, -5))
        assertEquals(0, ChordLayout.rowAt(rows, 4_999))
        assertEquals(1, ChordLayout.rowAt(rows, 5_000))
        assertEquals(2, ChordLayout.rowAt(rows, 60_000))
    }

    @Test fun lyricsWithoutTimesStillGetTheirChordsOverTheWords() {
        // A 3:30 song, chords every 2 s; the lyrics as a site gives them: no times, verses apart.
        val names = listOf("Dm", "Bb", "Gm", "F")
        val chart = ChordChart((0 until 105).map { k -> val c = Chord.parse(names[k % 4])!!; TimedChord(k * 2_000L, (k + 1) * 2_000L, c.root, c.quality) }, MusicKey(2, true))
        val plain = ParsedLyrics(
            false,
            listOf("El confesor me dice que no te quiera", "y yo le digo padre si usted la viera", "", "", "Dice que tus amores me vuelven loco", "que a mi deber no atiendo", "", "que duermo poco")
                .map { LyricLine(0, it) },
        )
        val rows = ChordLayout.rows(plain, chart, 210_000)
        val sung = rows.filterIsInstance<ChordRow.Lyric>()
        // Every line is there, with chords over it, in order of time, after an intro.
        assertEquals(5, sung.size)
        assertEquals("El confesor me dice que no te quiera", sung.first().text)
        assertTrue(sung.all { it.chords.isNotEmpty() })
        assertTrue(sung.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        assertTrue(rows.first() is ChordRow.Instrumental)
        assertTrue(sung.first().startMs in 4_000L..18_000L)
        // Each line starts on a chord change; the blank lines between verses are the band alone (one break, not two).
        assertTrue(sung.all { l -> chart.chords.any { it.startMs == l.startMs } })
        val est = ChordLayout.estimated(plain, chart, 210_000)
        assertEquals(7, est.lines.size)
        // Spread over the song, not crammed at the start.
        assertTrue(sung.last().startMs > 120_000)
    }

    @Test fun aFullSetOfLyricsWithoutTimesReadsLikeASheet() {
        // 44 lines for a 4:08 song (as plain lyrics usually are): the verses follow each other, the
        // band alone only before the voice and after it.
        val names = listOf("Dm", "Bb", "Gm7", "F")
        val chart = ChordChart((0 until 71).map { k -> val c = Chord.parse(names[k % 4])!!; TimedChord(k * 3_500L, (k + 1) * 3_500L, c.root, c.quality) }, MusicKey(10, false))
        val plain = ParsedLyrics(false, (1..44).map { LyricLine(0, "verso $it de una letra que no marca el tiempo") })
        val rows = ChordLayout.rows(plain, chart, 248_000)
        assertEquals(44, rows.count { it is ChordRow.Lyric })
        val inner = rows.drop(1).dropLast(1)
        assertTrue("chords-only rows between verses: ${inner.count { it is ChordRow.Instrumental }}", inner.none { it is ChordRow.Instrumental })
    }

    @Test fun noLyricsAtAllIsTheTimeline() {
        val chart = chart(0L to "G", 4_000L to "D", end = 8_000)
        assertTrue(ChordLayout.rows(ParsedLyrics(false, emptyList()), chart, 8_000).all { it is ChordRow.Instrumental })
    }
}
