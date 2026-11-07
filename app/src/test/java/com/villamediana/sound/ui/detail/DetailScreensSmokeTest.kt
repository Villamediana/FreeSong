package com.villamediana.sound.ui.detail

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.ui.nav.AlbumRoute
import com.villamediana.sound.ui.nav.ArtistRoute
import com.villamediana.sound.ui.nav.GenreRoute
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.nav.MixRoute
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.nav.PlaylistRoute
import com.villamediana.sound.ui.sheets.AddToPlaylistSheet
import com.villamediana.sound.ui.sheets.EditTrackDialog
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every detail screen and both sheets on Robolectric against a real, seeded Room library:
 * they must compose, load and show their content, and the sheets must open from the rows. With
 * SOUND_SHOTS=1 it also writes PNGs to app/build/sound-shots/ to eyeball the layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class DetailScreensSmokeTest {

    @org.junit.Before
    fun blockPlaybackBinding() = com.villamediana.sound.testing.blockMediaSessionBinding()
    @get:Rule val compose = createComposeRule()

    private val shots = System.getenv("SOUND_SHOTS") == "1"
    private val library get() = SoundApplication.graph.library

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val controller = rememberNavController()
        val navigator = remember(controller) { Navigator(controller) }
        SoundTheme {
            CompositionLocalProvider(LocalNavigator provides navigator, LocalBottomInset provides 96.dp) { content() }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("Semantics when waiting for '$text' timed out:\n" + compose.onAllNodes(isRoot()).printToString())
            throw e
        }
    }

    private fun assertShown(text: String) {
        check(compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) { "'$text' not on screen" }
    }

    private fun shot(name: String) {
        if (!shots) return
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
        File(dir, "detail-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun t(title: String, artist: String, album: String?, n: Int?, genre: String?, year: Int?, plays: Int = 0, liked: Boolean = false, added: Long = 0) = Track(
        title = title, artist = artist, album = album, trackNumber = n, genre = genre, year = year,
        durationMs = 200_000L + title.length * 3_000L, filePath = "/nope/$title.m4a", playCount = plays,
        liked = liked, likedAt = if (liked) 1_000L + added else null, addedAt = 1_700_000_000_000L + added,
    )

    /** Two Soda Stereo albums, a single, a Cerati song; rock + pop. Returns ids by title. */
    private fun seed(): Map<String, Long> = runBlocking {
        val tracks = listOf(
            t("Canción Animal", "Soda Stereo", "Canción Animal", 2, "Rock", 1990, plays = 9, liked = true, added = 1),
            t("De Música Ligera", "Soda Stereo", "Canción Animal", 4, "Rock", 1990, plays = 30, liked = true, added = 2),
            t("(En) El Séptimo Día", "Soda Stereo", "Canción Animal", 1, "Rock", 1990, plays = 4, added = 3),
            t("Persiana Americana", "Soda Stereo", "Signos", 3, "Rock", 1986, plays = 12, added = 4),
            t("Prófugos", "Soda Stereo", "Signos", 5, "Rock", 1986, added = 5),
            t("Zona de Promesas", "Soda Stereo", null, null, "Rock", 1993, added = 6),
            t("Crimen", "Gustavo Cerati", "Ahí Vamos", 6, "Pop", 2006, plays = 3, added = 7),
        )
        tracks.associate { it.title to library.insertTrack(it) }
    }

    @Test
    fun albumShowsNumberedTracksAndOpensTheSongSheet() {
        seed()
        compose.setContent { Host { AlbumScreen(AlbumRoute("Soda Stereo", "Canción Animal")) } }
        waitForText("De Música Ligera")
        assertShown("1990")
        assertShown("3 músicas")
        shot("album")

        // The shuffle toggle tells TalkBack whether it is on.
        val shuffle = compose.onNode(hasContentDescription("Aleatório") and isToggleable())
        shuffle.assertIsOff()
        shuffle.performClick()
        shuffle.assertIsOn()

        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Mais de Soda Stereo"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Signos"))
        assertShown("Signos")
        shot("album-more")

        // ⋮ of the first song (index 0 is the header's ⋮).
        compose.onAllNodesWithContentDescription("Mais opções")[1].performClick()
        waitForText("Tocar a seguir")
        assertShown("Ir para o artista")
        assertShown("Timer para dormir")
        assertShown("Apagar do celular")
        check(compose.onAllNodesWithText("Editar informações").fetchSemanticsNodes().isEmpty())
        shot("track-sheet")
    }

    @Test
    fun artistHasPopularAlbumsSinglesAndAllSongs() {
        seed()
        compose.setContent { Host { ArtistScreen(ArtistRoute("Soda Stereo")) } }
        waitForText("Populares")
        assertShown("6 músicas")
        assertShown("De Música Ligera")
        shot("artist")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Todas as músicas"))
        assertShown("Zona de Promesas")
        shot("artist-all")
    }

    @Test
    fun playlistShowsItsSongsAndTheImportBadge() {
        val ids = seed()
        val id = runBlocking {
            val pid = library.createPlaylist("Viagem", "Para a estrada", sourceUrl = "https://music.youtube.com/playlist?list=PLx")
            library.addToPlaylist(pid, listOf(ids.getValue("Crimen"), ids.getValue("Persiana Americana"), ids.getValue("Prófugos")))
            pid
        }
        compose.setContent { Host { PlaylistScreen(PlaylistRoute(id)) } }
        waitForText("Viagem")
        assertShown("Para a estrada")
        check(compose.onAllNodesWithText("Importada do YouTube").fetchSemanticsNodes().isEmpty())
        assertShown("Crimen")
        shot("playlist")

        compose.onAllNodesWithContentDescription("Mais opções").onFirst().performClick()
        waitForText("Organizar músicas")
        assertShown("Editar informações")
        assertShown("Apagar playlist")
        shot("playlist-menu")
    }

    @Test
    fun reorderModeDragsARowAndRemovesAnother() {
        val ids = seed()
        val order = listOf("Crimen", "Persiana Americana", "Prófugos", "Zona de Promesas").map { ids.getValue(it) }
        val pid = runBlocking { library.createPlaylist("Ordem").also { library.addToPlaylist(it, order) } }
        compose.setContent { Host { PlaylistScreen(PlaylistRoute(pid)) } }
        waitForText("Ordem")
        compose.onAllNodesWithContentDescription("Mais opções").onFirst().performClick()
        waitForText("Organizar músicas")
        compose.onAllNodesWithText("Organizar músicas").onFirst().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        waitForText("Concluir")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Zona de Promesas"))
        shot("playlist-reorder")

        // Drag "Crimen" (first row) down a bit more than two rows (rows are 64 dp, 3 px/dp here).
        compose.onAllNodesWithContentDescription("Arrastar para reordenar")[0].performTouchInput {
            down(center)
            repeat(30) { moveBy(Offset(0f, 14f)) }
            up()
        }
        val expected = listOf(order[1], order[2], order[0], order[3])
        compose.waitUntil(10_000) { runBlocking { library.playlist(pid).first()?.entries?.map { it.track.id } } == expected }

        // Remove the last row; the snackbar offers undo.
        compose.onAllNodesWithContentDescription("Remover")[3].performClick()
        compose.waitUntil(10_000) { runBlocking { library.playlist(pid).first()?.entries?.map { it.track.id } } == expected.take(3) }
        waitForText("Desfazer")
        shot("playlist-removed")
        compose.onAllNodesWithText("Desfazer").onFirst().performClick()
        compose.waitUntil(10_000) { runBlocking { library.playlist(pid).first()?.entries?.map { it.track.id } } == expected }
    }

    @Test
    fun emptyPlaylistExplainsHowToAddSongs() {
        val id = runBlocking { library.createPlaylist("Nova") }
        compose.setContent { Host { PlaylistScreen(PlaylistRoute(id)) } }
        waitForText("Playlist vazia")
        shot("playlist-empty")
    }

    @Test
    fun likedNewestFirst() {
        seed()
        compose.setContent { Host { LikedScreen() } }
        waitForText("Curtidas")
        assertShown("De Música Ligera")
        assertShown("2 músicas")
        shot("liked")
    }

    @Test
    fun likedEmpty() {
        compose.setContent { Host { LikedScreen() } }
        waitForText("Nada curtido ainda")
        shot("liked-empty")
    }

    @Test
    fun songsSortedByTitle() {
        seed()
        compose.setContent { Host { SongsScreen() } }
        waitForText("Todas as músicas")
        assertShown("7 músicas")
        assertShown("Título")
        assertShown("(En) El Séptimo Día")
        shot("songs")
    }

    @Test
    fun genrePage() {
        seed()
        compose.setContent { Host { GenreScreen(GenreRoute("rock")) } }
        waitForText("Rock")
        assertShown("Persiana Americana")
        shot("genre")
    }

    @Test
    fun unknownMixSaysNotFound() {
        compose.setContent { Host { MixScreen(MixRoute("does-not-exist")) } }
        waitForText("Mix não encontrado")
    }

    @Test
    fun addToPlaylistAddsAndShowsTheCheck() {
        val ids = seed()
        val pid = runBlocking { library.createPlaylist("Domingo") }
        val crimen = ids.getValue("Crimen")
        val song = runBlocking { library.getTrack(crimen) }!!
        compose.setContent { Host { AddToPlaylistSheet(tracks = listOf(song), onDismiss = {}) } }
        compose.mainClock.advanceTimeBy(1_000) // let the sheet slide in
        waitForText("Domingo")
        assertShown("Nova playlist")
        shot("add-to-playlist")
        compose.onAllNodesWithText("Domingo").onFirst().performClick()
        compose.waitUntil(10_000) {
            runBlocking { library.playlist(pid).first()?.entries?.map { it.track.id } } == listOf(crimen)
        }
        assertEquals(listOf(crimen), runBlocking { library.playlist(pid).first()!!.entries.map { it.track.id } })
    }

    @Test
    fun editDialogShowsEveryField() {
        val ids = seed()
        val track = runBlocking { library.getTrack(ids.getValue("Prófugos"))!! }
        compose.setContent { Host { EditTrackDialog(track, onDismiss = {}, onSave = {}) } }
        waitForText("Editar informações")
        listOf("Título", "Artista", "Álbum", "Artista do álbum", "Gênero", "Ano", "Nº da faixa").forEach { assertShown(it) }
        shot("edit")
    }
}
