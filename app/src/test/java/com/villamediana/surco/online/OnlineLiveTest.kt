package com.villamediana.surco.online

import com.villamediana.surco.data.AudioQuality
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hits YouTube, YouTube Music, iTunes and LRCLIB for real. Run with `SURCO_LIVE=1`.
 * Every link below is public and was checked by hand.
 */
class OnlineLiveTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val yt by lazy { NewPipeYouTubeSource(http) }

    @Before fun live() {
        assumeTrue(System.getProperty("surco.live") == "1")
        NewPipeHttp.init(http, Locale("pt", "BR"))
    }

    // ---------------------------------------------------------------- search

    @Test fun searchSongsAndMore() = runBlocking {
        val page = yt.search("Soda Stereo De Música Ligera", SearchFilter.SONGS)
        val songs = page.items.filterIsInstance<SearchItem.Song>().map { it.track }
        println("SONGS: " + songs.take(5).joinToString { "${it.title} — ${it.artist} (${it.durationMs}) ${it.thumbnailUrl}" })
        assertTrue(songs.size >= 5)
        assertTrue(songs.all { it.fromMusicCatalog && it.url.startsWith("https://music.youtube.com/watch?v=") })
        assertTrue(songs.any { it.title.contains("Música Ligera", ignoreCase = true) && it.artist == "Soda Stereo" })
        assertTrue(songs.none { it.artist.endsWith(" - Topic") })
        assertTrue(songs.all { (it.durationMs ?: 0) > 60_000 })
        assertTrue(songs.first().thumbnailUrl!!.contains("=w${Thumbnails.SONG}-h${Thumbnails.SONG}"))
        assertTrue(page.hasMore)
        val next = yt.more(page)
        println("SONGS p2: " + next.items.size)
        assertTrue(next.items.isNotEmpty())
        val ids = (page.items + next.items).filterIsInstance<SearchItem.Song>().map { it.track.videoId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun searchVideos() = runBlocking {
        val page = yt.search("Anitta Envolver", SearchFilter.VIDEOS)
        val videos = page.items.filterIsInstance<SearchItem.Song>().map { it.track }
        println("VIDEOS: " + videos.take(5).joinToString { "${it.title} — ${it.artist} ${it.thumbnailUrl}" })
        assertTrue(videos.size >= 5)
        assertTrue(videos.none { it.fromMusicCatalog })
        assertTrue(videos.all { it.url.startsWith("https://www.youtube.com/watch?v=") && (it.durationMs ?: 0) > 0 })
        assertTrue(videos.any { it.title.contains("Envolver", ignoreCase = true) })
        val next = yt.more(page)
        assertTrue(next.items.isNotEmpty())
    }

    @Test fun searchAlbums() = runBlocking {
        val page = yt.search("Soda Stereo", SearchFilter.ALBUMS)
        val albums = page.items.filterIsInstance<SearchItem.Collection>().map { it.collection }
        println("ALBUMS: " + albums.take(5).joinToString { "${it.title} — ${it.author} ${it.url} ${it.thumbnailUrl}" })
        assertTrue(albums.size >= 5)
        assertTrue(albums.all { it.kind == CollectionKind.ALBUM && it.url.contains("list=OLAK5uy_") })
        assertTrue(albums.any { it.title == "Canción Animal" && it.author == "Soda Stereo" })
        assertTrue(albums.first().thumbnailUrl!!.contains("=w${Thumbnails.COLLECTION}-h${Thumbnails.COLLECTION}"))
        assertTrue(yt.more(page).items.isNotEmpty())
    }

    @Test fun searchPlaylists() = runBlocking {
        val page = yt.search("rock en español clásico", SearchFilter.PLAYLISTS)
        val lists = page.items.filterIsInstance<SearchItem.Collection>().map { it.collection }
        println("PLAYLISTS: " + lists.take(5).joinToString { "${it.title} — ${it.author} ${it.url}" })
        assertTrue(lists.size >= 5)
        assertTrue(lists.all { it.url.startsWith("https://music.youtube.com/playlist?list=") })
        assertTrue(lists.any { it.kind == CollectionKind.PLAYLIST })
        assertTrue(yt.more(page).items.isNotEmpty())
    }

    @Test fun searchArtists() = runBlocking {
        val page = yt.search("Soda Stereo", SearchFilter.ARTISTS)
        val artists = page.items.filterIsInstance<SearchItem.Collection>().map { it.collection }
        println("ARTISTS: " + artists.joinToString { "${it.title} ${it.url} ${it.thumbnailUrl}" })
        assertTrue(artists.isNotEmpty())
        val soda = artists.first()
        assertEquals("Soda Stereo", soda.title)
        assertEquals(CollectionKind.ARTIST, soda.kind)
        assertEquals("https://music.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw", soda.url)
        // Artist search is a single page on YouTube Music; more() must still behave.
        val more = yt.more(page)
        assertTrue(more.items.isEmpty() || more.items.none { it in page.items })
    }

    @Test fun suggestions() = runBlocking {
        val s = yt.suggestions("soda ste")
        println("SUGGESTIONS: $s")
        assertTrue(s.isNotEmpty())
        assertTrue(s.any { it.contains("soda stereo", ignoreCase = true) })
        assertEquals(emptyList<String>(), yt.suggestions("   "))
    }

    // ---------------------------------------------------------------- links

    @Test fun resolveSongShareText() = runBlocking {
        val r = yt.resolve("Ouça De Música Ligera de Soda Stereo no YouTube Music: https://music.youtube.com/watch?v=sWft4cMiwXQ&si=Xy12AbCd")
        val t = (r as ResolvedLink.Track).track
        println("RESOLVE song: $t")
        assertEquals("sWft4cMiwXQ", t.videoId)
        assertEquals("Soda Stereo", t.artist)
        assertTrue(t.fromMusicCatalog)
        assertEquals("Canción Animal", t.album)
        assertTrue((t.durationMs ?: 0) in 200_000..230_000)
        assertNotNull(t.thumbnailUrl)
    }

    @Test fun resolveVideoLinks() = runBlocking {
        val short = yt.resolve("https://youtu.be/T_FkEw27XJ0?si=abc") as ResolvedLink.Track
        println("RESOLVE youtu.be: ${short.track}")
        assertEquals("T_FkEw27XJ0", short.track.videoId)
        assertTrue(short.track.title.contains("Música Ligera", ignoreCase = true))
        assertTrue(!short.track.fromMusicCatalog)
        val watch = yt.resolve("https://www.youtube.com/watch?v=T_FkEw27XJ0&feature=share&pp=ygUJ") as ResolvedLink.Track
        assertEquals("T_FkEw27XJ0", watch.track.videoId)
        val shorts = yt.resolve("https://www.youtube.com/shorts/T_FkEw27XJ0") as ResolvedLink.Track
        assertEquals("T_FkEw27XJ0", shorts.track.videoId)
        assertEquals(ResolvedLink.Unsupported, yt.resolve("https://open.spotify.com/track/1"))
    }

    @Test fun resolveCollections() = runBlocking {
        val album = (yt.resolve("https://music.youtube.com/playlist?list=OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4&si=x") as ResolvedLink.Collection).collection
        println("RESOLVE album: $album")
        assertEquals(CollectionKind.ALBUM, album.kind)
        assertEquals("Sueño Stereo", album.title)
        assertEquals("Soda Stereo", album.author)
        assertEquals(1995, album.year)

        val browse = (yt.resolve("https://music.youtube.com/browse/MPREb_7LvbQPQlUGp") as ResolvedLink.Collection).collection
        println("RESOLVE MPREb: $browse")
        assertEquals(CollectionKind.ALBUM, browse.kind)
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4", browse.url)

        val playlist = (yt.resolve("https://www.youtube.com/playlist?list=PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ") as ResolvedLink.Collection).collection
        println("RESOLVE PL: $playlist")
        assertEquals(CollectionKind.PLAYLIST, playlist.kind)
        assertTrue(playlist.title.contains("Soda Stereo", ignoreCase = true))

        val curated = (yt.resolve("https://music.youtube.com/playlist?list=RDCLAK5uy_nyD9oEfxn-d5aJD7sgL89sZoTJ5q3jZ4w") as ResolvedLink.Collection).collection
        println("RESOLVE RDCLAK: $curated")
        assertEquals(CollectionKind.PLAYLIST, curated.kind)
        assertTrue(curated.title.isNotBlank())
    }

    @Test fun resolveArtists() = runBlocking {
        for (link in listOf(
            "https://www.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw",
            "https://music.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw?si=pq",
            "https://www.youtube.com/@SodaStereo",
        )) {
            val c = (yt.resolve(link) as ResolvedLink.Collection).collection
            println("RESOLVE artist $link → $c")
            assertEquals(CollectionKind.ARTIST, c.kind)
            assertEquals("Soda Stereo", c.title)
            // Topic channel, official channel and @handle all land on the same YouTube Music artist.
            assertEquals("https://music.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw", c.url)
            assertNotNull(c.thumbnailUrl)
        }
    }

    // ---------------------------------------------------------------- collections

    @Test fun albumCollection() = runBlocking {
        val d = yt.collection("https://music.youtube.com/playlist?list=OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4", CollectionKind.ALBUM)
        println("ALBUM: ${d.collection}\n  " + d.tracks.joinToString("\n  ") { "${it.title} — ${it.artist} [${it.album}] ${it.durationMs}" })
        assertEquals("Sueño Stereo", d.collection.title)
        assertEquals("Soda Stereo", d.collection.author)
        assertEquals(1995, d.collection.year)
        assertEquals(12, d.tracks.size)
        assertEquals(12L, d.collection.itemCount)
        assertTrue(d.tracks.all { it.album == "Sueño Stereo" && it.artist == "Soda Stereo" && it.fromMusicCatalog })
        assertTrue(d.tracks.all { it.thumbnailUrl == d.collection.thumbnailUrl })
        assertTrue(d.collection.thumbnailUrl!!.isNotBlank())
    }

    @Test fun albumFromBrowseLink() = runBlocking {
        val d = yt.collection("https://music.youtube.com/browse/MPREb_7LvbQPQlUGp", CollectionKind.ALBUM)
        assertEquals("Sueño Stereo", d.collection.title)
        assertEquals(12, d.tracks.size)
    }

    @Test fun playlistCollection() = runBlocking {
        val d = yt.collection("https://music.youtube.com/playlist?list=RDCLAK5uy_nyD9oEfxn-d5aJD7sgL89sZoTJ5q3jZ4w", CollectionKind.PLAYLIST)
        println("PLAYLIST: ${d.collection} (${d.tracks.size})\n  " + d.tracks.take(5).joinToString("\n  ") { "${it.title} — ${it.artist}" })
        assertEquals(CollectionKind.PLAYLIST, d.collection.kind)
        assertEquals(null, d.collection.year)
        assertTrue(d.tracks.size >= 20)
        assertEquals(d.tracks.size, d.tracks.map { it.videoId }.toSet().size)

        val big = yt.collection("https://www.youtube.com/playlist?list=PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ", CollectionKind.PLAYLIST, limit = 150)
        println("PL: ${big.collection} (${big.tracks.size})")
        assertTrue(big.tracks.size in 1..150)
        assertNotNull(big.collection.thumbnailUrl)
    }

    @Test fun artistCollection() = runBlocking {
        val d = yt.collection("https://music.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw", CollectionKind.ARTIST, limit = 120)
        println("ARTIST: ${d.collection} tracks=${d.tracks.size}\n  " + d.tracks.take(5).joinToString("\n  ") { "${it.title} — ${it.artist}" } +
            "\n related: " + d.related.joinToString { "${it.kind}:${it.title}(${it.year})" })
        assertEquals("Soda Stereo", d.collection.title)
        assertTrue(d.tracks.size >= 20)
        assertTrue(d.tracks.size <= 120)
        assertTrue(d.tracks.all { it.fromMusicCatalog })
        val albums = d.related.filter { it.kind == CollectionKind.ALBUM }
        assertTrue(albums.size >= 5)
        assertTrue(albums.any { it.title == "Canción Animal" })
        assertTrue(albums.all { it.year != null })
        assertNotNull(d.description)
    }

    // ---------------------------------------------------------------- audio

    @Test fun audioStreamsForEveryQuality() = runBlocking {
        val best = yt.audioStream("sWft4cMiwXQ", AudioQuality.BEST)
        val compatible = yt.audioStream("sWft4cMiwXQ", AudioQuality.COMPATIBLE)
        val saver = yt.audioStream("sWft4cMiwXQ", AudioQuality.SAVER)
        println("BEST $best\nCOMPATIBLE $compatible\nSAVER $saver".replace(Regex("url=[^,]+"), "url=…"))
        assertEquals("webm", best.extension)
        assertEquals("audio/webm", best.mimeType)
        assertEquals("m4a", compatible.extension)
        assertEquals("audio/mp4", compatible.mimeType)
        assertTrue(best.bitrate > compatible.bitrate - 20_000)
        assertTrue(saver.bitrate in 40_000..80_000)
        assertTrue((compatible.contentLength ?: 0) > 1_000_000)

        for (s in listOf(best, compatible)) {
            val req = Request.Builder().url(s.url).header("Range", "bytes=0-262143").header("User-Agent", NewPipeHttp.USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                val bytes = r.body.bytes()
                println("GET ${s.extension} → ${r.code} ${bytes.size} bytes")
                assertTrue(r.code == 206 || r.code == 200)
                assertEquals(262_144, bytes.size)
            }
        }
    }

    @Test fun trackDetails() = runBlocking {
        val t = yt.track("T_FkEw27XJ0")
        println("TRACK $t")
        assertTrue(t.title.contains("Música Ligera", ignoreCase = true))
        assertEquals("Soda Stereo", t.artist)
        assertTrue(!t.fromMusicCatalog)
        assertTrue(t.thumbnailUrl!!.startsWith("https://i.ytimg.com/vi/T_FkEw27XJ0/"))
    }

    // ---------------------------------------------------------------- metadata + lyrics

    @Test fun enrichRealSongs() = runBlocking {
        val enricher = CatalogMetadataEnricher(http, country = "BR")
        val soda = enricher.enrich(MetaInput("De Música Ligera (Remasterizado 2007)", "Soda Stereo", null, null, null, null, 213_000, true))
        println("ENRICH soda: $soda")
        assertEquals("Soda Stereo", soda.artist)
        assertNotNull(soda.genre)
        assertEquals(1990, soda.year)
        assertNotNull(soda.artworkUrl)

        val anitta = enricher.enrich(MetaInput("Anitta - Envolver [Official Music Video]", "Anitta", null, null, null, null, 194_000, false))
        println("ENRICH anitta: $anitta")
        assertEquals("Envolver", anitta.title)
        assertEquals("Anitta", anitta.artist)
        assertNotNull(anitta.genre)
        assertEquals(2021, anitta.year)

        val bunny = enricher.enrich(MetaInput("Bad Bunny - DtMF (Video Oficial)", "Bad Bunny", null, null, null, null, 237_000, false))
        println("ENRICH bunny: $bunny")
        assertEquals("DtMF", bunny.title)
        assertEquals("Bad Bunny", bunny.artist)
        // iTunes search doesn't index this release; covers/look-alikes must not be taken for it.
        assertTrue(bunny.album == null || bunny.album!!.contains("FOToS", ignoreCase = true))
    }

    @Test fun lyricsForRealSongs() = runBlocking {
        val lrclib = LrclibLyricsSource(http)
        val soda = lrclib.find("De Música Ligera", "Soda Stereo", "Canción Animal", 213_000)
        println("LYRICS soda: ${soda?.synced} ${soda?.text?.take(80)}")
        assertNotNull(soda)
        assertTrue(soda!!.text.contains("música ligera", ignoreCase = true))
        val bunny = lrclib.find("DtMF", "Bad Bunny", "DeBÍ TiRAR MáS FOToS", 237_000)
        println("LYRICS bunny: ${bunny?.synced} ${bunny?.text?.take(80)}")
        assertNotNull(bunny)
        assertTrue(bunny!!.synced)
    }
}
