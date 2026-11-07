package com.villamediana.sound.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

class SpotifyImportTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource("spotify/$name")!!.readText()

    private fun rt(id: String, title: String, artist: String, durationMs: Long? = null) =
        RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, artist, durationMs = durationMs)

    // ------------------------------------------------------------------ links

    @Test fun findsThePlaylistInEveryKindOfLink() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        listOf(
            "https://open.spotify.com/playlist/$id?si=abc123",
            "Ouça no Spotify: https://open.spotify.com/intl-pt/playlist/$id",
            "https://open.spotify.com/embed/playlist/$id",
            "spotify:playlist:$id",
        ).forEach { assertEquals(it, id, SpotifyLinks.playlistId(it)) }
        assertNull(SpotifyLinks.playlistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyLinks.playlistId("https://www.youtube.com/playlist?list=PL123"))
        assertTrue(SpotifyLinks.mentions("https://spotify.link/AbC12xyz"))
        assertFalse(SpotifyLinks.mentions("só um texto"))
    }

    // ------------------------------------------------------------------ embed page

    @Test fun readsTheEmbedPage() {
        val p = SpotifyPlaylists.parseEmbed("37i9dQZF1DXcBWIGoYBM5M", resource("embed-top-hits.html"))!!
        assertEquals("Today’s Top Hits", p.name)
        assertEquals(50, p.tracks.size)
        assertEquals(SpotifyTrack("Stop The Wedding!", "Ashe", 198_840), p.tracks.first())
        assertTrue(p.coverUrl!!.startsWith("https://"))
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", p.url)
        assertTrue(p.tracks.all { it.title.isNotBlank() && it.artist.isNotBlank() })
    }

    @Test fun aPrivateOrMissingPlaylistIsNotFound() {
        assertNull(SpotifyPlaylists.parseEmbed("0000000000000000000000", resource("embed-not-found.html")))
        assertNull(SpotifyPlaylists.parseEmbed("x", "<html>nada</html>"))
    }

    @Test fun textWithoutALinkFailsBeforeTheNetwork() = runBlocking {
        val error = runCatching { SpotifyPlaylists(OkHttpClient()).find("minha playlist") }.exceptionOrNull()
        assertEquals(SpotifyError.NotALink, error)
    }

    // ------------------------------------------------------------------ matching

    @Test fun matchesTheSameSongByTheSameArtist() {
        val t = SpotifyTrack("Dreams - 2004 Remaster", "Fleetwood Mac", 257_000)
        val picked = SpotifyMatch.pick(
            t,
            listOf(
                rt("cover", "Dreams", "The Cranberries", 271_000),
                rt("live", "Dreams (Live)", "Fleetwood Mac", 330_000),
                rt("ok", "Dreams (2004 Remaster)", "Fleetwood Mac", 258_000),
            ),
        )
        assertEquals("ok", picked?.videoId)
        assertEquals("Dreams Fleetwood Mac", SpotifyMatch.query(t))
    }

    @Test fun featuredArtistsAndExtraArtistsStillMatch() {
        val t = SpotifyTrack("Die With A Smile", "Lady Gaga, Bruno Mars", 251_000)
        assertEquals("a", SpotifyMatch.pick(t, listOf(rt("a", "Die With A Smile", "Lady Gaga & Bruno Mars", 252_000)))?.videoId)
        val feat = SpotifyTrack("Señorita (feat. X)", "Shawn Mendes", null)
        assertEquals("b", SpotifyMatch.pick(feat, listOf(rt("b", "Señorita", "Shawn Mendes and Camila Cabello")))?.videoId)
    }

    @Test fun anotherSongOrAnotherArtistIsNoMatch() {
        val t = SpotifyTrack("Perfect", "Simple Plan", 279_000)
        assertNull(SpotifyMatch.pick(t, listOf(rt("ed", "Perfect", "Ed Sheeran", 263_000), rt("x", "Welcome to My Life", "Simple Plan"))))
        assertNull(SpotifyMatch.pick(t, emptyList()))
    }

    // ------------------------------------------------------------------ import

    private val playlist = SpotifyPlaylist(
        id = "abcdefghijklmnopqrstuv",
        name = "Domingo",
        owner = "Miguel",
        coverUrl = "https://i.scdn.co/image/x",
        tracks = listOf(
            SpotifyTrack("Um", "Banda"),
            SpotifyTrack("Dois", "Banda"),
            SpotifyTrack("Sumiu", "Ninguém"),
            SpotifyTrack("Tres", "Banda"),
        ),
    )

    @Test fun importKeepsTheOrderAndSaysWhatWasMissing() = runBlocking {
        var saved: Pair<SpotifyPlaylist, List<RemoteTrack>>? = null
        val importer = SpotifyImporter(
            search = { q -> listOf(rt(q.substringBefore(" ").lowercase(), q.substringBefore(" "), "Banda")).filter { !q.startsWith("Sumiu") } },
            save = { p, tracks -> saved = p to tracks },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        importer.start(playlist)
        val done = withTimeout(5_000) { importer.state.first { it is SpotifyImporter.State.Done } } as SpotifyImporter.State.Done
        assertEquals(3, done.found)
        assertEquals(listOf("Sumiu"), done.missing.map { it.title })
        val (p, tracks) = saved!!
        assertEquals(playlist, p)
        assertEquals(listOf("um", "dois", "tres"), tracks.map { it.videoId })
        // It opens on the online playlist page.
        assertEquals(CollectionKind.PLAYLIST, done.collection.kind)
        assertEquals("Domingo", done.collection.title)
        assertEquals(playlist.url, done.collection.url)
        importer.reset()
        assertEquals(SpotifyImporter.State.Idle, importer.state.value)
    }

    @Test fun noConnectionFailsWithoutCreatingAPlaylist() = runBlocking {
        var saves = 0
        val importer = SpotifyImporter(
            search = { throw java.io.IOException("offline") },
            save = { _, _ -> saves++ },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        importer.start(playlist)
        val failed = withTimeout(5_000) { importer.state.first { it is SpotifyImporter.State.Failed } } as SpotifyImporter.State.Failed
        assertTrue(failed.offline)
        assertEquals(0, saves)
    }

    /** Live (SOUND_LIVE=1): a real public playlist is read and its first songs found on YouTube Music. */
    @Test fun liveImportFindsTheSongs() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1")
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale.forLanguageTag("pt-BR"))
        val p = SpotifyPlaylists(http).find("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x")
        println("LIVE spotify: ${p.name} · ${p.tracks.size} músicas")
        val yt = NewPipeYouTubeSource(http)
        val sample = p.tracks.take(8)
        val found = sample.map { t ->
            val songs = yt.search(SpotifyMatch.query(t), SearchFilter.SONGS).items.filterIsInstance<SearchItem.Song>().map { it.track }
            SpotifyMatch.pick(t, songs.take(8)).also { println("LIVE  ${t.title} — ${t.artist}  →  ${it?.title} — ${it?.artist}") }
        }
        assertTrue(p.tracks.size >= 20)
        assertTrue("found ${found.count { it != null }} of ${sample.size}", found.count { it != null } >= 6)
    }
}
