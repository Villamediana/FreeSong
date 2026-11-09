package com.villamediana.sound.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The story's order, what enters it, and which song plays behind each chapter. */
class RecapStoryPlanTest {
    private fun song(n: Int) = RecapSong("v$n", "Song $n", "Artist $n", null, plays = 10 - n, listenedMs = 1_000)
    private fun artist(n: Int) = RecapArtist("Artist $n", null, plays = 10 - n, listenedMs = 1_000)

    private val full = Recap(
        year = 2026,
        listenedMs = 3_600_000,
        plays = 30,
        songs = 5,
        artists = 3,
        topSongs = (1..5).map(::song),
        topArtists = (1..3).map(::artist),
        topGenre = "MPB",
        favoriteHour = 22,
        months = List(12) { if (it in 3..7) 1_000L else 0L },
        daysListened = 40,
        longestStreak = 9,
        firstSong = song(4),
        firstAt = 1L,
    )

    @Test
    fun aFullYearTellsEveryChapterInOrder() {
        assertEquals(RecapChapter.entries.toList(), RecapStoryPlan.chapters(full))
    }

    @Test
    fun aChapterWithNothingToTellIsLeftOut() {
        val thin = full.copy(topGenre = null, favoriteHour = null, firstSong = null, months = List(12) { if (it == 2) 5L else 0L }, topSongs = listOf(song(1)), topArtists = listOf(artist(1)))
        assertEquals(
            listOf(RecapChapter.INTRO, RecapChapter.TIME, RecapChapter.ARTIST, RecapChapter.SONG, RecapChapter.OUTRO),
            RecapStoryPlan.chapters(thin),
        )
        // Only the last screen waits; the others move on by themselves.
        assertEquals(listOf(RecapChapter.OUTRO), RecapChapter.entries.filter { !it.advances })
    }

    @Test
    fun theNumberOneWaitsForItsOwnChapter() {
        val chapters = RecapStoryPlan.chapters(full)
        val song = chapters.indexOf(RecapChapter.SONG)
        assertEquals(1, RecapStoryPlan.soundtrack(full, chapters, 0))
        assertEquals(1, RecapStoryPlan.soundtrack(full, chapters, song - 1))
        assertEquals(0, RecapStoryPlan.soundtrack(full, chapters, song))
        assertEquals(0, RecapStoryPlan.soundtrack(full, chapters, chapters.lastIndex))
        // One song: it plays from the start. None: silence.
        val one = full.copy(topSongs = listOf(song(1)))
        assertEquals(0, RecapStoryPlan.soundtrack(one, RecapStoryPlan.chapters(one), 0))
        assertNull(RecapStoryPlan.soundtrack(full.copy(topSongs = emptyList()), chapters, 0))
    }

    @Test
    fun theHourSaysWhoYouAreAndThePeakMonthIsTheLoudest() {
        assertEquals(PartOfDay.DAWN, RecapStoryPlan.partOfDay(3))
        assertEquals(PartOfDay.MORNING, RecapStoryPlan.partOfDay(5))
        assertEquals(PartOfDay.AFTERNOON, RecapStoryPlan.partOfDay(17))
        assertEquals(PartOfDay.NIGHT, RecapStoryPlan.partOfDay(18))
        assertEquals(PartOfDay.NIGHT, RecapStoryPlan.partOfDay(23))
        assertEquals(6, RecapStoryPlan.peakMonth(full.copy(months = List(12) { if (it == 6) 9L else 1L })))
        assertNull(RecapStoryPlan.peakMonth(full.copy(months = List(12) { 0L })))
    }
}
