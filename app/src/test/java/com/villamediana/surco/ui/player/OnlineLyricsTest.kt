package com.villamediana.surco.ui.player

import com.villamediana.surco.data.asTransientTrack
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.online.Lyrics
import com.villamediana.surco.online.RemoteTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the player's lyrics come from, and the in-memory cache of streamed songs' lyrics. */
class OnlineLyricsTest {
    private val remote = RemoteTrack("abc", "https://music.youtube.com/watch?v=abc", "Té para Tres", "Soda Stereo", "Canción Animal", 140_000, fromMusicCatalog = true)
    private val streamed = remote.asTransientTrack()
    private val query = LyricsQuery("Té para Tres", "Soda Stereo", "Canción Animal", 140_000)

    @Test fun `a song on the phone reads its file or has none`() {
        val local = Track(id = 3, videoId = "abc", title = "T", artist = "A", filePath = "/a.m4a", lyricsPath = "/lyrics/3.lrc")
        assertEquals(LyricsOrigin.File("/lyrics/3.lrc"), LyricsOrigin.choose(local, local))
        // Before the live row arrives, the player's own copy has the path too.
        assertEquals(LyricsOrigin.File("/lyrics/3.lrc"), LyricsOrigin.choose(local, null))
        assertEquals(LyricsOrigin.None, LyricsOrigin.choose(local.copy(lyricsPath = null), local.copy(lyricsPath = null)))
        assertEquals(LyricsOrigin.None, LyricsOrigin.choose(local.copy(lyricsPath = " "), null))
    }

    @Test fun `a streamed song asks lrclib with what it knows`() {
        assertEquals(LyricsOrigin.Online(query), LyricsOrigin.choose(streamed, null))
        val noAlbum = remote.copy(album = "", durationMs = null).asTransientTrack()
        assertEquals(LyricsOrigin.Online(LyricsQuery("Té para Tres", "Soda Stereo", null, null)), LyricsOrigin.choose(noAlbum, null))
    }

    @Test fun `once downloaded the saved file wins, otherwise it keeps the online lyrics`() {
        val copy = Track(id = 9, videoId = "abc", title = "Té para Tres", artist = "Soda Stereo", filePath = "/abc.m4a", lyricsPath = "/lyrics/9.lrc")
        assertEquals(LyricsOrigin.File("/lyrics/9.lrc"), LyricsOrigin.choose(streamed, copy))
        assertEquals(LyricsOrigin.Online(query), LyricsOrigin.choose(streamed, copy.copy(lyricsPath = null)))
    }

    @Test fun `the same question is not asked twice`() = runBlocking {
        var calls = 0
        val cache = OnlineLyricsCache({ q ->
            calls++
            assertEquals(query, q)
            Lyrics("[00:01.00]Uno\n[00:05.50]Dos", synced = true)
        })
        assertNull(cache.peek(query))
        val first = cache.get(query)!!
        assertTrue(first.synced)
        assertEquals(listOf("Uno", "Dos"), first.lines.map { it.text })
        assertSame(first, cache.get(query))
        assertSame(first, cache.peek(query))
        assertEquals(1, calls)
    }

    @Test fun `not found is remembered for a while only`() = runBlocking {
        var now = 0L
        var calls = 0
        var answer: Lyrics? = null
        val cache = OnlineLyricsCache({ calls++; answer }, now = { now }, missTtlMs = 1_000)
        assertNull(cache.get(query))
        assertSame(OnlineLyricsCache.MISSING, cache.peek(query))
        assertNull(cache.get(query))
        assertEquals(1, calls)

        now = 1_500
        answer = Lyrics("Só texto\nsem tempo", synced = false)
        val plain = cache.get(query)!!
        assertEquals(false, plain.synced)
        assertEquals(2, calls)
    }

    @Test fun `blank or failing answers count as none`() = runBlocking {
        val blank = OnlineLyricsCache({ Lyrics("  \n ", synced = false) })
        assertNull(blank.get(query))
        val failing = OnlineLyricsCache({ error("network") })
        assertNull(failing.get(query))
    }

    @Test fun `the cache forgets the oldest songs`() = runBlocking {
        val cache = OnlineLyricsCache({ q -> Lyrics(q.title, synced = false) }, capacity = 2)
        val a = query.copy(title = "a")
        val b = query.copy(title = "b")
        val c = query.copy(title = "c")
        cache.get(a)
        cache.get(b)
        cache.get(c)
        assertNull(cache.peek(a))
        assertEquals("c", cache.peek(c)!!.lines.single().text)
    }
}
