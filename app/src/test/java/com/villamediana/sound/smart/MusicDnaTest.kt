package com.villamediana.sound.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The musical DNA: the moment's artists, skips that know the moment, places, friends. */
class MusicDnaTest {
    private val zone = ZoneOffset.UTC
    /** A Wednesday. */
    private val today = LocalDate.of(2026, 11, 11)
    private val now = today.atTime(23, 0).toInstant(zone).toEpochMilli()

    private fun at(daysAgo: Int, hour: Int): Long = today.minusDays(daysAgo.toLong()).atTime(hour, 10).toInstant(zone).toEpochMilli()

    private fun heard(artist: String, song: Int, at: Long, place: Long? = null, people: List<String> = emptyList(), genre: String? = null) =
        Listen("${artist.take(4)}$song".padEnd(11, 'x'), "$artist $song", artist, genre, at, 200_000, 200_000, completed = true, skipped = false, place = place, people = people)

    private fun skipped(artist: String, song: Int, at: Long) =
        Listen("${artist.take(4)}$song".padEnd(11, 'x'), "$artist $song", artist, null, at, 6_000, 200_000, completed = false, skipped = true)

    private val night = DnaMoment(DayPart.EVENING, weekend = false)
    private val morning = DnaMoment(DayPart.MORNING, weekend = false)

    /** Gaitas in the evenings, eighties in the mornings, Djavan any time. */
    private fun life(): List<Listen> = (1..20).flatMap { d ->
        listOf(
            heard("Gaiteros", d % 5, at(d, 21)),
            heard("Gaiteros", (d + 1) % 5, at(d, 22)),
            heard("Eighties", d % 5, at(d, 8)),
            heard("Eighties", (d + 2) % 5, at(d, 9)),
            heard("Djavan", d % 4, at(d, if (d % 2 == 0) 9 else 21)),
        )
    }

    @Test
    fun eachPartOfTheDayHasItsArtists() {
        val dna = MusicDna.build(life(), emptySet(), now, zone)
        assertTrue(dna.lift("gaiteros", night) > 1.4)
        assertTrue(dna.lift("gaiteros", morning) < 0.7)
        assertTrue(dna.lift("eighties", morning) > 1.4)
        // Heard at any time: as usual everywhere.
        assertEquals(1.0, dna.lift("djavan", night), 0.25)
        assertEquals(1.0, dna.lift("djavan", morning), 0.25)
        // An artist never heard: nothing to say.
        assertEquals(1.0, dna.lift("unknown", night), 0.0)
    }

    @Test
    fun aFewListensMoveLittleManyMoveMore() {
        val few = MusicDna.build(listOf(heard("A", 1, at(1, 21)), heard("B", 1, at(1, 9))), emptySet(), now, zone)
        val many = MusicDna.build((1..30).flatMap { listOf(heard("A", it % 5, at(it, 21)), heard("B", it % 5, at(it, 9))) }, emptySet(), now, zone)
        assertTrue(few.lift("a", night) < many.lift("a", night))
        assertTrue(few.lift("a", night) > 1.0)
    }

    @Test
    fun aSkipTeachesTheDnaNothing() {
        // Miguel: a song skipped may be loved, just not for now — the DNA doesn't learn against it.
        val skips = (1..6).map { skipped("Gaiteros", 1, at(it, 22)) }
        val plain = MusicDna.build(life(), emptySet(), now, zone)
        val skipped = MusicDna.build(life() + skips, emptySet(), now, zone)
        assertEquals(plain.lift("gaiteros", night), skipped.lift("gaiteros", night), 0.0)
        assertEquals(plain.songsFor(night, 6).map { it.videoId }, skipped.songsFor(night, 6).map { it.videoId })
    }

