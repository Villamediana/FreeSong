package com.villamediana.sound.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The taste the suggestions come from: learned from everything, steady, and listening to "no". */
class TasteProfileTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3_600_000L

    private fun vid(artist: String, song: Int) = artist.filter(Char::isLetterOrDigit) + "_" + song

    private fun heard(artist: String, song: Int, daysAgo: Double, genre: String? = null, completed: Boolean = true) =
        Listen(vid(artist, song), "$artist song $song", artist, genre, now - (daysAgo * day).toLong(), 200_000, 200_000, completed, skipped = false)

    private fun skipped(artist: String, song: Int, daysAgo: Double) =
        Listen(vid(artist, song), "$artist song $song", artist, null, now - (daysAgo * day).toLong(), 8_000, 200_000, completed = false, skipped = true)

    @Test
    fun anEveningOfSomethingElseNudgesTheTasteItDoesNotTurnItOver() {
        // Weeks of Djavan, across his songs (not in the last few days); tonight, six songs of someone new.
        val listens = (0 until 24).map { heard("Djavan", it % 6, daysAgo = 6.0 + it) } +
            (0 until 6).map { heard("Anitta", it, daysAgo = 0.05) }
        val p = TasteProfile.build(listens, emptyList(), now)
        assertEquals("djavan", p.artists.first().key)
        val anitta = p.artists.first { it.key == "anitta" }
        // The moment shows (she leads "now"), the taste stays.
        assertTrue(anitta.now > p.artists.first().now)
        assertTrue(anitta.affinity < p.artists.first().affinity)
    }

    @Test
    fun oneSongOnRepeatIsNotAWholeArtist() {
        val listens = (0 until 40).map { heard("Repeat", 1, daysAgo = it * 0.5) } +
            (0 until 20).map { heard("Wide", it % 5, daysAgo = it * 1.0) }
        val p = TasteProfile.build(listens, emptyList(), now)
        assertEquals("wide", p.artists.first().key)
    }

    @Test
    fun whatIsSkippedAgainAndAgainIsNotSuggestedAgainButAHeartWins() {
        val listens = listOf(
            skipped("Meh", 1, 6.0), skipped("Meh", 1, 5.0), skipped("Meh", 1, 3.0), skipped("Meh", 1, 2.0), // skipped again and again
            skipped("Maybe", 1, 3.0), skipped("Maybe", 1, 2.0), heard("Maybe", 2, 1.0), // twice: maybe just not the moment
            heard("Meh", 2, 2.0),
            skipped("Nope", 1, 4.0), skipped("Nope", 2, 3.0), skipped("Nope", 3, 2.0), skipped("Nope", 4, 1.0),
            skipped("Nope", 5, 1.0), skipped("Nope", 6, 0.5), // never one of theirs heard through
            skipped("Loved", 1, 2.0), skipped("Loved", 1, 1.0),
        )
        val hearts = listOf(Heart(vid("Loved", 1), "Loved song 1", "Loved", null, now - day))
        val p = TasteProfile.build(listens, hearts, now)
        assertTrue(vid("Meh", 1) in p.avoidSongs)
        assertFalse(vid("Meh", 2) in p.avoidSongs)
        // Skipped twice is not "never again" (Miguel: maybe it just wasn't the vibe).
        assertFalse(vid("Maybe", 1) in p.avoidSongs)
        assertTrue("nope" in p.avoidArtists)
        assertTrue(p.avoids("whatever123", "Nope feat. Someone"))
        assertTrue(p.artists.none { it.key == "nope" })
        // Hearted: never avoided, even skipped.
        assertFalse(vid("Loved", 1) in p.avoidSongs)
        assertTrue(p.affinityOf("Loved") > 0)
    }

    @Test
    fun genresAreLearnedFromStreamingAndThePickedOnesJoin() {
        // Streamed songs carry no genre: the artists' genres say it.
        val listens = (0 until 12).map { heard("Henrique e Juliano", it % 4, daysAgo = it.toDouble()) } +
            (0 until 8).map { heard("Metallica", it % 4, daysAgo = it.toDouble()) }
        val genres = mapOf("henrique e juliano" to "Sertanejo Universitário", "metallica" to "Metal")
        val p = TasteProfile.build(listens, emptyList(), now, artistGenres = genres, picked = setOf("jazz"))
        assertEquals(listOf("sertanejo", "rock", "jazz"), p.genres.map { it.id })
        assertTrue(p.genres.first { it.id == "jazz" }.picked)
        // A song's own genre tag (the library's) wins over the artist's.
        val tagged = TasteProfile.build(listOf(heard("Someone", 1, 1.0, genre = "Pagode")), emptyList(), now, artistGenres = mapOf("someone" to "Rock"))
        assertEquals("pagode", tagged.artists.single().genre)
    }

    @Test
    fun theFavouritesOfTheMomentNeedMoreThanOneLuckyPlay() {
        val listens = (0 until 5).map { heard("Steady", it, daysAgo = it.toDouble()) } + heard("Lucky", 1, daysAgo = 0.01)
        val p = TasteProfile.build(listens, emptyList(), now)
        assertEquals(listOf("steady"), p.favoritesNow(4).map { it.key })
        // A heart is enough evidence on its own.
        val hearted = TasteProfile.build(listens, listOf(Heart(vid("Lucky", 1), "Lucky song 1", "Lucky", null, now)), now)
        assertTrue(hearted.favoritesNow(4).any { it.key == "lucky" })
    }

    @Test
    fun theDaysSeedsTakeTurnsAcrossGenres() {
        val listens = (0 until 9).map { heard("Rock $it", 1, daysAgo = it * 0.1) } + heard("Mpb 1", 1, daysAgo = 2.0) + heard("Mpb 1", 1, daysAgo = 2.1)
        val genres = (0 until 9).associate { "rock $it" to "Rock" } + ("mpb 1" to "MPB")
        val p = TasteProfile.build(listens, emptyList(), now, artistGenres = genres)
        val seeds = p.seeds(4)
        assertEquals(4, seeds.size)
        // MPB gets its turn though rock has many more artists.
        assertTrue(seeds.take(2).any { it.title.startsWith("Mpb") })
    }

    @Test
    fun theSeedsAreNotTheSameFavouritesEveryDay() {
        val listens = (0 until 8).flatMap { a -> (1..3).flatMap { s -> List(4 - s) { heard("Band $a", s, daysAgo = a * 0.3 + it * 0.01) } } }
        val p = TasteProfile.build(listens, emptyList(), now)
        // Without chance: always the same ones, each artist's most heard song.
        assertEquals(p.seeds(4), p.seeds(4))
        assertTrue(p.seeds(4).all { it.videoId!!.endsWith("_1") })
        // With it: the same draw gives the same seeds, other days bring other artists and other songs of theirs.
        assertEquals(p.seeds(4, kotlin.random.Random(5)), p.seeds(4, kotlin.random.Random(5)))
        val days = (1..20).map { d -> p.seeds(4, kotlin.random.Random(d)).map { it.videoId!! } }
        assertTrue(days.all { it.size == 4 && it.map { v -> v.substringBefore('_') }.distinct().size == 4 })
        assertTrue(days.distinct().size >= 10)
        assertTrue(days.flatten().distinct().size >= 12)
        assertTrue(days.flatten().any { !it.endsWith("_1") })
    }

    @Test
    fun aSongHeardIsKnownASongSkippedIsNot() {
        val p = TasteProfile.build(listOf(heard("A", 1, 1.0), skipped("A", 2, 1.0)), emptyList(), now)
        assertTrue(vid("A", 1) in p.known)
        assertFalse(vid("A", 2) in p.known)
    }

    @Test
    fun downloadedSongsCountALittleForSomeoneWhoBarelyListensInTheApp() {
        val saved = (0 until 10).map { Heart(vid("Saved", it), "Saved $it", "Saved Artist", "Rock", now - 30 * day) }
        val p = TasteProfile.build(emptyList(), emptyList(), now, saved = saved)
        assertEquals("saved artist", p.artists.single().key)
        assertEquals("rock", p.genres.single().id)
        // Downloading is not hearing: they are still new, and one download isn't a favourite.
        assertTrue(p.known.isEmpty())
        assertTrue(p.favoritesNow(3).isEmpty())
    }
}
