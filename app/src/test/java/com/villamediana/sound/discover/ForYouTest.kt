package com.villamediana.sound.discover

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.online.AudioStream
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.villamediana.sound.smart.SoundPrint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun remote(id: String, artist: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", "Song $id", artist, durationMs = 200_000)

/** The Home's suggestions from a real listening history (database) and a fake YouTube. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ForYouTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Each song's radio: other songs of its artist and of "neighbours" (the same for every seed of a style). */
    private class FakeYouTube : YouTubeSource {
        var radios = 0
        override suspend fun related(videoId: String, limit: Int): List<RemoteTrack> {
            radios++
            val style = videoId.substringBefore('-')
            return (1..limit).map { i -> remote("$style-new$i", "$style Neighbour ${i % 7}") }
        }
        override suspend fun search(query: String, filter: SearchFilter): SearchPage =
            SearchPage(query, filter, (1..20).map { SearchItem.Song(remote("g-${query.hashCode()}-$it", "Genre Artist $it")) })
        override suspend fun more(page: SearchPage) = page
        override suspend fun suggestions(query: String) = emptyList<String>()
        override suspend fun resolve(text: String) = ResolvedLink.Unsupported
        override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = throw UnsupportedOperationException()
        override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream = throw UnsupportedOperationException()
        override suspend fun track(videoId: String): RemoteTrack = throw UnsupportedOperationException()
    }

    private lateinit var db: SoundDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private val youTube = FakeYouTube()
    private var now = 1_800_000_000_000L
    private lateinit var forYou: ForYou
    private lateinit var discover: DiscoverRepository

    @Before fun setUp() {
        db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        discover = DiscoverRepository(youTube, tmp.root.resolve("d.json"), clock = { now }, session = 1)
        // The catalog is offline: the genres come from what is already known.
        val offline = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        val genres = ArtistGenres(offline, tmp.root.resolve("g.json"), clock = { now })
        tmp.root.resolve("g.json").writeText("""{"samba":{"genre":"Samba","at":$now},"rockband":{"genre":"Rock","at":$now}}""")
        forYou = ForYou(library, discover, genres, picked = { emptySet() }, scope = scope, clock = { now })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun stream(id: String, artist: String, times: Int = 1, skipped: Boolean = false, hoursAgo: Long = 24) {
        repeat(times) { i ->
            library.recordOnlinePlay(remote(id, artist), if (skipped) 5_000 else 200_000, 200_000, "test", now - (hoursAgo + i) * 3_600_000L)
        }
    }

    @Test
    fun theLibrarysSongsOfThisHourFitNowAndTheOnesOfAnotherHourFitLess() = runBlocking {
        fun own(n: Int, artist: String) = com.villamediana.sound.data.db.Track(
            title = "Own $n", artist = artist, videoId = "own$n", filePath = "/music/$n.m4a", durationMs = 200_000,
        )
        // Nothing heard yet: the moment has nothing to say about the library.
        val nowBand = library.insertTrack(own(1, "Now Band"))
        val thenBand = library.insertTrack(own(2, "Then Band"))
        val stranger = library.insertTrack(own(3, "Stranger"))
        assertTrue(forYou.libraryFit().isEmpty())
        // Two weeks of one band at this hour and another half a day away.
        for (day in 1..14) {
            library.recordOnlinePlay(remote("n${day % 5}", "Now Band"), 200_000, 200_000, "test", now - day * 24 * 3_600_000L)
            library.recordOnlinePlay(remote("t${day % 5}", "Then Band"), 200_000, 200_000, "test", now - day * 24 * 3_600_000L - 12 * 3_600_000L)
        }
        forYou.invalidate()
        val fit = forYou.libraryFit()
        assertTrue(fit.getValue(nowBand) > 1.3)
        assertTrue(fit.getValue(thenBand) < 0.8)
        // Never heard: as always (not even listed).
        assertFalse(stranger in fit)
    }

    @Test
    fun whatSongsSoundLikeIsKeptOnThePhoneAndReachesTheDna() = runBlocking {
        fun sound(e: Float, seconds: Float = 120f) = SoundPrint(-30f + 20f * e, 500f + 3_000f * e, 5f + 40f * e, 0.1f + 0.7f * e, 70f + 90f * e, seconds)
        // Soft songs at this hour of the day, hard ones half a day away, for a week.
        for (i in 0 until 7) {
            library.learnSound("soft-$i", sound(0.05f + 0.03f * i))
            library.learnSound("hard-$i", sound(0.75f + 0.03f * i))
            for (day in 1..3) {
                library.recordOnlinePlay(remote("soft-$i", "Soft Band"), 200_000, 200_000, "test", now - day * 24 * 3_600_000L)
                library.recordOnlinePlay(remote("hard-$i", "Hard Band"), 200_000, 200_000, "test", now - day * 24 * 3_600_000L - 12 * 3_600_000L)
            }
        }
        assertEquals(14, library.sounds().size)
        // Heard again: the two measures join, each by how much it heard.
        library.learnSound("soft-0", sound(0.05f, seconds = 40f).copy(loud = -21f))
        val again = library.soundOf("soft-0")!!
        assertEquals(160f, again.seconds, 0.01f)
        assertEquals(-27f, again.loud, 0.01f)
        assertTrue(again.settled)

        val dna = forYou.dna()
        val m = forYou.moment()
        assertTrue(dna.soundLift("soft-1", "soft band", m) > 1.2)
        assertTrue(dna.soundLift("hard-1", "hard band", m) < 0.9)
    }

    @Test
    fun theDaysDiscoveriesComeFromWhatIsLovedAndStayAllDay() = runBlocking {
        stream("samba-1", "Samba", times = 4)
        stream("samba-2", "Samba", times = 3)
        stream("rock-1", "RockBand", times = 2)
        // A song of the radios, already heard: not a discovery.
        stream("samba-new1", "samba Neighbour 1", times = 2)
        val first = forYou.discoveries()
        assertTrue(first.size >= 8)
        assertFalse(first.any { it.videoId == "samba-new1" || it.videoId.startsWith("samba-") && !it.videoId.contains("new") })
        // Both of the listener's styles are in it.
        assertTrue(first.any { it.videoId.startsWith("samba-") } && first.any { it.videoId.startsWith("rock-") })
        // Hours later, after other listening, the same row (made once a day).
        now += 3 * 3_600_000L
        stream("rock-2", "RockBand", times = 3, hoursAgo = 0)
        forYou.invalidate()
        val asked = youTube.radios
        assertEquals(first.map { it.videoId }, forYou.discoveries().map { it.videoId })
        assertEquals(asked, youTube.radios)
    }

    @Test
    fun becauseYouLikeStaysThroughTheSlotEvenAsOtherSongsPlay() = runBlocking {
        stream("samba-1", "Samba", times = 5)
        stream("samba-2", "Samba", times = 4)
        stream("rock-1", "RockBand", times = 2)
        stream("rock-2", "RockBand", times = 2)
        val rows = forYou.because()
        assertEquals("Samba", rows.first().artist)
        // A binge of something else right now (enough to make it the favourite of the moment):
        // the rows of this slot don't move; the next slot follows the new taste.
        stream("rock-3", "RockBand", times = 8, hoursAgo = 0)
        forYou.invalidate()
        assertEquals(rows.map { it.artist to it.tracks.map { t -> t.videoId } }, forYou.because().map { it.artist to it.tracks.map { t -> t.videoId } })
        now += 6 * 3_600_000L
        forYou.invalidate()
        assertEquals("RockBand", forYou.because().first().artist)
    }

    @Test
    fun becauseYouLikeIsOneRowOfFourArtistsTheTwoFavouritesAlwaysOnIt() = runBlocking {
        // Six artists loved, in order: the first two always have a card, two of the others take turns.
        listOf("Samba" to 9, "RockBand" to 8, "Jazzy" to 6, "Folky" to 5, "Bluesy" to 4, "Poppy" to 3).forEach { (artist, n) ->
            val style = artist.lowercase()
            stream("$style-1", artist, times = n)
            stream("$style-2", artist, times = n - 1)
        }
        val first = forYou.because().map { it.artist }
        assertEquals(4, first.size)
        assertEquals(setOf("Samba", "RockBand"), first.take(2).toSet())
        // Each card is a mix: long enough to be one.
        assertTrue(forYou.because().all { it.tracks.size >= 12 })
        now += 6 * 3_600_000L
        forYou.invalidate()
        val next = forYou.because().map { it.artist }
        assertEquals(setOf("Samba", "RockBand"), next.take(2).toSet())
        assertTrue("the others take turns", next.drop(2) != first.drop(2))
    }

    @Test
    fun aSongSkippedAgainAndAgainLeavesTheRowsAndTheGenresAreLearned() = runBlocking {
        stream("samba-1", "Samba", times = 5)
        stream("samba-2", "Samba", times = 3)
        val first = forYou.discoveries()
        val victim = first.first()
        // Skipped just now: out of the row for now…
        stream(victim.videoId, victim.artist, times = 2, skipped = true, hoursAgo = 0)
        forYou.invalidate()
        assertFalse(forYou.discoveries().any { it.videoId == victim.videoId })
        // Samba streamed (no genre on the songs): its shelf comes first, though nothing was picked.
        assertEquals("pagode", forYou.genres(emptyList()).first().id)
        // A genre picked and not listened to still gets its shelf, after the listened one.
        assertEquals(listOf("pagode", "jazz"), forYou.genres(listOf("jazz")).map { it.id })
    }

    @Test
    fun aShelfLeansToTheListenersCornerOfTheGenre() = runBlocking {
        stream("samba-1", "Samba", times = 5)
        val pagode = GenreCatalog.byId("pagode")!!
        val shelf = forYou.shelf(pagode)
        assertEquals(30, shelf.size)
        // Half from the radio of their own samba, half from the genre's live pool, woven.
        assertTrue(shelf.count { it.videoId.startsWith("samba-new") } >= 6)
        assertTrue(shelf.count { it.videoId.startsWith("g-") } >= 6)
        // Someone who never heard the genre: the pool alone.
        val rock = forYou.shelf(GenreCatalog.byId("jazz")!!)
        assertTrue(rock.all { it.videoId.startsWith("g-") })
    }
}
