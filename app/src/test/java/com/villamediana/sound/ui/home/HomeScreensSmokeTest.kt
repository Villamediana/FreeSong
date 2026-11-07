package com.villamediana.sound.ui.home

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.ui.library.CollectionMenuSheet
import com.villamediana.sound.ui.library.CollectionTarget
import com.villamediana.sound.ui.library.LibraryScreen
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.settings.SettingsScreen
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the three screens on Robolectric against a real (seeded) Room library: they must
 * compose, load and show their key content without crashing. With SOUND_SHOTS=1 it also writes
 * PNGs to app/build/sound-shots/ to eyeball the layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class HomeScreensSmokeTest {

    @org.junit.Before
    fun blockPlaybackBinding() = com.villamediana.sound.testing.blockMediaSessionBinding()
    @get:Rule val compose = createComposeRule()

    private val shots = System.getenv("SOUND_SHOTS") == "1"

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val controller = rememberNavController()
        val navigator = remember(controller) { Navigator(controller) }
        SoundTheme {
            CompositionLocalProvider(LocalNavigator provides navigator, LocalBottomInset provides 96.dp) { content() }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        if (!shots) return
        compose.mainClock.advanceTimeBy(2_000) // let entrance animations settle
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun emptyHomeWelcomes() {
        compose.setContent { Host { HomeScreen() } }
        // First run: the genre picker instead of an empty page.
        waitForText("O que você curte?")
        waitForText("Reggaeton")
        // An intro: the mini player stays out of it.
        check(com.villamediana.sound.ui.nav.Chrome.hideMiniPlayer)
        shot("home-picker")
    }

    @Test
    fun homeShowsTheLibrary() {
        seed()
        compose.setContent { Host { HomeScreen() } }
        waitForText("Suas músicas")
        waitForText("Tocadas recentemente")
        shot("home")
        // The library has its own pills: no shortcuts, no added / genres / artists rows here.
        listOf("Curtidas", "Aleatório inteligente", "Adicionadas recentemente", "Por gênero", "Seus artistas").forEach {
            check(compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty()) { "$it on the Home" }
        }
    }

    @Test
    fun songsTabListsTheLikedSongsOnly() {
        seed()
        // A streamed like: the only row offering "Baixar".
        runBlocking {
            SoundApplication.graph.library.setLiked(
                com.villamediana.sound.online.RemoteTrack("zz1", "https://music.youtube.com/watch?v=zz1", "Streamed Love", "Online Artist"),
                true,
            )
        }
        compose.setContent { Host { com.villamediana.sound.ui.library.MainScreen() } }
        compose.onAllNodesWithText("Músicas")[0].performClick()
        waitForText("Velha Infância")
        waitForText("Streamed Love")
        check(compose.onAllNodesWithText("Té Para Tres").fetchSemanticsNodes().isEmpty()) { "a song not liked in Músicas" }
        listOf("Downloads", "Curtidas", "Todas as músicas").forEach {
            check(compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty()) { "$it in Músicas" }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Baixar").fetchSemanticsNodes().size == 1 }
        // Play them all, right there.
        // (The tabs beside it are composed off screen: only the one in view counts.)
        val plays = compose.onAllNodesWithContentDescription("Tocar").fetchSemanticsNodes().map { it.boundsInRoot }
        check(plays.count { it.width > 0f } == 1) { "$plays" }
        shot("songs-tab")

        compose.onAllNodesWithText("Playlists")[0].performClick()
        waitForText("Domingo")
        listOf("Downloads", "Curtidas").forEach {
            check(compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty()) { "$it in Playlists" }
        }
        // Back to Músicas: still the songs (the pill never toggles back to "everything").
        compose.onAllNodesWithText("Músicas")[0].performClick()
        waitForText("Velha Infância")
        // Playlists stays composed beside it (swipe), but out of view.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Domingo").fetchSemanticsNodes().none { it.boundsInRoot.width > 0f } }
    }

    @Test
    fun libraryListsAndFilters() {
        seed()
        compose.setContent { Host { LibraryScreen() } }
        waitForText("Sua biblioteca")
        waitForText("Domingo")
        shot("library")
        compose.onNodeWithContentDescription("Ver em grade").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Ver em lista").fetchSemanticsNodes().isNotEmpty() }
        shot("library-grid")
        compose.onNodeWithContentDescription("Ver em lista").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Ver em grade").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Álbuns")[0].performClick()
        waitForText("Canción Animal")
        // TalkBack hears which filter is on.
        compose.onNode(hasText("Álbuns") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Playlists") and isSelectable()).assertIsNotSelected()
        shot("library-albums")
        compose.onAllNodesWithText("Músicas")[0].performClick()
        waitForText("Velha Infância")
        shot("library-songs")
    }

    @Test
    fun playlistsPlusImportsFromSpotify() {
        seed()
        // An imported playlist sits with the others (online: nothing downloaded).
        runBlocking {
            SoundApplication.graph.library.saveImported(
                "https://open.spotify.com/playlist/abcdefghijklmnopqrstuv", "Do Spotify", "Miguel", null,
                listOf(com.villamediana.sound.online.RemoteTrack("zz1", "https://music.youtube.com/watch?v=zz1", "Streamed Love", "Online Artist")),
            )
        }
        compose.setContent { Host { com.villamediana.sound.ui.library.MainScreen() } }
        compose.onAllNodesWithText("Playlists")[0].performClick()
        waitForText("Domingo")
        waitForText("Do Spotify")
        compose.onNodeWithContentDescription("Nova playlist").performClick()
        waitForText("Importar do Spotify")
        compose.onAllNodesWithText("Importar do Spotify")[0].performClick()
        waitForText("Link da playlist")
        val live = System.getProperty("sound.live") == "1"
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput(
            if (live) "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x" else "minha playlist",
        )
        compose.onAllNodesWithText("Buscar")[0].performClick()
        if (live) {
            waitForText("Today’s Top Hits", 30_000)
            waitForText("Importar")
            shot("spotify-preview")
        } else {
            waitForText("Não é um link de playlist do Spotify.")
            shot("spotify-input")
        }
        com.villamediana.sound.ui.library.SpotifyImportRequest.open = false
    }

    @Test
    fun recapSumsUpTheListening() {
        seed()
        runBlocking {
            val lib = SoundApplication.graph.library
            val tracks = lib.allTracks()
            val soda = tracks.first { it.title == "De Música Ligera" }
            repeat(4) { lib.recordPlay(soda.id, 220_000, 230_000, "ALBUM") }
            lib.recordPlay(tracks.first { it.title == "Velha Infância" }.id, 200_000, 210_000, "SONGS")
            val online = com.villamediana.sound.online.RemoteTrack("zz1", "https://music.youtube.com/watch?v=zz1", "Streamed Love", "Online Artist")
            repeat(3) { lib.recordOnlinePlay(online, 180_000, 190_000, "RADIO") }
        }
        // Outside January the Início has no way in (the retrospective is once a year).
        val year = java.time.LocalDate.now().year
        compose.setContent { Host { com.villamediana.sound.ui.recap.RecapScreen(year) } }
        waitForText("Sua retrospectiva $year")
        waitForText("Mais tocadas")
        waitForText("De Música Ligera")
        waitForText("Streamed Love")
        waitForText("Soda Stereo")
        shot("recap")
    }

    @Test
    fun swipingMovesBetweenTabs() {
        seed()
        compose.setContent { Host { com.villamediana.sound.ui.library.MainScreen() } }
        compose.onAllNodesWithText("Músicas")[0].performClick()
        waitForText("Velha Infância")
        // On the content: the next tab.
        compose.onNodeWithText("Velha Infância").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        waitForText("Domingo")
        compose.onNode(hasText("Playlists") and isSelectable()).assertIsSelected()
        // On the pills ("menu") too: back to Músicas.
        compose.onNode(hasText("Playlists") and isSelectable()).performTouchInput { swipeRight(startX = left, endX = left + 900f) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Músicas") and isSelectable() and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Velha Infância")
    }

    @Test
    fun settingsShowEverything() {
        seed()
        compose.setContent { Host { SettingsScreen() } }
        waitForText("Qualidade do áudio")
        waitForText("Máxima")
        // Choices and switches carry their state for TalkBack (the whole row is the control).
        compose.onNode(hasText("Máxima") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Buscar letras") and isToggleable()).assertIsOn()
        compose.onNode(hasText("Baixar só no Wi-Fi") and isToggleable()).assertIsOff()
        shot("settings")
        compose.onAllNodes(hasScrollToKeyAction())[0].performScrollToKey("about")
        waitForText("Limpar histórico")
        // Everything kept to spare the internet, each with its size and "Limpar".
        compose.onAllNodes(hasScrollToKeyAction())[0].performScrollToKey("about")
        waitForText("Guardado pra economizar internet")
        listOf("Músicas ouvidas", "Vídeos de fundo", "Capas", "Letras e traduções", "Sugestões do Início", "Limpar tudo").forEach { waitForText(it) }
        waitForText("músicas")
        shot("settings-bottom")
    }

    @Test
    fun mixCardsRender() {
        val app = ApplicationProvider.getApplicationContext<SoundApplication>()
        val covers = listOf(0xFFB5432A, 0xFF2F5D8C, 0xFFE4B363, 0xFF3D7A5E).mapIndexed { i, c -> cover(app.cacheDir, "m$i", c.toInt()) }
        val mixes = listOf(
            Mix("genre:mpb", "Seu MPB", "Tribalistas, Elis Regina e mais", Mix.Kind.GENRE, emptyList(), covers, 0xFF3D7A5E.toInt()),
            Mix("on-repeat", "No repeat", "O que você não para de ouvir", Mix.Kind.ON_REPEAT, emptyList(), covers.take(1), null),
        )
        compose.setContent {
            Host {
                Row(Modifier.background(SoundColors.Ink).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    mixes.forEachIndexed { i, m -> MixCard(m, playing = i == 1, onOpen = {}, onPlay = {}) }
                }
            }
        }
        waitForText("Seu MPB")
        waitForText("No repeat")
        shot("mix-cards")
    }

    @Test
    fun longPressMenuDeletesAPlaylistLikeItsOwnPage() {
        seed()
        val lib = SoundApplication.graph.library
        val domingo = runBlocking { lib.playlists.first().single { it.name == "Domingo" } }
        compose.setContent { Host { CollectionMenuSheet(CollectionTarget.Playlist(domingo), onDismiss = {}) } }
        waitForText("Apagar playlist")
        compose.onAllNodesWithText("Apagar playlist")[0].performClick()
        // The same dialog as the playlist's own ⋮ (detail_delete_playlist_*).
        waitForText("Apagar playlist?")
        waitForText("“Domingo” sai da biblioteca")
        compose.onAllNodesWithText("Apagar", substring = false)[0].performClick()
        compose.waitUntil(10_000) { runBlocking { lib.playlists.first().none { it.id == domingo.playlist.id } } }
    }

    // ------------------------------------------------------------------ data

    private fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SoundApplication>()
        val lib = SoundApplication.graph.library
        val now = System.currentTimeMillis()
        val covers = listOf(0xFFB5432A, 0xFF2F5D8C, 0xFFE4B363, 0xFF3D7A5E, 0xFF7A3D6E, 0xFF1F1F1F).mapIndexed { i, c ->
            cover(app.cacheDir, "c$i", c.toInt())
        }
        data class S(val title: String, val artist: String, val album: String?, val genre: String, val year: Int, val cover: Int)
        val songs = listOf(
            S("De Música Ligera", "Soda Stereo", "Canción Animal", "Rock en español", 1990, 0),
            S("Té Para Tres", "Soda Stereo", "Canción Animal", "Rock en español", 1990, 0),
            S("Un Millón de Años Luz", "Soda Stereo", "Canción Animal", "Rock en español", 1990, 0),
            S("Velha Infância", "Tribalistas", "Tribalistas", "MPB", 2002, 1),
            S("Já Sei Namorar", "Tribalistas", "Tribalistas", "MPB", 2002, 1),
            S("Midnight City", "M83", "Hurry Up, We're Dreaming", "Indie", 2011, 2),
            S("Wait", "M83", "Hurry Up, We're Dreaming", "Indie", 2011, 2),
            S("Águas de Março", "Elis Regina", "Elis & Tom", "MPB", 1974, 3),
            S("Get Lucky", "Daft Punk", "Random Access Memories", "Electronic", 2013, 4),
            S("Instant Crush", "Daft Punk", "Random Access Memories", "Electronic", 2013, 4),
            S("Flor de Lis", "Djavan", null, "MPB", 1976, 5),
        )
        val ids = songs.mapIndexed { i, s ->
            lib.insertTrack(
                Track(
                    videoId = "vid$i", title = s.title, artist = s.artist, album = s.album, genre = s.genre, year = s.year,
                    trackNumber = i + 1, durationMs = 200_000L + i * 7_000, filePath = "/nope/$i.m4a", fileSize = 4_000_000,
                    coverPath = covers[s.cover], addedAt = now - i * 3_600_000L,
                    liked = i % 3 == 0, likedAt = if (i % 3 == 0) now - i else null,
                    playCount = (songs.size - i) % 5, lastPlayedAt = if (i % 2 == 0) now - i * 60_000L else null,
                ),
            )
        }
        val list = lib.createPlaylist("Domingo", "Pra ouvir devagar")
        lib.addToPlaylist(list, listOf(ids[3], ids[7], ids[10], ids[5]))
        lib.setPlaylistPinned(list, true)
        lib.setAlbumPinned(AlbumKey("Daft Punk", "Random Access Memories"), true)
    }

    private fun cover(dir: File, name: String, color: Int): String {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x55FFFFFF }
        c.drawCircle(210f, 110f, 70f, p)
        p.color = 0x33000000
        c.drawRect(0f, 200f, 300f, 300f, p)
        val f = File(dir, "$name.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }
}
