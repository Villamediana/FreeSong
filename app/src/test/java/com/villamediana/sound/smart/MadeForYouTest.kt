package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.RemoteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MadeForYouTest {
    private fun track(id: Long, genre: String?, liked: Boolean = false, plays: Int = 0) = Track(
        id = id, title = "Song $id", artist = "Artist ${id % 4}", genre = genre, filePath = "/x/$id",
        videoId = "v${id.toString().padStart(10, '0')}", liked = liked, playCount = plays,
    )

    private fun rt(id: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", "New $id", "Someone")
    private val labels = MadeForYou.Labels({ "Mix $it" }, "Seu mix", { "$it e mais" })

    @Test fun upToThreeMixesForTheGenresHeardMost() {
        val lib = (1L..12L).map { track(it, "Rock") } + (13L..18L).map { track(it, "MPB") } +
            (19L..21L).map { track(it, "Jazz") } + (22L..23L).map { track(it, "Pop") }
        val plans = MadeForYou.plan(lib, Taste(), day = 100)
        assertEquals(listOf("Rock", "MPB", "Jazz"), plans.map { it.genre })
        assertTrue(plans.all { it.id.startsWith(MadeForYou.PREFIX) })
        assertEquals(12, plans[0].own.size) // every Rock song is a candidate
        assertTrue(plans[0].own.all { it.genre == "Rock" })
        assertEquals(MadeForYou.SEEDS, plans[0].seeds.size)
        // Seeds from different artists first (4 artists in the library, then a repeat).
        assertEquals(4, plans[0].seeds.take(4).map { it.artist }.distinct().size)
        // The same day makes the same mixes; another day, another pick.
        assertEquals(plans, MadeForYou.plan(lib, Taste(), day = 100))
        assertTrue(MadeForYou.plan(lib, Taste(), day = 101)[0].own != plans[0].own)
    }

    @Test fun withoutGenresOneMixOfTheWholeLibrary() {
        val plans = MadeForYou.plan((1L..5L).map { track(it, null) }, Taste(), day = 1)
        assertEquals(1, plans.size)
        assertEquals(null, plans[0].genre)
        assertEquals("Seu mix", MadeForYou.assemble(plans[0], emptyList(), labels).title)
    }

    private fun rt(id: String, artist: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", "New $id", artist)

    @Test fun newSongsAreTheOnesTheRadiosAgreeOnNeverOnesYouHave() {
        val radios = listOf(
            listOf(rt("a1", "Maná"), rt("mine", "Maná"), rt("a2", "Jaguares")),
            listOf(rt("b1", "Soda Stereo"), rt("a1", "Maná"), rt("b2", "Jaguares")),
            listOf(rt("c1", "Bad Bunny")),
        )
        val picked = MadeForYou.pickNew(radios, inLibrary = setOf("mine"), count = 5).map { it.videoId }
        assertTrue("mine" !in picked)
        // In two radios: first. Off on its own (a radio that shares no artist): last.
        assertEquals("a1", picked.first())
        assertEquals("c1", picked.last())
        assertEquals(setOf("a1", "a2", "b1", "b2", "c1"), picked.toSet())
    }

    @Test fun newSongsKeepVarietyAndNeverTheSameSongTwice() {
        val radio = (1..10).map { rt("m$it", "Maná") } + listOf(
            RemoteTrack("x1", "u", "Clavado en un bar", "Maná"),
            RemoteTrack("x2", "u", "Clavado En Un Bar", "Maná"),
            rt("o1", "Other"),
        )
        val picked = MadeForYou.pickNew(listOf(radio), inLibrary = emptySet(), count = 20)
        assertEquals(MadeForYou.PER_ARTIST, picked.count { it.artist == "Maná" })
        assertTrue(picked.any { it.videoId == "o1" })
    }

    @Test fun yourSongsTakeTurnsFromOneDayToTheNext() {
        val lib = (1L..40L).map { track(it, "Rock") }
        val first = MadeForYou.plan(lib, Taste(), day = 100)[0]
        val shown = first.own.take(MadeForYou.OWN).associate { MadeForYou.shownKey(it) to 1.0 }
        // What yesterday's mix had waits behind what it didn't.
        val next = MadeForYou.plan(lib, Taste(), day = 101, seen = shown)[0]
        assertTrue(next.own.take(MadeForYou.OWN).none { MadeForYou.shownKey(it) in shown })
        assertEquals(40, next.own.size)
        // The radios don't start from the same songs every day either.
        val seeds = (100L..120L).map { d -> MadeForYou.plan(lib, Taste(), day = d)[0].seeds.map { it.id }.toSet() }
        assertTrue(seeds.all { it.size == MadeForYou.SEEDS })
        assertTrue(seeds.distinct().size >= 10)
    }

    @Test fun whatIsBeingHeardLeadsTheMixNotTheHearts() {
        // Ten hearts never played lately, twenty songs without one heard these weeks.
        val lib = (1L..10L).map { track(it, "Rock", liked = true) } + (11L..30L).map { track(it, "Rock") }
        val taste = Taste(recentPlays = (11L..30L).associateWith { 3 })
        val liked = (100L..119L).sumOf { d -> MadeForYou.plan(lib, taste, day = d)[0].own.take(MadeForYou.OWN).count { it.liked } }
        // A heart still counts (some get in), it doesn't fill the mix.
        assertTrue("liked $liked", liked in 1..(20 * MadeForYou.OWN / 4))
    }

    @Test fun theNewSongsOfTheLastDaysGiveWayAndChanceMovesTheRest() {
        val radio = (1..20).map { rt("n$it", "Artist $it") }
        val first = MadeForYou.pickNew(listOf(radio), inLibrary = emptySet(), count = 10)
        assertEquals((1..10).map { "n$it" }, first.map { it.videoId })
        // Shown today: the other half of the radio comes first.
        val seen = first.associate { it.videoId to 1.0 }
        val next = MadeForYou.pickNew(listOf(radio), inLibrary = emptySet(), count = 10, seen = seen)
        assertEquals((11..20).map { "n$it" }.toSet(), next.map { it.videoId }.toSet())
        // A little chance: other orders, with the radio's first song always in.
        val draws = (1..30).map { d -> MadeForYou.pickNew(listOf(radio), emptySet(), count = 10, rnd = kotlin.random.Random(d)).map { it.videoId } }
        assertTrue(draws.distinct().size >= 20)
        assertTrue(draws.all { "n1" in it })
        assertEquals(draws[0], MadeForYou.pickNew(listOf(radio), emptySet(), count = 10, rnd = kotlin.random.Random(1)).map { it.videoId })
    }

    @Test fun yourSongsInTheMixStyleMoveUp() {
        val lib = (1L..30L).map { track(it, "Rock") }
        val plan = MadeForYou.plan(lib, Taste(), day = 5)[0]
        val lastArtist = plan.own.last().artist
        val mix = MadeForYou.assemble(plan, listOf(rt("n1", lastArtist)), labels)
        assertEquals(MadeForYou.OWN, mix.tracks.count { !it.isOnline })
        assertTrue(mix.tracks.filter { !it.isOnline }.count { it.artist == lastArtist } >= plan.own.take(MadeForYou.OWN).count { it.artist == lastArtist })
    }

    @Test fun theSameArtistDoesNotPlayTwiceInARowWhenAvoidable() {
        val order = listOf(track(1, "Rock"), track(5, "Rock"), track(2, "Rock"), track(3, "Rock"))
        val apart = MadeForYou.apart(order) // artists 1, 1, 2, 3 → 1, 2, 1, 3
        assertTrue(apart.zipWithNext().none { (a, b) -> a.artist == b.artist })
    }

    @Test fun theMixWeavesYourSongsWithTheNewOnes() {
        val lib = (1L..4L).map { track(it, "Rock") }
        val plan = MadeForYou.plan(lib, Taste(), day = 3)[0]
        val mix = MadeForYou.assemble(plan, listOf(rt("n1"), rt("n2"), rt("n3"), rt("n4")), labels)
        assertEquals("Mix Rock", mix.title)
        assertEquals(8, mix.tracks.size)
        // Yours lead and alternate with the new ones (streamed, transient).
        assertTrue(!mix.tracks[0].isOnline && mix.tracks[1].isOnline)
        assertEquals(4, mix.tracks.count { it.isOnline })
    }
}
