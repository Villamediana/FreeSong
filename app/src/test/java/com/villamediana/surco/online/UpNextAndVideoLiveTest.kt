package com.villamediana.surco.online

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/** "Up next" and "Ver o vídeo" against YouTube for real. Run with `SURCO_LIVE=1`. */
class UpNextAndVideoLiveTest {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val yt by lazy { NewPipeYouTubeSource(http) }

    /** "De Música Ligera (Remasterizado 2007)" — Soda Stereo - Topic (catalog audio, still cover). */
    private val artTrack = "sWft4cMiwXQ"

    @Before fun live() {
        assumeTrue(System.getProperty("surco.live") == "1")
        NewPipeHttp.init(http, Locale("pt", "BR"))
    }

    @Test fun relatedIsYouTubeMusicsRadio(): Unit = runBlocking {
        val related = yt.related(artTrack, 25)
        println("RELATED: " + related.take(8).joinToString { "${it.title} — ${it.artist} [${it.album}] ${it.durationMs}" })
        assertTrue("radio too short: ${related.size}", related.size >= 15)
        assertTrue(related.size <= 25)
        assertTrue(related.none { it.videoId == artTrack })
        assertEquals(related.size, related.map { it.videoId }.toSet().size)
        assertTrue(related.all { it.title.isNotBlank() && it.artist.isNotBlank() && !it.artist.endsWith(" - Topic") })
        assertTrue(related.count { (it.durationMs ?: 0) > 60_000 } >= related.size - 2)
        assertTrue(related.all { it.url.startsWith("https://music.youtube.com/watch?v=") && it.thumbnailUrl != null })
        assertEquals(emptyList<RemoteTrack>(), yt.related("", 10))
        assertEquals(emptyList<RemoteTrack>(), yt.related(artTrack, 0))
    }

    @Test fun relatedNeverThrows(): Unit = runBlocking {
        // A video id that doesn't exist: nothing to continue with, no exception.
        val none = yt.related("zzzzzzzzzzz", 10)
        println("RELATED of a missing video: ${none.size}")
        assertTrue(none.none { it.videoId == "zzzzzzzzzzz" })
    }

    @Test fun videoOfAnArtTrackIsTheOfficialVideo(): Unit = runBlocking {
        val v = yt.videoStream(artTrack, 720)
        println("VIDEO (art track): ${v.width}x${v.height} ${v.videoMimeType} + ${v.audioMimeType} ${v.videoUrl.take(90)}")
        assertTrue(v.height in 144..720)
        assertTrue(v.videoUrl.startsWith("https://"))
        assertTrue(v.audioUrl == null || v.audioUrl!!.startsWith("https://"))
        if (v.audioUrl != null) {
            assertEquals("video/mp4", v.videoMimeType)
            assertEquals("audio/mp4", v.audioMimeType)
        }
        // The art track itself is a still image: a real video is wider than square.
        assertTrue("expected a real video, got ${v.width}x${v.height}", v.width > v.height)
        assertRangeWorks(v.videoUrl)
        v.audioUrl?.let(::assertRangeWorks)
    }

    @Test fun videoOfAVideoIsThatVideo(): Unit = runBlocking {
        // A regular video search result plays as itself, capped at the asked height.
        val song = yt.search("Soda Stereo De Música Ligera video oficial", SearchFilter.VIDEOS).items
            .filterIsInstance<SearchItem.Song>().first().track
        val v = yt.videoStream(song.videoId, 480)
        println("VIDEO (${song.title}): ${v.width}x${v.height} ${v.videoMimeType}")
        assertTrue(v.height in 144..480)
        assertRangeWorks(v.videoUrl)
    }

    /** googlevideo answers a bounded range at once (what the chunked data source relies on). */
    private fun assertRangeWorks(url: String) {
        val request = Request.Builder().url(url).header("User-Agent", NewPipeHttp.USER_AGENT).header("Range", "bytes=0-65535").build()
        http.newCall(request).execute().use { r ->
            assertEquals(206, r.code)
            assertEquals(65_536, r.body.bytes().size)
        }
    }
}
