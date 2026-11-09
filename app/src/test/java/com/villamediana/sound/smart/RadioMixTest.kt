package com.villamediana.sound.smart

import com.villamediana.sound.discover.SongRadio
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The radios and the queue's "a seguir": like what is being heard, and never the same twice. */
class RadioMixTest {
    private val now = 1_800_000_000_000L
    private fun song(id: String, artist: String = "Artist ${id.hashCode().mod(40)}") =
        RemoteTrack(id, "https://music.youtube.com/watch?v=$id", "Song $id", artist, durationMs = 200_000)

    /** YouTube's up-next of a song: the same list every time (that's why the radio repeated). */
    private fun upNext(of: String, size: Int) = (1..size).map { song("$of-$it", "Artist ${(of.hashCode() + it).mod(25)}") }

    private val radio = SongRadio(
        related = { id, size -> upNext(id, size) },
        profile = { null },
        session = { Session() },
    )

    @Test
    fun theSameSongNeverStartsTheSameRadioTwiceButItStaysCloseToIt() = runBlocking {
        val starts = (1..5).map { radio.songs(listOf("seed"), emptySet(), 20, Random(it)) }
        // Different every time…
        assertEquals(5, starts.map { it.map { t -> t.videoId } }.toSet().size)
        assertNotEquals(starts[0].take(5).map { it.videoId }, starts[1].take(5).map { it.videoId })
        // …but most of it comes straight from the song's own radio (not off on a tangent).
        starts.forEach { list -> assertTrue(list.count { it.videoId.startsWith("seed-") } >= 12) }
        // The same draw is the same radio.
        assertEquals(radio.songs(listOf("seed"), emptySet(), 20, Random(9)), radio.songs(listOf("seed"), emptySet(), 20, Random(9)))
    }

    @Test
    fun whatSeveralRadiosAgreeOnAndTheListenersArtistsComeFirst() {
        val shared = song("shared", "Shared Artist")
        val a = listOf(song("a1", "A1"), song("a2", "A2"), shared) + (3..20).map { song("a$it", "A$it") }
        val b = listOf(song("b1", "B1"), shared) + (2..20).map { song("b$it", "B$it") }
        val liked = TasteProfile.build((1..4).map { Listen("x$it", "x", "B9", null, now, 200_000, 200_000, true, false) }, emptyList(), now)
        var sharedTop = 0
        var likedTop = 0
        repeat(30) { i ->
            val out = RadioMix.rank(listOf(a, b), emptyList(), liked, Session(), emptySet(), 15, Random(i))
            if (out.take(5).any { it.videoId == "shared" }) sharedTop++
            if (out.take(8).any { it.artist == "B9" }) likedTop++
        }
        assertTrue("shared $sharedTop/30", sharedTop >= 24)
        assertTrue("liked $likedTop/30", likedTop >= 20)
    }

    @Test
    fun theSessionLeadsJustHeardOrSkippedSongsAndTwiceSkippedArtistsStayOut() {
        val list = (1..20).map { song("s$it", "Art ${it % 10}") }
        val session = Session.of(
            listOf(
                Listen("s1", "", "Art 1", null, now, 200_000, 200_000, true, false), // heard just now
                Listen("s2", "", "Art 2", null, now, 5_000, 200_000, false, true), // skipped
                Listen("x1", "", "Art 3", null, now, 5_000, 200_000, false, true),
                Listen("x2", "", "Art 3", null, now, 5_000, 200_000, false, true), // Art 3 skipped twice
            ),
        )
        val out = RadioMix.rank(listOf(list), emptyList(), null, session, setOf("s20"), 20, Random(1))
        val ids = out.map { it.videoId }
        assertFalse("s1" in ids)
        assertFalse("s2" in ids)
        assertFalse("s20" in ids)
        assertFalse(out.any { it.artist == "Art 3" })
        // Art 2 was skipped once: still there, just further down.
        assertTrue(out.any { it.artist == "Art 2" })
        assertTrue(out.zipWithNext().none { (x, y) -> x.artist == y.artist })
    }

    @Test
    fun aSongSkippedButThenHeardThroughIsNotSkipped() {
        val s = Session.of(
            listOf(
                Listen("v", "", "A", null, now - 1, 5_000, 200_000, false, true),
                Listen("v", "", "A", null, now, 200_000, 200_000, true, false),
            ),
        )
        assertFalse("v" in s.skipped)
        assertTrue(s.skippedArtists.isEmpty())
    }
}
