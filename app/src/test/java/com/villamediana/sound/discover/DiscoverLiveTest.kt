package com.villamediana.sound.discover

import com.villamediana.sound.online.NewPipeHttp
import com.villamediana.sound.online.NewPipeYouTubeSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/** Live: real covers and shelves from YouTube Music. SOUND_LIVE=1 */
class DiscoverLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun coversAndShelvesComeFromYouTubeMusic() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1")
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale.forLanguageTag("pt-BR"))
        val repo = DiscoverRepository(NewPipeYouTubeSource(http), tmp.root.resolve("d.json"))
        val genres = listOf("pop", "reggaeton", "sertanejo", "salsa").mapNotNull(GenreCatalog::byId)
        val covers = repo.covers(genres)
        genres.forEach { g ->
            println("${g.id}: ${covers[g.id]?.size} covers")
            assertTrue("no covers for ${g.id}", covers[g.id].orEmpty().size >= 2)
        }
        val shelf = repo.shelf(genres[1])
        println("reggaeton shelf: " + shelf.take(5).joinToString { "${it.title} — ${it.artist}" })
        assertTrue(shelf.size >= 8)
    }

    @Test fun theRestOfTheHomeIsLiveToo() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1")
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale.forLanguageTag("pt-BR"))
        val repo = DiscoverRepository(NewPipeYouTubeSource(http), tmp.root.resolve("d.json"))
        val rock = GenreCatalog.byId("rock")!!
        val classics = repo.songs("classics:rock", "${rock.term} classics", 12)
        println("classics: " + classics.take(4).joinToString { "${it.title} — ${it.artist}" })
        assertTrue(classics.size >= 6)
        val related = repo.related(classics.first().videoId)
        println("because: " + related.take(4).joinToString { "${it.title} — ${it.artist}" })
        assertTrue(related.isNotEmpty())
        val playlists = repo.playlists(rock, 3)
        println("playlists: " + playlists.joinToString { it.title })
        assertTrue(playlists.isNotEmpty())
        val artist = repo.artist(classics.first().artist.substringBefore(",").trim())
        println("artist: ${artist?.title} ${artist?.url}")
        assertTrue(artist != null)
    }

    /** The player's album and artist by name, and the long radios the made-for-you mixes compare. */
    @Test fun albumAndArtistByNameAndLongRadios() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1")
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale.forLanguageTag("pt-BR"))
        val source = NewPipeYouTubeSource(http)
        val repo = DiscoverRepository(source, tmp.root.resolve("d.json"))
        val album = repo.findAlbum("Amar Es Combatir", "Maná", sameArtist = true)
        println("album: ${album?.title} — ${album?.author} ${album?.url}")
        assertTrue(album != null)
        val songs = source.collection(album!!.url, com.villamediana.sound.online.CollectionKind.ALBUM).tracks
        println("album songs: ${songs.size} " + songs.take(4).joinToString { it.title })
        assertTrue(songs.size >= 8)
        val artist = repo.findArtist("Maná")
        println("artist: ${artist?.title} ${artist?.url}")
        assertTrue(artist != null)
        val radios = songs.take(3).map { repo.related(it.videoId, 50) }
        println("radios: " + radios.map { it.size })
        val picked = com.villamediana.sound.smart.MadeForYou.pickNew(radios, songs.map { it.videoId }.toSet())
        println("for you (${picked.size}): " + picked.take(10).joinToString { "${it.title} — ${it.artist}" })
        assertTrue(picked.size >= 20)
        val rock = GenreCatalog.byId("rock")!!
        val shelf = repo.shelf(rock)
        val lists = repo.playlists(rock)
        println("rock shelf ${shelf.size}: " + shelf.take(4).joinToString { it.title } + " | playlists: " + lists.joinToString { it.title })
        assertTrue(shelf.size >= 12 && lists.isNotEmpty())
    }
}

