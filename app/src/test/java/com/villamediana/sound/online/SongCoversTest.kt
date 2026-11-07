package com.villamediana.sound.online

import com.villamediana.sound.ui.search.FakeYouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/** Album covers for video uploads: the catalog's square art when the result is the very song. */
class SongCoversTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun song(id: String, title: String, artist: String, thumb: String) =
        SearchItem.Song(RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, artist, thumbnailUrl = thumb, fromMusicCatalog = true))

    @Test fun sameSongIgnoresCaseAccentsFeatAndTheArticle() {
        assertTrue(CoverMatch.same("Iris", "Goo Goo Dolls", "Iris", "The Goo Goo Dolls"))
        assertTrue(CoverMatch.same("Canción Animal", "Soda Stereo", "Cancion Animal", "Soda Stereo"))
        assertTrue(CoverMatch.same("Dai Dai", "Shakira", "Dai Dai (feat. Burna Boy)", "Shakira & Burna Boy"))
        assertFalse(CoverMatch.same("Perfect", "Simple Plan", "Perfect", "Ed Sheeran"))
        assertFalse(CoverMatch.same("In The End", "Linkin Park", "Numb", "Linkin Park"))
    }

    @Test fun aVideoUploadGetsTheCatalogCoverAndItIsKeptOnDisk() = runBlocking {
        val yt = FakeYouTube().apply {
            onSearch = { q, f ->
                SearchPage(q, f, listOf(song("x1", "Numb", "Linkin Park", "https://lh3/numb"), song("x2", "In the End", "Linkin Park", "https://lh3/in-the-end")))
            }
        }
        val file = tmp.root.resolve("covers.json")
        val covers = SongCovers({ yt }, file, scope)
        covers.request("vid1", "In The End", "Linkin Park")
        withTimeout(5_000) { covers.found.first { it.containsKey("vid1") } }
        assertEquals("https://lh3/in-the-end", covers.coverFor("vid1"))
        assertEquals(listOf("Linkin Park In The End" to SearchFilter.SONGS), yt.searches.toList())

        // Asked once: no second search, and a new instance reads it from disk.
        covers.request("vid1", "In The End", "Linkin Park")
        Thread.sleep(1_800)
        assertEquals(1, yt.searches.size)
        assertEquals("https://lh3/in-the-end", SongCovers({ yt }, file, scope).coverFor("vid1"))
    }

    @Test fun noMatchingResultKeepsTheVideoFrame() = runBlocking {
        val yt = FakeYouTube().apply { onSearch = { q, f -> SearchPage(q, f, listOf(song("y", "Perfect", "Ed Sheeran", "https://lh3/perfect"))) } }
        val covers = SongCovers({ yt }, tmp.root.resolve("c.json"), scope)
        covers.request("vid2", "Perfect", "Simple Plan")
        withTimeout(5_000) { covers.found.first { it.containsKey("vid2") } }
        assertEquals(null, covers.coverFor("vid2"))
    }

    /** Live (SOUND_LIVE=1): a real video upload gets square album art from YouTube Music. */
    @Test fun liveLookup() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1")
        NewPipeHttp.init(OkHttpClient(), Locale.forLanguageTag("pt-BR"))
        val covers = SongCovers({ NewPipeYouTubeSource(OkHttpClient()) }, tmp.root.resolve("live.json"), scope)
        covers.request("eVTXPUF4Oz4", "In The End", "Linkin Park")
        withTimeout(30_000) { covers.found.first { it.containsKey("eVTXPUF4Oz4") } }
        val art = covers.coverFor("eVTXPUF4Oz4")
        println("LIVE cover: $art")
        assertTrue(art != null && art.contains("googleusercontent"))
    }
}
