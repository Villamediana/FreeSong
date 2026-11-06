package com.villamediana.sound.online

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibLyricsSourceTest {

    private fun records(json: String) = OnlineJson.decodeFromString(ListSerializer(LrclibRecord.serializer()), json)

    @Test fun parsesRecordAndPrefersSynced() {
        val r = OnlineJson.decodeFromString(LrclibRecord.serializer(), OnlineFixtures.lrclibGet)
        assertEquals("De Música Ligera", r.trackName)
        assertEquals(213.0, r.duration!!, 0.01)
        val lyrics = LrclibMatcher.lyricsOf(r)!!
        assertTrue(lyrics.synced)
        assertTrue(lyrics.text.startsWith("[00:20.50]"))
    }

    @Test fun plainWhenNoSyncedAndNullForInstrumental() {
        val plain = LrclibRecord(trackName = "x", plainLyrics = "line", syncedLyrics = "  ")
        assertEquals(Lyrics("line", synced = false), LrclibMatcher.lyricsOf(plain))
        assertNull(LrclibMatcher.lyricsOf(LrclibRecord(instrumental = true, plainLyrics = "x")))
        assertNull(LrclibMatcher.lyricsOf(LrclibRecord()))
    }

    @Test fun searchPicksClosestDurationWithinTolerance() {
        val results = records(OnlineFixtures.lrclibSearchDtmf)
        // 237 s: the 237.0 one is plain only, the 238.0 one is synced and within a second → synced wins.
        val l = LrclibMatcher.pick(results, "DtMF", "Bad Bunny", 237_000)!!
        assertTrue(l.synced)
        assertTrue(l.text.contains("Eh-eh"))
        // 180 s: the short edit.
        assertEquals("[00:01.00] short edit", LrclibMatcher.pick(results, "DtMF", "Bad Bunny", 180_000)!!.text)
        // Nothing within ±5 s.
        assertNull(LrclibMatcher.pick(results, "DtMF", "Bad Bunny", 300_000))
        // Other songs never match.
        assertNull(LrclibMatcher.pick(results, "Callaíta", "Bad Bunny", 237_000))
    }

    @Test fun exactHit() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.lrclibGet }
        val source = LrclibLyricsSource(fake.client)
        val l = source.find("De Música Ligera", "Soda Stereo", "Canción Animal", 213_400)!!
        assertTrue(l.synced)
        val url = fake.requests.single().url
        assertEquals("/api/get", url.encodedPath)
        assertEquals("213", url.queryParameter("duration"))
        assertEquals("Canción Animal", url.queryParameter("album_name"))
        assertEquals(LrclibLyricsSource.USER_AGENT, fake.requests.single().header("User-Agent"))
    }

    @Test fun notFoundFallsBackToSearch() = runTest {
        val fake = OnlineFixtures.FakeHttp { req ->
            if (req.url.encodedPath == "/api/get") 404 to OnlineFixtures.lrclibNotFound
            else 200 to OnlineFixtures.lrclibSearchDtmf
        }
        val source = LrclibLyricsSource(fake.client)
        val l = source.find("DtMF (Remix Oficial)", "Bad Bunny", null, 238_000)!!
        assertTrue(l.synced)
        assertEquals(2, fake.requests.size)
        val search = fake.requests[1].url
        assertEquals("/api/search", search.encodedPath)
        assertEquals("DtMF (Remix Oficial)".let { TitleCleaner.baseTitle(it) }, search.queryParameter("track_name"))
    }

    @Test fun instrumentalExactHitHasNoLyrics() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to """{"id":1,"trackName":"Intro","artistName":"X","duration":60,"instrumental":true}""" }
        assertNull(LrclibLyricsSource(fake.client).find("Intro", "X", null, 60_000))
        assertEquals(1, fake.requests.size)
    }

    @Test fun neverThrows() = runTest {
        val offline = OnlineFixtures.FakeHttp { throw java.net.UnknownHostException("offline") }
        assertNull(LrclibLyricsSource(offline.client).find("Envolver", "Anitta", null, 194_000))
        val broken = OnlineFixtures.FakeHttp { 200 to "<html>oops</html>" }
        assertNull(LrclibLyricsSource(broken.client).find("Envolver", "Anitta", null, 194_000))
        val serverError = OnlineFixtures.FakeHttp { 500 to "" }
        assertNull(LrclibLyricsSource(serverError.client).find("Envolver", "Anitta", null, 194_000))
    }
}
