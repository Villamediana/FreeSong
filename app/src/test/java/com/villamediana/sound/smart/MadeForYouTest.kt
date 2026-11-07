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
        assertEquals(MadeForYou.OWN, plans[0].own.size)
        assertTrue(plans[0].own.all { it.genre == "Rock" })
        assertEquals(MadeForYou.SEEDS, plans[0].seeds.size)
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

    @Test fun newSongsComeInTurnsFromEachRadioNeverOnesYouHave() {
        val radios = listOf(listOf(rt("a1"), rt("mine"), rt("a2")), listOf(rt("b1"), rt("a1"), rt("b2")), listOf(rt("c1")))
        val picked = MadeForYou.pickNew(radios, inLibrary = setOf("mine"), count = 5)
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), picked.map { it.videoId })
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