    @Test
    fun placesAndFriendsHaveTheirOwnSound() {
        val home = 1L
        val gym = 2L
        val listens = (1..15).flatMap { d ->
            listOf(
                heard("Gaiteros", d % 5, at(d, 13), place = home),
                heard("Metal", d % 5, at(d, 13), place = gym),
                heard("Samba", d % 5, at(d, 13), people = listOf("uana")),
                heard("Metal", (d + 1) % 5, at(d, 14)),
            )
        }
        val dna = MusicDna.build(listens, emptySet(), now, zone)
        val afternoon = DnaMoment(DayPart.AFTERNOON, weekend = false)
        assertTrue(dna.lift("gaiteros", afternoon.copy(place = home)) > dna.lift("gaiteros", afternoon.copy(place = gym)))
        assertTrue(dna.lift("metal", afternoon.copy(place = gym)) > dna.lift("metal", afternoon.copy(place = home)))
        assertTrue(dna.lift("samba", afternoon.copy(people = setOf("uana"))) > 1.5)
        // A place or a friend not known yet doesn't move anything.
        assertEquals(dna.lift("samba", afternoon), dna.lift("samba", afternoon.copy(place = 99, people = setOf("ubia"))), 0.0)
        // The songs heard with Ana.
        assertTrue(dna.together(setOf("uana"), 10).all { it.artist == "Samba" })
        assertEquals(5, dna.together(setOf("uana"), 10).size)
    }

    @Test
    fun theSongsForTheMomentAreTheMoments() {
        val dna = MusicDna.build(life(), emptySet(), now, zone)
        val forNight = dna.songsFor(night, 6)
        assertTrue(forNight.take(3).all { it.artist == "Gaiteros" })
        assertTrue(dna.songsFor(morning, 6).take(3).all { it.artist == "Eighties" })
    }

    @Test
    fun theTasteOfTheMomentLeansToItsArtists() {
        val listens = life()
        val profile = TasteProfile.build(listens, emptyList(), now)
        val dna = MusicDna.build(listens, emptySet(), now, zone)
        val atNight = profile.inMoment(dna, night)
        val atMorning = profile.inMoment(dna, morning)
        assertTrue(atNight.affinityOf("Gaiteros") > atMorning.affinityOf("Gaiteros"))
        assertTrue(atMorning.affinityOf("Eighties") > atNight.affinityOf("Eighties"))
        assertNotEquals(atNight.artists.first().key, atMorning.artists.first().key)
    }

    @Test
    fun theSummaryTellsTheDayTheWeekAndThePlaces() {
        val listens = life() + (1..12).map { heard("Gaiteros", it % 5, at(it, 21), place = 7L) }
        val s = MusicDna.build(listens, emptySet(), now, zone).summary
        assertEquals(24, s.hourMinutes.size)
        assertTrue(s.hourMinutes[21] > s.hourMinutes[3])
        assertEquals(DayPart.EVENING, s.mainPart)
        assertEquals("Gaiteros", s.parts.first { it.part == DayPart.EVENING }.artists.first())
        assertEquals("Eighties", s.parts.first { it.part == DayPart.MORNING }.artists.first())
        val place = s.places.single()
        assertEquals(7L, place.id)
        assertEquals(DayPart.EVENING, place.part)
        assertEquals("Gaiteros", place.artists.first())
    }

    @Test
    fun aMomentIsTheClockTheWeekThePlaceAndWhoIsAlong() {
        val saturdayNight = LocalDate.of(2026, 11, 14).atTime(22, 30).toInstant(zone).toEpochMilli()
        val m = DnaMoment.at(saturdayNight, zone, place = 3L, people = setOf("ub", "ua"))
        assertEquals(DayPart.EVENING, m.part)
        assertTrue(m.weekend)
        assertEquals("EVENING:we:3:ua,ub", m.key)
        assertEquals(DayPart.NIGHT_LATE, DnaMoment.at(LocalDate.of(2026, 11, 11).atTime(3, 0).toInstant(zone).toEpochMilli(), zone).part)
    }

    @Test
    fun aPlaceIsWhereYouKeepComingBack() {
        val home = PlaceClusters.locate(emptyList(), -23.5505, -46.6333, 1_000)
        assertEquals(0L, home.id)
        val known = listOf(home.copy(id = 1))
        // 60 m away: the same place, its middle moved a little.
        val again = PlaceClusters.locate(known, -23.5500, -46.6333, 2_000)
        assertEquals(1L, again.id)
        assertEquals(2, again.fixes)
        assertTrue(again.lat > home.lat)
        // 2 km away: another place.
        assertEquals(0L, PlaceClusters.locate(known, -23.5325, -46.6333, 3_000).id)
        assertEquals(2_000.0, PlaceClusters.distanceM(-23.5505, -46.6333, -23.5325, -46.6333), 50.0)
    }
}
