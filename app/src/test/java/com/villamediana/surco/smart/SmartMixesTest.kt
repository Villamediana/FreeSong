package com.villamediana.surco.smart

import com.villamediana.surco.data.db.Track
import com.villamediana.surco.smart.TestLibrary.Companion.BASE_TIME
import com.villamediana.surco.smart.TestLibrary.Companion.DAY_MS
import com.villamediana.surco.smart.TestLibrary.Companion.library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SmartMixesTest {
    private val labels = MixLabels(
        genreMix = { "Mix $it" },
        genreMixSubtitle = { "$it e mais" },
        liked = "Curtidas",
        likedSubtitle = "liked-sub",
        onRepeat = "No repeat",
        onRepeatSubtitle = "repeat-sub",
        rediscover = "Redescubra",
        rediscoverSubtitle = "rediscover-sub",
        fresh = "Novidades",
        freshSubtitle = "fresh-sub",
        decade = { "Anos $it" },
        decadeSubtitle = "decade-sub",
        artistMix = { "Mix de $it" },
        daily = "Mix do dia",
        dailySubtitle = "daily-sub",
    )

    /** A varied library with enough of everything for every mix kind. */
    private val big: List<Track> = library {
        repeat(8) { add("Bad Bunny", genre = "Reggaeton", album = "Un Verano", year = 2022, liked = it < 3, playCount = it) }
        repeat(6) { add("Karol G", genre = "Reggaeton", album = "Mañana", year = 2023) }
        repeat(7) { add("Djavan", genre = "MPB", album = "Luz", year = 1982, playCount = 2) }
        repeat(5) { add("Caetano $it", genre = "MPB", year = 1975) }
        repeat(9) { add("Soda Stereo", genre = "Rock en Español", album = "Canción Animal", year = 1990, lastPlayedAt = BASE_TIME - 90 * DAY_MS, playCount = 3) }
        repeat(4) { add("Jazz $it", genre = "Jazz", year = 1959) }
        repeat(6) { add("New $it", genre = "Pop", addedAt = BASE_TIME - it * DAY_MS) }
    }
    private val taste = Taste(
        recentPlays = big.filter { it.artist == "Djavan" }.associate { it.id to 4 } + mapOf(big[0].id to 2),
        justPlayed = emptySet(),
        now = BASE_TIME,
    )

    @Test
    fun `builds every kind that has enough songs`() {
        val mixes = SmartMixes.build(big, taste, labels)
        val ids = mixes.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(SmartMixes.ID_DAILY in ids)
        assertTrue(SmartMixes.ID_ON_REPEAT in ids)
        assertTrue(SmartMixes.ID_FRESH in ids)
        assertTrue(SmartMixes.ID_REDISCOVER in ids)
        assertTrue("genre:reggaeton" in ids)
        assertTrue("genre:mpb" in ids)
        assertTrue("genre:rock-en-espanol" in ids)
        assertTrue("artist:bad-bunny" in ids)
        assertTrue("artist:soda-stereo" in ids)
        assertTrue("decade:2020" in ids)
        assertTrue("decade:1990" in ids)
        // Too few: 4 jazz songs, 3 liked songs, Karol G has exactly 6 → yes; Caetanos are 5 different artists.
        assertFalse("genre:jazz" in ids)
        assertFalse(SmartMixes.ID_LIKED in ids)
        assertTrue("artist:karol-g" in ids)
        assertFalse(ids.any { it.startsWith("artist:caetano") })
        assertFalse("decade:1950" in ids)
    }

    @Test
    fun `thresholds are exact`() {
        fun ids(lib: List<Track>, t: Taste = neutralTaste()) = SmartMixes.build(lib, t, labels).map { it.id }.toSet()
        assertFalse("genre:pop" in ids(library { repeat(4) { add("A$it", genre = "Pop") } }))
        assertTrue("genre:pop" in ids(library { repeat(5) { add("A$it", genre = "Pop") } }))
        assertFalse("decade:1980" in ids(library { repeat(7) { add("A$it", year = 1985) } }))
        assertTrue("decade:1980" in ids(library { repeat(8) { add("A$it", year = 1985) } }))
        assertFalse("artist:x" in ids(library { repeat(5) { add("X") } }))
        assertTrue("artist:x" in ids(library { repeat(6) { add("X") } }))
        assertFalse(SmartMixes.ID_LIKED in ids(library { repeat(4) { add("A$it", liked = true) } }))
        assertTrue(SmartMixes.ID_LIKED in ids(library { repeat(5) { add("A$it", liked = true) } }))
        assertFalse(SmartMixes.ID_DAILY in ids(library { repeat(9) { add("A$it") } }))
        assertTrue(SmartMixes.ID_DAILY in ids(library { repeat(10) { add("A$it") } }))
        assertTrue(SmartMixes.build(emptyList(), neutralTaste(), labels).isEmpty())
    }

    @Test
    fun `byId rebuilds exactly the same mix`() {
        val mixes = SmartMixes.build(big, taste, labels)
        mixes.forEach { mix -> assertEquals(mix.id, mix, SmartMixes.byId(mix.id, big, taste, labels)) }
        // Order of the library list does not matter.
        mixes.forEach { mix -> assertEquals(mix.id, mix, SmartMixes.byId(mix.id, big.reversed(), taste, labels)) }
        assertNull(SmartMixes.byId("genre:jazz", big, taste, labels))
        assertNull(SmartMixes.byId("nonsense", big, taste, labels))
    }

    @Test
    fun `mixes stay the same all day and change the next day`() {
        val zone = TimeZone.getDefault()
        val day = SmartMixes.dayNumber(BASE_TIME, zone)
        val morning = day * DAY_MS - zone.getOffset(BASE_TIME) + 8 * 3_600_000L
        val night = morning + 14 * 3_600_000L
        assertEquals(day, SmartMixes.dayNumber(morning, zone))
        assertEquals(day, SmartMixes.dayNumber(night, zone))
        val a = SmartMixes.byId(SmartMixes.ID_DAILY, big, taste.copy(now = morning), labels)!!
        val b = SmartMixes.byId(SmartMixes.ID_DAILY, big, taste.copy(now = night), labels)!!
        val c = SmartMixes.byId(SmartMixes.ID_DAILY, big, taste.copy(now = morning + DAY_MS), labels)!!
        assertEquals(a.tracks.map { it.id }, b.tracks.map { it.id })
        assertNotEquals(a.tracks.map { it.id }, c.tracks.map { it.id })
    }

    @Test
    fun `daily mix mixes favourites with discovery`() {
        val daily = SmartMixes.byId(SmartMixes.ID_DAILY, big, taste, labels)!!
        assertEquals(Mix.Kind.DAILY, daily.kind)
        assertEquals(SmartMixes.DAILY_SIZE, daily.tracks.size)
        assertEquals(daily.tracks.size, daily.tracks.map { it.id }.distinct().size)
        val favourites = daily.tracks.count { it.liked || it.playCount > 0 || (taste.recentPlays[it.id] ?: 0) > 0 }
        assertTrue("favourites $favourites", favourites in 10..20)
        assertTrue("discovery", daily.tracks.any { it.playCount == 0 && !it.liked })
    }

    @Test
    fun `on repeat is the most played of the month, most first`() {
        val mix = SmartMixes.byId(SmartMixes.ID_ON_REPEAT, big, taste, labels)!!
        assertTrue(mix.tracks.all { (taste.recentPlays[it.id] ?: 0) >= 2 })
        val plays = mix.tracks.map { taste.recentPlays.getValue(it.id) }
        assertEquals(plays.sortedDescending(), plays)
    }

    @Test
    fun `fresh has only songs added in the last two weeks`() {
        val mix = SmartMixes.byId(SmartMixes.ID_FRESH, big, taste, labels)!!
        assertEquals(6, mix.tracks.size)
        assertTrue(mix.tracks.all { it.addedAt >= BASE_TIME - 14 * DAY_MS })
    }

    @Test
    fun `rediscover has old favourites and forgotten songs, nothing from this month`() {
        val mix = SmartMixes.byId(SmartMixes.ID_REDISCOVER, big, taste, labels)!!
        assertTrue(mix.tracks.none { (taste.recentPlays[it.id] ?: 0) > 0 })
        assertTrue(mix.tracks.none { it.addedAt >= BASE_TIME - 14 * DAY_MS })
        assertTrue(mix.tracks.any { it.artist == "Soda Stereo" })
    }

    @Test
    fun `genre and artist mixes have titles, subtitles, covers and tint`() {
        val genre = SmartMixes.byId("genre:reggaeton", big, taste, labels)!!
        assertEquals("Mix Reggaeton", genre.title)
        assertTrue(genre.subtitle, genre.subtitle.startsWith("Bad Bunny, Karol G"))
        assertTrue(genre.tracks.take(14).all { it.genre == "Reggaeton" })
        assertEquals(genre.covers.distinct(), genre.covers)
        assertTrue(genre.covers.size in 1..4)
        assertNotNull(genre.tint)
        assertEquals(big.first { it.coverPath == genre.covers.first() }.accentColor, genre.tint)

        val artist = SmartMixes.byId("artist:bad-bunny", big, taste, labels)!!
        assertEquals("Mix de Bad Bunny", artist.title)
        assertTrue(artist.tracks.any { it.artist == "Karol G" })
        assertEquals(artist.tracks.size, artist.tracks.map { it.id }.distinct().size)

        val decade = SmartMixes.byId("decade:1990", big, taste, labels)!!
        assertEquals("Anos 1990", decade.title)
        assertTrue(decade.tracks.all { it.year in 1990..1999 })
    }

    @Test
    fun `covers are at most four, one per album`() {
        val lib = library { repeat(6) { a -> repeat(3) { add("Artist $a", album = "Album $a", genre = "Pop") } } }
        val covers = SmartMixes.coversOf(lib)
        assertEquals(4, covers.size)
        assertEquals(4, covers.distinct().size)
    }

    @Test
    fun `interleave keeps both orders and proportions`() {
        assertEquals(listOf(1, 10, 2, 20, 3), SmartMixes.interleave(listOf(1, 2, 3), listOf(10, 20)))
        assertEquals(listOf(1, 2), SmartMixes.interleave(listOf(1, 2), emptyList()))
        assertEquals(listOf(10), SmartMixes.interleave(emptyList(), listOf(10)))
    }
}
