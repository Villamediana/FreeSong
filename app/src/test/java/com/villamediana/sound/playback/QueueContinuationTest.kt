package com.villamediana.sound.playback

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.smart.neutralTaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueContinuationTest {
    private fun mine(id: Long, video: String? = null, genre: String = "Rock") =
        Track(id = id, title = "Mine $id", artist = "Artist $id", genre = genre, videoId = video, filePath = "/music/$id.m4a", durationMs = 200_000)

    private fun r(v: String) = RemoteTrack(v, "https://music.youtube.com/watch?v=$v", "New $v", "Someone")

    private fun online(v: String) = r(v).asTransientTrack()

    @Test fun seedsAreTheListsLastSongsYouTubeKnowsLatestFirst() {
        val queue = listOf(mine(1, "a"), mine(2, "b"), mine(3), online("c"), mine(4, "b"), mine(5, "d"))
        assertEquals(listOf("d", "b", "c"), QueueContinuation.seeds(queue))
        assertEquals(emptyList<String>(), QueueContinuation.seeds(listOf(mine(1), mine(2))))
        // What was just skipped doesn't lead what comes next (unless everything was skipped).
        assertEquals(listOf("b", "c", "a"), QueueContinuation.seeds(queue, skipped = setOf("d")))
        assertEquals(listOf("d", "b", "c"), QueueContinuation.seeds(queue, skipped = setOf("a", "b", "c", "d")))
    }

    @Test fun aListOfYourSongsStaysMostlyYoursARadioStaysARadio() {
        assertEquals(QueueContinuation.MAX_OWN_SHARE, QueueContinuation.ownShare(List(10) { mine(it + 1L) }), 1e-9)
        assertEquals(0.0, QueueContinuation.ownShare(List(10) { online("v$it") }), 1e-9)
        val half = List(5) { mine(it + 1L) } + List(5) { online("v$it") }
        assertEquals(QueueContinuation.MAX_OWN_SHARE / 2, QueueContinuation.ownShare(half), 1e-9)
    }

    @Test fun aRadioKeepsTheSongItStartedFromAmongItsSeeds() {
        val queue = listOf(online("start"), online("a"), online("b"), online("c"), online("d"), online("e"))
        val radio = PlaySource(PlaySource.Type.RADIO, "Rádio", "start")
        assertEquals("start", QueueContinuation.anchor(radio, queue))
        // Right after what is playing now, however far the list went.
        assertEquals(listOf("e", "start", "d", "c"), QueueContinuation.seeds(queue, anchor = "start"))
        // Already one of them: nothing changes.
        assertEquals(listOf("e", "d", "c"), QueueContinuation.seeds(queue, anchor = "d"))
        // A radio of a downloaded song is found by its library id; any other list has no anchor.
        val mine = listOf(mine(7, "seven"), mine(8, "eight"))
        assertEquals("seven", QueueContinuation.anchor(PlaySource(PlaySource.Type.RADIO, "Rádio", "7"), mine))
        assertEquals(null, QueueContinuation.anchor(PlaySource(PlaySource.Type.ALBUM, "Disco", "start"), queue))
        assertEquals(null, QueueContinuation.anchor(radio, mine))
        assertEquals(null, QueueContinuation.anchor(null, queue))
    }

    @Test fun aRadioFromYouTubeTakesNothingFromTheLibraryByItself() {
        val yours = List(10) { mine(it + 1L) }
        // Even with the list's end all yours (downloads played by the radio).
        assertEquals(0.0, QueueContinuation.ownShare(yours, PlaySource(PlaySource.Type.RADIO, "Rádio", "abcDEF12345")), 1e-9)
        // The library's own radio (started from a song's library id) stays the library's.
        assertEquals(QueueContinuation.MAX_OWN_SHARE, QueueContinuation.ownShare(yours, PlaySource(PlaySource.Type.RADIO, "Rádio", "12")), 1e-9)
        assertEquals(QueueContinuation.MAX_OWN_SHARE, QueueContinuation.ownShare(yours, PlaySource(PlaySource.Type.ALBUM, "Disco", "abc")), 1e-9)
    }

    @Test fun twoOfYoursThenANewOneTakingTurnsAcrossTheRadios() {
        val own = (10L..20L).map { mine(it) }
        val radios = listOf(listOf(r("a1"), r("a2"), r("a3")), listOf(r("b1"), r("b2"), r("b3")))
        val out = QueueContinuation.blend(own, radios, QueueContinuation.MAX_OWN_SHARE, emptySet(), emptySet(), emptyMap(), limit = 9)
        assertEquals(
            listOf("Mine 10", "Mine 11", "New a1", "Mine 12", "Mine 13", "New b1", "Mine 14", "Mine 15", "New a2"),
            out.map { it.title },
        )
    }

    @Test fun nothingQueuedAgainNoRepeatsAndDownloadsPlayFromTheLibrary() {
        val downloaded = mine(30, video = "dl")
        val own = listOf(mine(1), mine(2), downloaded)
        val radios = listOf(listOf(r("q"), r("n1"), r("dl"), r(""), r("n1"), r("n2")))
        val out = QueueContinuation.blend(
            own = own,
            radios = radios,
            ownShare = 0.0,
            queuedIds = setOf(1L),
            queuedVideoIds = setOf("q"),
            byVideoId = mapOf("dl" to downloaded),
            limit = 10,
        )
        // A radio first (share 0); once it runs dry, your songs fill in; nothing twice.
        assertEquals(listOf("New n1", "Mine 30", "New n2", "Mine 2"), out.map { it.title })
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test fun offlineYourSongsAloneAndWithNothingYourOwnTheRadioAlone() {
        val own = listOf(mine(1), mine(2), mine(3))
        assertEquals(own, QueueContinuation.blend(own, emptyList(), QueueContinuation.MAX_OWN_SHARE, emptySet(), emptySet(), emptyMap()))
        val radio = QueueContinuation.blend(emptyList(), listOf(listOf(r("x"), r("y"))), QueueContinuation.MAX_OWN_SHARE, emptySet(), emptySet(), emptyMap())
        assertEquals(listOf("New x", "New y"), radio.map { it.title })
        assertEquals(emptyList<Track>(), QueueContinuation.blend(emptyList(), emptyList(), 0.5, emptySet(), emptySet(), emptyMap()))
    }

    @Test fun withEverySongQueuedYourOnesHeardLongestAgoComeBack() {
        val library = (1L..8L).map { mine(it) }
        val queue = library // all of it played already
        val again = QueueContinuation.again(queue, library, neutralTaste(), seed = 1)
        val recent = queue.takeLast(library.size / 2).map { it.id }.toSet()
        assertTrue(again.isNotEmpty())
        assertTrue(again.none { it.id in recent })
        assertEquals(emptyList<Track>(), QueueContinuation.again(queue, emptyList(), neutralTaste(), seed = 1))
    }
}
