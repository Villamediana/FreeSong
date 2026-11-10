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
    fun whatSeveralRadiosAgreeOnComesFirstAndTheTasteOnlyNudges() {
        val shared = song("shared", "Shared Artist")
        val a = listOf(song("a1", "A1"), song("a2", "A2"), shared) + (3..20).map { song("a$it", "A$it") }
        val b = listOf(song("b1", "B1"), shared) + (2..20).map { song("b$it", "B$it") }
        fun likes(artist: String) = TasteProfile.build((1..4).map { Listen("x$it", "x", artist, null, now, 200_000, 200_000, true, false) }, emptyList(), now)
        fun top(profile: TasteProfile?, artist: String, first: Int) = (0 until 60).count { i ->
            RadioMix.rank(listOf(a, b), emptyList(), profile, Session(), emptySet(), 15, Random(i)).take(first).any { it.artist == artist }
        }
        val sharedTop = (0 until 30).count { i ->
            RadioMix.rank(listOf(a, b), emptyList(), likes("A6"), Session(), emptySet(), 15, Random(i)).take(5).any { it.videoId == "shared" }
        }
        assertTrue("shared $sharedTop/30", sharedTop >= 24)
        // A favourite artist's song near the seed comes up more often than it would by itself…
        val plain = top(null, "A6", 6)
        val liked = top(likes("A6"), "A6", 6)
        assertTrue("liked $liked plain $plain", liked > plain)
        // …but one at the far end of the radio never jumps over the songs next to the seed: liking
        // an artist is not what makes a song belong in this radio.
        assertEquals(0, top(likes("A20"), "A20", 5))
    }

    @Test
    fun aRadioIsMostlySongsTheListenerDoesNotKnowYet() {
        val list = (1..30).map { song("k$it", "Art $it") }
        // Everything near the seed was already heard to the end; the new songs are further down.
        val profile = TasteProfile.build((1..18).map { Listen("k$it", "Song k$it", "Art $it", null, now, 200_000, 200_000, true, false) }, emptyList(), now)
        repeat(10) { i ->
            val out = RadioMix.rank(listOf(list), emptyList(), profile, Session(), emptySet(), 12, Random(i))
            assertEquals(12, out.size)
            assertEquals(4, out.count { it.videoId in profile.known }) // a third, the rest new
        }
        // With nothing new left, the known ones fill in: the radio doesn't come up short.
        val few = RadioMix.rank(listOf(list.take(18)), emptyList(), profile, Session(), emptySet(), 12, Random(1))
        assertEquals(12, few.size)
    }

    @Test
    fun aSongOnlyARadioOfARadioHasByAnArtistTheSeedDoesNotKnowStaysBehind() {
        val direct = (1..20).map { song("d$it", "Near ${it % 10}") }
        // A radio of one of its songs: half artists the seed's radio has, half strangers, taking turns.
        val wider = (1..20).map { if (it % 2 == 0) song("w$it", "Near ${it % 10}") else song("w$it", "Far $it") }
        var near = 0
        var far = 0
        repeat(30) { i ->
            val out = RadioMix.rank(listOf(direct), listOf(wider), null, Session(), emptySet(), 20, Random(i))
            near += out.count { it.videoId.startsWith("w") && it.artist.startsWith("Near") }
            far += out.count { it.artist.startsWith("Far") }
        }
        assertTrue("near $near far $far", far * 2 < near)
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
