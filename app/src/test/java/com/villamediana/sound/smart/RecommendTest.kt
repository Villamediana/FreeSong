package com.villamediana.sound.smart

import com.villamediana.sound.discover.GenreCatalog
import com.villamediana.sound.online.RemoteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendTest {
    private val now = 1_800_000_000_000L
    private fun song(id: String, artist: String, title: String = "Song $id") =
        RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, artist, durationMs = 200_000)

    private fun listen(id: String, artist: String, completed: Boolean = true, skipped: Boolean = false, daysAgo: Int = 1) =
        Listen(id, "Song $id", artist, null, now - daysAgo * 86_400_000L, if (skipped) 5_000 else 200_000, 200_000, completed, skipped)

    @Test
    fun whatTheRadiosAgreeOnAndTheLikedArtistsComeFirstNothingSkippedOrKnown() {
        val profile = TasteProfile.build(
            listOf(
                listen("seed1", "Djavan"), listen("seed1", "Djavan", daysAgo = 2), listen("known", "Caetano"),
                listen("bad", "Gil", completed = false, skipped = true), listen("bad", "Gil", completed = false, skipped = true, daysAgo = 2),
            ),
            emptyList(),
            now,
        )
        val radioA = listOf(song("both", "Marisa"), song("onlyA", "Zeca"), song("known", "Caetano"), song("bad", "Gil"), song("caet2", "Caetano"))
        val radioB = listOf(song("onlyB", "Nobody"), song("both", "Marisa"), song("dj2", "Djavan"))
        val picks = Recommend.fromRadios(listOf(radioA, radioB), profile, exclude = setOf("seed1"), count = 10)
        val ids = picks.map { it.videoId }
        // In both radios: first. A song of a liked artist rises past ones only one radio has.
        assertEquals("both", ids.first())
        assertTrue(ids.indexOf("dj2") < ids.indexOf("onlyB"))
        // Never: a song already heard to the end, the seed itself.
        assertFalse("known" in ids)
        assertFalse("seed1" in ids)
        // Skipped twice: still suggested (a skip is the moment, not the taste).
        assertTrue("bad" in ids)
        // A known artist's song that is new to the listener is fine.
        assertTrue("caet2" in ids)
    }

    @Test
    fun eachArtistAFewTimesAtMostAndNeverTwiceInARow() {
        val radio = (1..10).map { song("m$it", "Same") } + (1..4).map { song("o$it", "Other $it") }
        val picks = Recommend.fromRadios(listOf(radio), TasteProfile.build(emptyList(), emptyList(), now), emptySet(), count = 8, perArtist = 2)
        assertEquals(2, picks.count { it.artist == "Same" })
        assertTrue(picks.zipWithNext().none { (a, b) -> a.artist == b.artist })
        // Another upload of the same song is the same song.
        val dupes = listOf(song("x1", "A", "Hello"), song("x2", "A", "Hello (Official Video)"), song("x3", "B"))
        assertEquals(2, Recommend.fromRadios(listOf(dupes), TasteProfile.build(emptyList(), emptyList(), now), emptySet(), 5).size)
    }

    @Test
    fun aShelfLeansToTheListenersArtistsAndLeavesTheSkippedOut() {
        val profile = TasteProfile.build(
            (1..6).map { listen("fav$it", "Favorite") } + (1..6).map { listen("n$it", "Never", completed = false, skipped = true) },
            emptyList(),
            now,
        )
        val pool = (1..30).map { song("g$it", "Genre Artist $it") } + song("newfav", "Favorite") + song("nv", "Never")
        var favTop = 0
        repeat(40) { seed ->
            val shelf = Recommend.order(pool, profile, seed.toLong(), count = 16)
            assertFalse(shelf.any { it.artist == "Never" })
            if (shelf.take(8).any { it.videoId == "newfav" }) favTop++
        }
        // Not always (each session is another draw), but the liked artist is usually up front.
        assertTrue("favorite in the first half $favTop/40", favTop >= 18)
        // The same session draws the same shelf.
        assertEquals(Recommend.order(pool, profile, 7, 16), Recommend.order(pool, profile, 7, 16))
    }

    @Test
    fun genreNamesBecomeTheHomesGenres() {
        val cases = mapOf(
            "Sertanejo Universitário" to "sertanejo",
            "Música Sertaneja" to "sertanejo",
            "Hip-Hop/Rap" to "hiphop",
            "Pop Latino" to "latin",
            "Urbano latino" to "reggaeton",
            "Reggaeton" to "reggaeton",
            "Funk Carioca" to "funk",
            "MPB" to "mpb",
            "Bossa Nova" to "mpb",
            "Samba" to "pagode",
            "Pagode" to "pagode",
            "R&B/Soul" to "rnb",
            "Pop Rock" to "rock",
            "Hard Rock" to "rock",
            "Alternativo" to "indie",
            "Dance" to "electronic",
            "K-Pop" to "kpop",
            "Música Cristã" to "gospel",
            "Christian & Gospel" to "gospel",
            "Jazz" to "jazz",
            "Trilha Sonora" to "classical",
            "Salsa y Tropical" to "salsa",
            "Pop" to "pop",
        )
        cases.forEach { (name, id) -> assertEquals(name, id, GenreMatch.idOf(name)) }
        assertNull(GenreMatch.idOf("Country"))
        assertNull(GenreMatch.idOf(null))
        // Every answer is a genre the Home has a shelf for.
        val home = GenreCatalog.all.map { it.id }.toSet()
        assertTrue(cases.values.all { it in home })
    }
}
