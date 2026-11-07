package com.villamediana.sound.smart

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.discover.DiscoverRepository
import com.villamediana.sound.online.AudioStream
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The made-for-you mixes of someone just starting: they come as the first songs do, not only the next day. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MadeForYouStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Radio : YouTubeSource {
        var offline = false
        var asked = 0
        override suspend fun related(videoId: String, limit: Int): List<RemoteTrack> {
            asked++
            if (offline) throw IOException("offline")
            return (1..limit).map { RemoteTrack("$videoId-r$it", "https://music.youtube.com/watch?v=$videoId-r$it", "New $it", "Someone", durationMs = 200_000) }
        }
        override suspend fun search(query: String, filter: SearchFilter): SearchPage = SearchPage(query, filter, emptyList())
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
    private val radio = Radio()
    private lateinit var store: MadeForYouStore

    @Before fun setUp() {
        db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        val discover = DiscoverRepository(radio, tmp.root.resolve("d.json"))
        val labels = MadeForYou.Labels(title = { "Mix $it" }, untitled = "Seu mix", subtitle = { it })
        store = MadeForYouStore(library, discover, { labels }, scope, clock = { 1_773_144_000_000 })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun song(n: Int) = Track(title = "Song $n", artist = "Artist $n", genre = "Rock", videoId = "v$n", filePath = "/music/$n.m4a", durationMs = 200_000)

    /** Refreshes and waits until the mixes match [done]. */
    private fun refreshUntil(done: (List<Mix>) -> Boolean): List<Mix> = runBlocking {
        store.refresh()
        withTimeout(5_000) {
            while (!done(store.mixes.value)) delay(20)
        }
        store.mixes.value
    }

    @Test fun theFirstSongsBringTheMixTheSameDay() {
        refreshUntil { true }
        runBlocking { delay(200) }
        assertEquals(emptyList<Mix>(), store.mixes.value)

        runBlocking { library.insertTrack(song(1)) }
        val one = refreshUntil { mixes -> mixes.size == 1 && mixes[0].tracks.size > 1 }
        assertEquals("Mix Rock", one[0].title)

        // A young mix grows with the library through the day.
        runBlocking { (2..4).forEach { library.insertTrack(song(it)) } }
        val grown = refreshUntil { mixes -> mixes.singleOrNull()?.tracks?.count { it.id > 0 } == 4 }
        assertTrue(grown[0].tracks.any { it.title == "New 1" })
    }

    @Test fun newSongsThatFailedAreAskedAgainAndAFullMixStaysAsItIs() {
        runBlocking { library.insertTrack(song(1)) }
        radio.offline = true
        val own = refreshUntil { mixes -> mixes.size == 1 }
        assertEquals(1, own[0].tracks.size)
        runBlocking { delay(200) }

        radio.offline = false
        val full = refreshUntil { mixes -> mixes.singleOrNull()?.tracks?.size?.let { it > 1 } == true }
        val asked = radio.asked
        store.refresh()
        runBlocking { delay(300) }
        assertEquals(asked, radio.asked) // nothing changed: the day's mix stays, no network
        assertEquals(full, store.mixes.value)
    }
}
