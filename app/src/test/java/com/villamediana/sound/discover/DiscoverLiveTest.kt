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
}
