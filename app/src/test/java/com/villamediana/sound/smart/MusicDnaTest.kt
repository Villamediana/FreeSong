package com.villamediana.sound.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
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

    private val night = DnaMoment(22, DayOfWeek.WEDNESDAY)
    private val morning = DnaMoment(8, DayOfWeek.WEDNESDAY)

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
    fun aSongOfTheLibraryFitsItsMomentAndASkipHereTakesItDownOnlyHere() {
        val gaita = "Gait1".padEnd(11, 'x')
        val skips = (1..4).map { skipped("Gaiteros", 1, at(it, 22)) }
        val dna = MusicDna.build(life() + skips, emptySet(), now, zone)
        // The evening's band: up in the evening, down in the morning; a song without an id goes by its artist.
        assertTrue(dna.fit(null, "gaiteros", night) > 1.3)
        assertTrue(dna.fit(null, "gaiteros", morning) < 0.7)
        assertEquals(dna.lift("gaiteros", night), dna.fit("", "gaiteros", night), 1e-9)
        // The one skipped in the evenings fits them less than its band; in the morning the skips say little.
        assertTrue(dna.fit(gaita, "gaiteros", night) < dna.fit(null, "gaiteros", night) - 0.2)
        assertTrue(dna.fit(gaita, "gaiteros", morning) > 0.85 * dna.fit(null, "gaiteros", morning))
        // Nothing known: as always. And never past the bounds.
        assertEquals(1.0, dna.fit("nothing", "unknown", night), 0.0)
        assertTrue(dna.fit(gaita, "gaiteros", night) in MusicDna.FIT_MIN..MusicDna.FIT_MAX)
    }

    @Test
    fun aFewListensMoveLittleManyMoveMore() {
        val few = MusicDna.build(listOf(heard("A", 1, at(1, 21)), heard("B", 1, at(1, 9))), emptySet(), now, zone)
        val many = MusicDna.build((1..30).flatMap { listOf(heard("A", it % 5, at(it, 21)), heard("B", it % 5, at(it, 9))) }, emptySet(), now, zone)
        assertTrue(few.lift("a", night) < many.lift("a", night))
        assertTrue(few.lift("a", night) > 1.0)
    }

    @Test
    fun skipsTeachTheMomentTheyHappenedIn() {
        // A song loved in the mornings, skipped at night again and again.
        val nightly = "Eigh7xxxxxx"
        val listens = life() +
            (1..3).map { skipped("Eighties", 7, at(it, 22)) } +
            (1..5).map { heard("Eighties", 7, at(it, 8)) }
        val dna = MusicDna.build(listens, emptySet(), now, zone)
        assertTrue(dna.songFactor(nightly, night) < 0.6)
        assertTrue(dna.songFactor(nightly, morning) > 0.85)
        assertTrue(nightly in dna.avoidNow(night))
        assertFalse(nightly in dna.avoidNow(morning))
    }

    @Test
    fun oneSkipOnlyNudgesAndHeardThroughForgives() {
        val once = MusicDna.build(life() + skipped("Djavan", 9, at(1, 21)), emptySet(), now, zone)
        assertTrue(once.songFactor("Djav9xxxxxx", night) in 0.6..0.95)
        assertTrue("Djav9xxxxxx" !in once.avoidNow(night))
        // Skipped three nights, but heard through on six others: it's fine at night.
        val forgiven = MusicDna.build(
            life() + (1..3).map { skipped("Eighties", 7, at(it, 22)) } + (4..9).map { heard("Eighties", 7, at(it, 22)) },
            emptySet(), now, zone,
        )
        assertTrue("Eigh7xxxxxx" !in forgiven.avoidNow(night))
        assertTrue(forgiven.songFactor("Eigh7xxxxxx", night) > 0.7)
    }

    @Test
    fun skipsFadeAndTheSongComesBack() {
        val old = (100..104).map { skipped("Eighties", 7, at(it, 22)) }
        val dna = MusicDna.build(life() + old, emptySet(), now, zone)
        assertTrue(dna.songFactor("Eigh7xxxxxx", night) > 0.8)
        assertTrue("Eigh7xxxxxx" !in dna.avoidNow(night))
    }

    @Test
    fun aHeartKeepsASongFromGoingFarDown() {
        val listens = life() + (1..5).map { skipped("Eighties", 7, at(it, 22)) }
        val dna = MusicDna.build(listens, setOf("Eigh7xxxxxx"), now, zone)
        assertTrue(dna.songFactor("Eigh7xxxxxx", night) >= MusicDna.HEART_FLOOR)
        assertTrue("Eigh7xxxxxx" !in dna.avoidNow(night))
    }

    @Test
    fun anArtistAndARitmoSkippedAtNightFitTheNightLess() {
        val plain = MusicDna.build(life(), emptySet(), now, zone)
        val skips = (1..8).map { skipped("Djavan", it % 4, at(it, 22)) }
        val dna = MusicDna.build(life() + skips, emptySet(), now, zone)
        assertTrue(dna.lift("djavan", night) < plain.lift("djavan", night) * 0.85)
        assertEquals(plain.lift("djavan", morning), dna.lift("djavan", morning), 0.05)
        // The genre too (the "ritmo").
        val withGenre = { list: List<Listen> -> MusicDna.build(list, emptySet(), now, zone, genreOf = { a, _ -> if (a == "djavan") "mpb" else "rock" }) }
        assertTrue(withGenre(life() + skips).genreLift("mpb", night) < withGenre(life()).genreLift("mpb", night))
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
        val afternoon = DnaMoment(13, DayOfWeek.WEDNESDAY)
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
    fun aMomentNeverLivedHasNoSongsOfItsOwn() {
        // Only nights so far: the morning has nothing to say (not the night's songs under its name).
        val nights = (1..10).flatMap { d -> (0..5).map { heard("Gaiteros", (d + it) % 8, at(d, 22)) } }
        val dna = MusicDna.build(nights, emptySet(), now, zone)
        assertTrue(dna.songsFor(night, 16).size >= 5)
        assertTrue(dna.songsFor(morning, 16).isEmpty())
        // The late afternoon is still too far from the nights.
        assertTrue(dna.songsFor(DnaMoment(17, DayOfWeek.WEDNESDAY), 16).isEmpty())

        // Lived once: the morning gets its songs — its own, none of the night's.
        val lived = MusicDna.build(nights + (1..5).map { heard("Eighties", it, at(1, 9)) }, emptySet(), now, zone)
        val forMorning = lived.songsFor(morning, 16)
        assertEquals(5, forMorning.size)
        assertTrue(forMorning.all { it.artist == "Eighties" })
        // The night keeps its own.
        assertTrue(lived.songsFor(night, 16).all { it.artist == "Gaiteros" })
    }

    @Test
    fun aSongOfTheMomentWasReallyHeardInItAndNotMonthsAgo() {
        val nights = (1..10).flatMap { d -> (0..5).map { heard("Gaiteros", (d + it) % 8, at(d, 22)) } }
        // Half a minute of a song in the morning is not the morning's song; heard through, it is.
        val brief = heard("Eighties", 1, at(1, 8)).copy(listenedMs = 40_000, completed = false)
        assertTrue(MusicDna.build(nights + brief, emptySet(), now, zone).songsFor(morning, 16).isEmpty())
        assertEquals(1, MusicDna.build(nights + heard("Eighties", 1, at(1, 8)), emptySet(), now, zone).songsFor(morning, 16).size)
        // Across midnight the hours are still neighbours.
        assertEquals(1, MusicDna.build(listOf(heard("Gaiteros", 1, at(1, 1))), emptySet(), now, zone).songsFor(DnaMoment(23, DayOfWeek.WEDNESDAY), 16).size)
        // Mornings of months ago have faded: the morning goes quiet again.
        val longAgo = (170..171).flatMap { d -> (0..5).map { heard("Eighties", it, at(d, 8)) } }
        assertTrue(MusicDna.build(nights + longAgo, emptySet(), now, zone).songsFor(morning, 16).isEmpty())
    }

    @Test
    fun withFriendsAlongTheSongsHeardWithThemCountAtAnyHour() {
        val listens = (1..10).flatMap { d ->
            listOf(heard("Gaiteros", d % 6, at(d, 22)), heard("Samba", d % 6, at(d, 22), people = listOf("uana")))
        }
        val dna = MusicDna.build(listens, emptySet(), now, zone)
        assertTrue(dna.songsFor(morning, 16).isEmpty())
        val withAna = dna.songsFor(morning.copy(people = setOf("uana")), 16)
        assertEquals(6, withAna.size)
        assertTrue(withAna.all { it.artist == "Samba" })
        // A friend never heard with says nothing: no songs, and the mix is not named after them.
        assertTrue(dna.songsFor(morning.copy(people = setOf("ubia")), 16).isEmpty())
        assertEquals(setOf("uana"), dna.known(setOf("uana", "ubia")))
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
    fun aMomentIsTheClockTheWeekThePlaceAndWhoIsAlong() {
        val saturdayNight = LocalDate.of(2026, 11, 14).atTime(22, 30).toInstant(zone).toEpochMilli()
        val m = DnaMoment.at(saturdayNight, zone, place = 3L, people = setOf("ub", "ua"))
        assertEquals(DayPart.EVENING, m.part)
        assertTrue(m.weekend)
        assertEquals(22, m.hour)
        assertEquals(DayOfWeek.SATURDAY, m.weekday)
        // The mix of the moment is made for three hours at a time.
        assertEquals("7:we:3:ua,ub", m.key)
        assertEquals(m.key, m.copy(hour = 21).key)
        assertNotEquals(m.key, m.copy(hour = 20).key)
        assertEquals(DayPart.NIGHT_LATE, DnaMoment.at(LocalDate.of(2026, 11, 11).atTime(3, 0).toInstant(zone).toEpochMilli(), zone).part)
    }

    @Test
    fun aNightBelongsToTheDayItStartedIn() {
        // Saturday 2 a.m. is still Friday night; at 6 it is Saturday.
        val late = DnaMoment.at(LocalDate.of(2026, 11, 14).atTime(2, 0).toInstant(zone).toEpochMilli(), zone)
        assertEquals(DayOfWeek.FRIDAY, late.weekday)
        assertFalse(late.weekend)
        assertEquals(DayPart.NIGHT_LATE, late.part)
        assertEquals(DayOfWeek.SATURDAY, DnaMoment.at(LocalDate.of(2026, 11, 14).atTime(6, 0).toInstant(zone).toEpochMilli(), zone).weekday)
        // Monday 1 a.m. is Sunday night: the weekend.
        assertTrue(DnaMoment.at(LocalDate.of(2026, 11, 16).atTime(1, 0).toInstant(zone).toEpochMilli(), zone).weekend)
    }

    @Test
    fun theClockIsSmoothWithNoWallBetweenAfternoonAndEvening() {
        // Jazz at the end of the afternoon (17 h), rock late (23 h), pop in the morning.
        val listens = (1..25).flatMap { d ->
            listOf(heard("Jazz", d % 5, at(d, 17)), heard("Rock", d % 5, at(d, 23)), heard("Pop", d % 5, at(d, 9)))
        }
        val dna = MusicDna.build(listens, emptySet(), now, zone)
        fun jazz(hour: Int) = dna.lift("jazz", DnaMoment(hour, DayOfWeek.WEDNESDAY))
        // An hour later is "the evening" by the clock's parts — and still jazz time.
        assertTrue(jazz(18) > 1.5)
        assertEquals(jazz(17), jazz(18), 0.25)
        // It fades with the hours instead of falling at 18 h.
        assertTrue(jazz(18) > jazz(20))
        assertTrue(jazz(20) > jazz(23))
        assertTrue(jazz(23) < 0.8)
        // Around midnight the clock goes on: 1 a.m. is beside 23 h.
        assertTrue(dna.lift("rock", DnaMoment(1, DayOfWeek.WEDNESDAY)) > 1.4)
    }

    @Test
    fun eachDayOfTheWeekHasItsOwnSound() {
        // Forró on Friday nights, rock on every other night.
        val listens = (1..56).flatMap { d ->
            val friday = today.minusDays(d.toLong()).dayOfWeek == DayOfWeek.FRIDAY
            (0..2).map { heard(if (friday) "Forro" else "Rock", (d + it) % 6, at(d, 21)) }
        }
        val dna = MusicDna.build(listens, emptySet(), now, zone)
        val friday = DnaMoment(21, DayOfWeek.FRIDAY)
        val monday = DnaMoment(21, DayOfWeek.MONDAY)
        assertTrue(dna.lift("forro", friday) > 1.6)
        assertTrue(dna.lift("forro", friday) > dna.lift("forro", monday) * 1.5)
        assertTrue(dna.lift("rock", monday) > dna.lift("rock", friday))
        // Friday night goes on after midnight.
        assertTrue(dna.lift("forro", DnaMoment(0, DayOfWeek.FRIDAY)) > dna.lift("forro", DnaMoment(0, DayOfWeek.MONDAY)))
    }

    @Test
    fun aPlaceHasItsRitmoItsSongsAndItsSkips() {
        val home = 1L
        val gym = 2L
        val genre = { a: String, _: String? -> if (a == "metal") "rock" else "mpb" }
        val listens = (1..15).flatMap { d ->
            listOf(
                heard("Djavan", d % 5, at(d, 13), place = home),
                heard("Metal", d % 5, at(d, 13), place = gym),
                // One song of Djavan's heard at home, skipped at the gym.
                heard("Djavan", 9, at(d, 14), place = home),
            )
        } + (1..3).map { skipped("Djavan", 9, at(it, 14)).copy(place = gym) }
        val dna = MusicDna.build(listens, emptySet(), now, zone, genreOf = genre)
        val atHome = DnaMoment(13, DayOfWeek.WEDNESDAY, place = home)
        val atGym = atHome.copy(place = gym)
        // The ritmo.
        assertTrue(dna.genreLift("rock", atGym) > 1.3)
        assertTrue(dna.genreLift("rock", atGym) > dna.genreLift("rock", atHome) * 1.5)
        // The songs.
        assertTrue(dna.songsFor(atGym, 4).all { it.artist == "Metal" })
        assertTrue(dna.songsFor(atHome, 4).all { it.artist == "Djavan" })
        // The skips: what was skipped at the gym says less about home.
        assertTrue(dna.songFactor("Djav9xxxxxx", atGym) < dna.songFactor("Djav9xxxxxx", atHome) - 0.05)
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

    private fun id(artist: String, song: Int) = "${artist.take(4)}$song".padEnd(11, 'x')

    /** A song's sound, from calm and still (0) to intense and moving (1). */
    private fun sound(e: Float) = SoundPrint(-30f + 20f * e, 500f + 3_000f * e, 5f + 40f * e, 0.1f + 0.7f * e, 70f + 90f * e, 120f)

    /** Soft songs in the mornings, hard ones at night; Hush (soft) and Bang (hard) only a couple of afternoons. */
    private fun soundLife(): List<Listen> = (1..20).flatMap { d ->
        listOf(
            heard("Soft", d % 8, at(d, 8)),
            heard("Soft", (d + 3) % 8, at(d, 9)),
            heard("Hard", d % 8, at(d, 21)),
            heard("Hard", (d + 3) % 8, at(d, 22)),
        )
    } + listOf(heard("Hush", 1, at(2, 15)), heard("Hush", 2, at(3, 15)), heard("Bang", 1, at(2, 15)), heard("Bang", 2, at(3, 15)))

    private fun sounds(): Map<String, SoundPrint> =
        (0..7).associate { id("Soft", it) to sound(0.05f + 0.03f * it) } +
            (0..7).associate { id("Hard", it) to sound(0.75f + 0.03f * it) } +
            mapOf(id("Hush", 1) to sound(0.1f), id("Hush", 2) to sound(0.15f), id("Bang", 1) to sound(0.9f), id("Bang", 2) to sound(0.85f))

    @Test
    fun theSoundOfAMomentCarriesToWhatWasNeverHeardInIt() {
        val dna = MusicDna.build(soundLife(), emptySet(), now, zone, sounds = sounds())
        // Hush was never heard in the morning, but it sounds like the mornings.
        assertTrue(dna.soundLift(id("Hush", 1), "hush", morning) > 1.3)
        assertTrue(dna.soundLift(id("Hush", 1), "hush", night) < 0.8)
        assertTrue(dna.soundLift(id("Bang", 1), "bang", night) > 1.3)
        assertTrue(dna.soundLift(id("Bang", 1), "bang", morning) < 0.8)
        // A song not measured yet goes by what its artist usually sounds like.
        assertTrue(dna.soundLift("neverheard1", "hush", morning) > 1.3)
        assertTrue(dna.soundLift(null, "bang", night) > 1.3)
        // Nothing known about the sound: nothing to say.
        assertEquals(1.0, dna.soundLift("neverheard1", "nobody", morning), 0.0)
        // The moment's taste and its own songs follow.
        val taste = TasteProfile.build(soundLife(), emptyList(), now)
        fun hush(m: DnaMoment) = taste.inMoment(dna, m).affinityOf("Hush")
        assertTrue(hush(morning) > hush(night))
    }

    @Test
    fun withoutEnoughSongsMeasuredTheSoundSaysNothing() {
        val few = sounds().entries.take(SoundMap.MIN_SONGS - 1).associate { it.key to it.value }
        val dna = MusicDna.build(soundLife(), emptySet(), now, zone, sounds = few)
        assertEquals(1.0, dna.soundLift(id("Soft", 0), "soft", morning), 0.0)
        assertTrue(dna.soundFits(morning).isEmpty())
        // And the DNA without any sound is the one it was.
        val plain = MusicDna.build(soundLife(), emptySet(), now, zone)
        assertEquals(plain.lift("soft", morning), dna.lift("soft", morning), 0.0)
    }

    @Test
    fun theQuietSongOfALoudBandFitsTheMorning() {
        val quiet = id("Hard", 9)
        val dna = MusicDna.build(soundLife() + heard("Hard", 9, at(2, 15)), emptySet(), now, zone, sounds = sounds() + (quiet to sound(0.08f)))
        assertTrue(dna.soundFits(morning).getValue(quiet) > 1.3)
        assertTrue(dna.soundFits(night).getValue(quiet) < 0.8)
        // A song that sounds like its artist: nothing of its own to say.
        assertFalse(id("Soft", 3) in dna.soundFits(morning))
        val taste = TasteProfile.build(soundLife(), emptyList(), now).inMoment(dna, morning)
        assertTrue(taste.soundFit(quiet) > 1.3)
        assertEquals(1.0, taste.soundFit("neverheard1"), 0.0)
    }

    @Test
    fun aSoundSkippedInTheMorningFitsItLessAndTheNightTheSame() {
        // Soft and hard at any hour; then the hard ones skipped in the mornings, whoever plays them.
        val any = (1..20).flatMap { d ->
            listOf(
                heard("Soft", d % 8, at(d, if (d % 2 == 0) 8 else 21)),
                heard("Hard", d % 8, at(d, if (d % 2 == 0) 9 else 22)),
                heard("Soft", (d + 3) % 8, at(d, if (d % 2 == 0) 22 else 9)),
                heard("Hard", (d + 3) % 8, at(d, if (d % 2 == 0) 21 else 8)),
            )
        }
        val skips = (1..6).flatMap { d -> listOf(skipped("Bang", 1, at(d, 8)), skipped("Bang", 2, at(d, 9))) }
        val plain = MusicDna.build(any, emptySet(), now, zone, sounds = sounds())
        val dna = MusicDna.build(any + skips, emptySet(), now, zone, sounds = sounds())
        val hard = id("Hard", 7)
        assertEquals(1.0, plain.soundLift(hard, "hard", morning), 0.2)
        assertTrue(dna.soundLift(hard, "hard", morning) < plain.soundLift(hard, "hard", morning) - 0.1)
        assertEquals(plain.soundLift(hard, "hard", night), dna.soundLift(hard, "hard", night), 0.05)
        assertEquals(plain.soundLift(id("Soft", 0), "soft", morning), dna.soundLift(id("Soft", 0), "soft", morning), 0.05)
    }
}
