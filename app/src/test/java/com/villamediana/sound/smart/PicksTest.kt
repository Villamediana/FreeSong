package com.villamediana.sound.smart

import com.villamediana.sound.online.RemoteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The last step of every suggested list: variety, new songs ahead of known ones, not the same every day. */
class PicksTest {
    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") =
        RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, artist, durationMs = 200_000)

    @Test
    fun knownSongsTakeOnlyTheirShareWhileThereAreNewOnes() {
        val known = (1..10).map { song("k$it") }
        val new = (1..10).map { song("n$it") }
        // The known ones ranked first, as when the taste pushes them up.
        val out = Picks.cut(known + new, 9, perArtist = 2, known = { it.videoId.startsWith("k") }, knownShare = 1.0 / 3.0)
        assertEquals(9, out.size)
        assertEquals(3, out.count { it.videoId.startsWith("k") })
        assertEquals(listOf("k1", "k2", "k3"), out.take(3).map { it.videoId })
        // Nothing new left: the known ones that waited fill the list.
        val few = Picks.cut(known + new.take(2), 9, perArtist = 2, known = { it.videoId.startsWith("k") }, knownShare = 1.0 / 3.0)
        assertEquals(9, few.size)
        assertEquals(7, few.count { it.videoId.startsWith("k") })
        // No share asked: the order as ranked.
        assertEquals(known.take(9), Picks.cut(known + new, 9, perArtist = 2))
    }

    @Test
    fun aFewPerArtistNoSongTwiceAndNeverTheSameArtistInARow() {
        val ranked = listOf(
            song("a1", "Maná", "Clavado en un bar"),
            song("a2", "Maná", "Clavado En Un Bar (Official Video)"),
            song("a3", "Maná", "Rayando el sol"),
            song("a4", "Maná", "Oye mi amor"),
            song("b1", "Soda Stereo"),
            song("c1", "Caifanes"),
        )
        val out = Picks.cut(ranked, 10, perArtist = 2)
        assertEquals(setOf("a1", "a3", "b1", "c1"), out.map { it.videoId }.toSet())
        assertTrue(out.zipWithNext().none { (a, b) -> a.artist == b.artist })
        assertTrue(Picks.cut(ranked, 0, perArtist = 2).isEmpty())
    }

    @Test
    fun whatWasShownFadesOverTheDaysAndIsForgotten() {
        val shown = mapOf("today" to 100L, "yesterday" to 99L, "old" to 96L, "gone" to 95L)
        val seen = Picks.seen(shown, 100)
        assertEquals(1.0, seen.getValue("today"), 1e-9)
        assertEquals(0.8, seen.getValue("yesterday"), 1e-9)
        assertEquals(0.2, seen.getValue("old"), 1e-9)
        assertTrue("gone" !in seen)
        // For what stays the same all day: today's own showing doesn't count against it.
        assertEquals(setOf("yesterday", "old"), Picks.seen(shown, 100, today = false).keys)
        // A song shown counts less, never nothing.
        assertEquals(1.0, Picks.fresh(null), 1e-9)
        assertEquals(1.0 - Picks.SEEN, Picks.fresh(1.0), 1e-9)
        assertTrue(Picks.fresh(0.2) > Picks.fresh(0.8))
        // Marking: today's date on the new ones, the too old dropped, the rest kept.
        val marked = Picks.mark(shown, listOf("yesterday", "new", ""), 100)
        assertEquals(mapOf("today" to 100L, "yesterday" to 100L, "old" to 96L, "new" to 100L), marked)
    }

    @Test
    fun aDrawFavoursTheFirstAndWhatWasNotShownLately() {
        val ranked = (1..30).map { "s$it" }
        val runs = 400
        val first = IntArray(2)
        var shownIn = 0
        var plainIn = 0
        repeat(runs) { r ->
            val plain = Picks.draw(ranked, 10, Random(r), { it })
            assertEquals(10, plain.distinct().size)
            if ("s1" in plain) first[0]++
            if ("s30" in plain) first[1]++
            if ("s2" in plain) plainIn++
            if ("s2" in Picks.draw(ranked, 10, Random(r), { it }) { if (it == "s2") 1.0 else null }) shownIn++
        }
        assertTrue("first ${first[0]} last ${first[1]}", first[0] > first[1] * 2)
        assertTrue("plain $plainIn shown $shownIn", shownIn * 2 < plainIn)
        // The same draw, the same list; another one, another list; few songs come all, as ranked.
        assertEquals(Picks.draw(ranked, 10, Random(7), { it }), Picks.draw(ranked, 10, Random(7), { it }))
        assertTrue(Picks.draw(ranked, 10, Random(7), { it }) != Picks.draw(ranked, 10, Random(8), { it }))
        assertEquals(ranked.take(6), Picks.draw(ranked.take(6), 10, Random(1), { it }))
    }
}
