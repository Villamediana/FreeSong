package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Chords heard from notes, the way Basic Pitch hands them over: bass, strummed chord, a tune on top. */
class ChordHearingTest {
    private val bpm = 100
    private val beat = 60_000L / bpm

    private fun c(name: String) = Chord.parse(name)!!

    /** [chords] a bar each (four beats), strummed on every beat, the bass on the root, a melody wandering in the key. */
    private fun play(chords: List<String>, melody: Boolean = true, seed: Int = 1, offset: Long = 40): List<Note> {
        val r = Random(seed)
        val scale = listOf(0, 2, 4, 5, 7, 9, 11)
        val notes = mutableListOf<Note>()
        chords.forEachIndexed { bar, name ->
            val chord = c(name)
            val iv = chord.quality.intervals
            val at = bar * 4 * beat + offset
            notes += Note(36 + chord.root, at, 4 * beat - 30, 90)
            for (b in 0 until 4) for (i in iv) {
                notes += Note(60 + ((chord.root + i) % 12), at + b * beat + r.nextLong(0, 25), beat - 40, 70)
            }
            if (melody) for (b in 0 until 4) {
                val pc = if (b % 2 == 0) (chord.root + iv[b % 3]) % 12 else scale[r.nextInt(scale.size)]
                notes += Note(72 + pc, at + b * beat, beat - 20, 100)
            }
        }
        return notes
    }

    @Test fun `a pop progression with a tune over it comes out as written`() {
        val song = List(3) { listOf("C", "G", "Am", "F") }.flatten()
        val heard = ChordHearing.hear(play(song), bpm)
        assertEquals(song.map(::c), heard.sequence)
        assertEquals(MusicKey(0, false), heard.key)
    }

    @Test fun `a seventh that rings is written, one that does not is not`() {
        val song = listOf("Dm7", "G7", "C", "C", "Am", "Dm", "G7", "C")
        val heard = ChordHearing.hear(play(song, melody = false), bpm)
        assertEquals(listOf("Dm7", "G7", "C", "Am", "Dm", "G7", "C").map(::c), heard.sequence)
    }

    @Test fun `a song in Bb with its minor chords`() {
        val song = List(2) { listOf("Bb", "Gm", "Eb", "F") }.flatten()
        val heard = ChordHearing.hear(play(song, seed = 7), bpm)
        assertEquals(song.map(::c), heard.sequence)
    }

    @Test fun `chords change on the beats they are given`() {
        // A band that drags a little: the beats are not an even grid.
        val song = listOf("G", "D", "Em", "C", "G", "D")
        val notes = play(song, melody = false, offset = 500)
        val beats = LongArray(song.size * 4 + 1) { 500 + it * beat + (if (it % 4 == 3) 30 else 0) }
        val heard = ChordHearing.hear(notes, beats)
        assertEquals(song.map(::c), heard.sequence)
        heard.chords.drop(1).forEach { h -> assertTrue("${h.chord} at ${h.startMs} is not on a beat", h.startMs in beats) }
    }

    @Test fun `nothing heard, nothing written`() {
        assertTrue(ChordHearing.hear(emptyList(), bpm).chords.isEmpty())
    }

    @Test fun `a messy transcription still reads right most of the time`() {
        // What Basic Pitch really gives: a third of the notes missed, stray notes, loose timing.
        val song = List(4) { listOf("G", "D", "Em", "C") }.flatten()
        val r = Random(11)
        val notes = play(song, seed = 11)
            .filter { r.nextFloat() > 0.33f }
            .map { it.copy(startMs = it.startMs + r.nextLong(-60, 60)) } +
            List(40) { Note(48 + r.nextInt(36), r.nextLong(0, song.size * 4 * beat), r.nextLong(80, 300), 60) }
        val heard = ChordHearing.hear(notes, bpm)
        val right = song.indices.count { bar ->
            val mid = bar * 4 * beat + 2 * beat
            heard.chords.firstOrNull { mid in it.startMs until it.endMs }?.chord == c(song[bar])
        }
        assertTrue("$right of ${song.size} bars right: ${heard.sequence}", right >= song.size * 0.8)
    }

    @Test fun `the grid is stretched over notes outside the beats`() {
        val grid = ChordHearing.extend(longArrayOf(1_000, 1_500, 2_000), 0, 3_200)
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L, 3_500L), grid.toList())
    }
}
