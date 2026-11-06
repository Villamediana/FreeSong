package com.villamediana.surco.ui.home

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
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.smart.Mix
import com.villamediana.surco.ui.library.LibraryScreen
import com.villamediana.surco.ui.nav.LocalNavigator
import com.villamediana.surco.ui.nav.Navigator
import com.villamediana.surco.ui.settings.SettingsScreen
import com.villamediana.surco.ui.theme.LocalBottomInset
import com.villamediana.surco.ui.theme.SurcoColors
import com.villamediana.surco.ui.theme.SurcoTheme
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
 * compose, load and show their key content without crashing. With SURCO_SHOTS=1 it also writes
 * PNGs to app/build/surco-shots/ to eyeball the layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class HomeScreensSmokeTest {
    @get:Rule val compose = createComposeRule()

    private val shots = System.getenv("SURCO_SHOTS") == "1"

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val controller = rememberNavController()
        val navigator = remember(controller) { Navigator(controller) }
        SurcoTheme {
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
        val dir = File(System.getProperty("user.dir"), "build/surco-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun emptyHomeWelcomes() {
        compose.setContent { Host { HomeScreen() } }
        waitForText("Busque, baixe e ouça sem internet")
        compose.onAllNodesWithText("Buscar músicas").fetchSemanticsNodes().let { check(it.isNotEmpty()) }
        shot("home-empty")
    }

    @Test
    fun homeShowsTheLibrary() {
        seed()
        compose.setContent { Host { HomeScreen() } }
        waitForText("Aleatório inteligente")
        waitForText("Curtidas")
        waitForText("Tocadas recentemente")
        shot("home")
        compose.onAllNodes(hasScrollToKeyAction())[0].performScrollToKey("artists")
        waitForText("Seus artistas")
        waitForText("Por gênero")
        shot("home-bottom")
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
        shot("library-albums")
        compose.onAllNodesWithText("Músicas")[0].performClick()
        waitForText("Todas as músicas")
        shot("library-songs")
    }

    @Test
    fun settingsShowEverything() {
        seed()
        compose.setContent { Host { SettingsScreen() } }
        waitForText("Qualidade do áudio")
        waitForText("Máxima")
        shot("settings")
        compose.onAllNodes(hasScrollToKeyAction())[0].performScrollToKey("about")
        waitForText("NewPipeExtractor")
        waitForText("músicas")
        shot("settings-bottom")
    }

    @Test
    fun mixCardsRender() {
        val app = ApplicationProvider.getApplicationContext<SurcoApplication>()
        val covers = listOf(0xFFB5432A, 0xFF2F5D8C, 0xFFE4B363, 0xFF3D7A5E).mapIndexed { i, c -> cover(app.cacheDir, "m$i", c.toInt()) }
        val mixes = listOf(
            Mix("genre:mpb", "Seu MPB", "Tribalistas, Elis Regina e mais", Mix.Kind.GENRE, emptyList(), covers, 0xFF3D7A5E.toInt()),
            Mix("on-repeat", "No repeat", "O que você não para de ouvir", Mix.Kind.ON_REPEAT, emptyList(), covers.take(1), null),
        )
        compose.setContent {
            Host {
                Row(Modifier.background(SurcoColors.Ink).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    mixes.forEachIndexed { i, m -> MixCard(m, playing = i == 1, onOpen = {}, onPlay = {}) }
                }
            }
        }
        waitForText("Seu MPB")
        waitForText("No repeat")
        shot("mix-cards")
    }

    // ------------------------------------------------------------------ data

    private fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SurcoApplication>()
        val lib = SurcoApplication.graph.library
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
