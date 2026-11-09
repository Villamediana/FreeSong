package com.villamediana.sound.stats

import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RecapTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun track(id: Long, title: String, artist: String, videoId: String? = null, genre: String? = null) =
        Track(id = id, title = title, artist = artist, filePath = "/x/$id", videoId = videoId, genre = genre, coverPath = "/c/$id.jpg")

    private fun event(trackId: Long, at: Long, ms: Long = 200_000, skipped: Boolean = false) =
        PlayEvent(trackId = trackId, playedAt = at, listenedMs = ms, completed = !skipped, skipped = skipped)

    private fun online(videoId: String, title: String, artist: String, at: Long, ms: Long = 180_000, skipped: Boolean = false) =
        OnlinePlay(videoId = videoId, title = title, artist = artist, thumbnailUrl = "https://i/$videoId", playedAt = at, listenedMs = ms, completed = !skipped, skipped = skipped)

    @Test fun onceAYearAllThroughJanuaryWithTheYearThatEnded() {
        assertEquals(null, Recaps.yearShown(at(2026, 11, 7, 15), zone))
        assertEquals(null, Recaps.yearShown(at(2026, 12, 31, 23), zone))
        assertEquals(2026, Recaps.yearShown(at(2027, 1, 1, 0), zone))
        assertEquals(2026, Recaps.yearShown(at(2027, 1, 31, 23), zone))
        assertEquals(null, Recaps.yearShown(at(2027, 2, 1, 0), zone))
        assertEquals(at(2026, 1, 1, 0) until at(2027, 1, 1, 0), Recaps.span(2026, zone))
    }

    @Test fun onlyTheYearCounts() {
        val online = listOf(
            online("a", "A", "X", at(2025, 12, 31, 23)),
            online("b", "B", "Y", at(2026, 6, 1, 12)),
            online("c", "C", "Z", at(2027, 1, 2, 10)),
        )
        assertEquals(listOf("B"), Recaps.build(2026, emptyList(), emptyMap(), online, zone).topSongs.map { it.title })
    }

    @Test fun libraryAndOnlineListensAddUpAndTheSameSongCountsOnce() {
        val iris = track(1, "Iris", "Goo Goo Dolls", videoId = "iris", genre = "Rock")
        val slide = track(2, "Slide", "Goo Goo Dolls", genre = "Rock")
        val events = listOf(
            event(1, at(2026, 11, 3, 22)),
            event(1, at(2026, 11, 4, 22)),
            event(2, at(2026, 11, 4, 9), ms = 20_000, skipped = true),
        )
        val online = listOf(
            // Heard online before downloading it: the same song as the library's Iris.
            online("iris", "Iris", "Goo Goo Dolls", at(2026, 11, 2, 22)),
            online("bad", "Bad Guy", "Billie Eilish, Finneas", at(2026, 11, 6, 22)),
            online("bad", "Bad Guy", "Billie Eilish, Finneas", at(2026, 11, 6, 23)),
            online("bad", "Bad Guy", "Billie Eilish, Finneas", at(2026, 11, 7, 1), ms = 10_000, skipped = true),
        )
        val r = Recaps.build(2026, events, mapOf(1L to iris, 2L to slide), online, zone)

        assertEquals(200_000L * 2 + 20_000 + 180_000 * 3 + 10_000, r.listenedMs)
        assertEquals(5, r.plays)
        assertEquals(3, r.songs)
        assertEquals(listOf("Iris", "Bad Guy"), r.topSongs.map { it.title })
        assertEquals(3, r.topSongs.first().plays)
        // The library copy plays offline: the ranking points at it.
        assertEquals(iris, r.topSongs.first().track)
        assertEquals("bad", r.topSongs[1].remote?.videoId)
        // Skipped listens don't rank (Slide), the main artist names the artist.
        assertEquals(listOf("Goo Goo Dolls", "Billie Eilish"), r.topArtists.map { it.name })
        assertEquals("Rock", r.topGenre)
        assertEquals(22, r.favoriteHour)
        assertNotNull(r.favoriteDay)
    }

    @Test fun nothingHeardIsEmpty() {
        val r = Recaps.build(2026, emptyList(), emptyMap(), emptyList(), zone)
        assertTrue(r.empty)
        assertNull(r.favoriteHour)
        assertTrue(r.topSongs.isEmpty())
    }

    @Test fun favoriteDayIsWhereMostTimeWent() {
        val friday = at(2026, 11, 6, 20) // a Friday
        val online = listOf(online("a", "A", "X", friday, ms = 600_000), online("b", "B", "Y", at(2026, 11, 3, 20), ms = 100_000))
        assertEquals(DayOfWeek.FRIDAY, Recaps.build(2026, emptyList(), emptyMap(), online, zone).favoriteDay)
    }

    @Test fun theStoryKnowsTheMonthsTheDaysTheRunAndTheFirstSong() {
        val lib = track(1, "Oceano", "Djavan", videoId = "oceano")
        val events = listOf(
            event(1, at(2026, 1, 1, 0) + 14 * 60_000L, skipped = true), // skipped: not the first song
            event(1, at(2026, 7, 10, 22), ms = 300_000),
            event(1, at(2026, 7, 11, 22), ms = 300_000),
        )
        val online = listOf(
            online("trem", "Trem-Bala", "Ana Vilela", at(2026, 1, 1, 9)),
            online("trem", "Trem-Bala", "Ana Vilela", at(2026, 7, 12, 8)),
            online("trem", "Trem-Bala", "Ana Vilela", at(2025, 12, 31, 23)), // last year
        )
        val r = Recaps.build(2026, events, mapOf(1L to lib), online, zone)
        assertEquals("Trem-Bala", r.firstSong!!.title)
        assertEquals(at(2026, 1, 1, 9), r.firstAt)
        assertEquals(200_000L + 180_000L, r.months[0])
        assertEquals(600_000L + 180_000L, r.months[6])
        assertEquals(12, r.months.size)
        assertEquals(4, r.daysListened) // Jan 1, Jul 10, 11, 12
        assertEquals(3, r.longestStreak)
    }

    @Test fun mainArtistDropsTheGuests() {
        assertEquals("Lady Gaga", Recaps.mainArtist("Lady Gaga, Bruno Mars"))
        assertEquals("Shakira", Recaps.mainArtist("Shakira feat. Wyclef Jean"))
        assertEquals("Ana", Recaps.mainArtist("Ana & Vitória"))
    }
}
