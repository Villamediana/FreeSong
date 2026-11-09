package com.villamediana.sound.stats

import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.social.NumbersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** "Seus números": the period's totals and the week's rhythm, and what friends receive of them. */
class ListeningStatsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 20) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun online(id: String, artist: String, at: Long, ms: Long = 180_000) =
        OnlinePlay(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg", playedAt = at, listenedMs = ms, completed = true, skipped = false)

    private val now = at(2026, 11, 9, 21)
    private val plays = listOf(
        online("a", "Maná", at(2026, 10, 30)), online("b", "Maná", at(2026, 10, 31)), // last month
        online("c", "Djavan", at(2026, 11, 1)), online("d", "Maná", at(2026, 11, 2)),
        online("e", "Maná", at(2026, 11, 2, 9)),
    ) + (3..9).map { d -> online("x$d", "Rubel", at(2026, 11, d)) }

    @Test
    fun theMonthCountsOnlyItsDaysAndTheYearAllOfThem() {
        val month = ListeningStatsMaker.build(StatsPeriod.MONTH, emptyList(), emptyMap<Long, Track>(), plays, now, zone)
        assertEquals(10 * 180_000L, month.recap.listenedMs)
        assertEquals("Rubel", month.recap.topArtists.first().name)
        // Mondays this month: Nov 2 (two plays) and Nov 9 (one).
        assertEquals(7, month.weekdays.size)
        assertEquals(3 * 180_000L, month.weekdays[0])
        val year = ListeningStatsMaker.build(StatsPeriod.YEAR, emptyList(), emptyMap<Long, Track>(), plays, now, zone)
        assertEquals(12 * 180_000L, year.recap.listenedMs)
        assertEquals(at(2026, 1, 1, 0), ListeningStatsMaker.start(StatsPeriod.YEAR, now, zone))
        assertEquals(at(2026, 11, 1, 0), ListeningStatsMaker.start(StatsPeriod.MONTH, now, zone))
    }

    @Test
    fun friendsGetTotalsInMinutesAndOnlyCoversTheyCanOpen() {
        val n = NumbersOf.period(ListeningStatsMaker.build(StatsPeriod.MONTH, emptyList(), emptyMap<Long, Track>(), plays, now, zone))
        assertEquals(30L, n.minutes) // 10 plays of 3 min
        assertEquals(listOf(9L, 3L, 3L, 3L, 3L, 3L, 6L), n.weekdays) // Monday first; Sundays Nov 1 and 8
        assertEquals("Rubel", n.top.first().name)
        assertTrue(n.top.all { it.cover == null || it.cover!!.startsWith("https://") })
        // A downloaded song's cover is a file on this phone: friends don't get it.
        val track = Track(id = 1, title = "Oceano", artist = "Djavan", filePath = "/x", coverPath = "/data/covers/1.jpg")
        val local = NumbersOf.period(
            ListeningStatsMaker.build(StatsPeriod.MONTH, listOf(PlayEvent(trackId = 1, playedAt = at(2026, 11, 8), listenedMs = 600_000, completed = true, skipped = false)), mapOf(1L to track), emptyList(), now, zone),
        )
        assertEquals("Djavan", local.top.single().name)
        assertEquals(null, local.top.single().cover)
    }

    @Test
    fun nothingHeardIsEmpty() {
        assertTrue(ListeningStatsMaker.build(StatsPeriod.MONTH, emptyList(), emptyMap(), emptyList(), now, zone).empty)
    }
}
