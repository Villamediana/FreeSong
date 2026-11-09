package com.villamediana.sound.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.villamediana.sound.discover.HomeMixes
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Home's live part without crowding: the discoveries as songs, then ONE row of artist cards
 * ("Porque você curte") and ONE row of genre cards ("Seus gêneros"), each card a mix that opens its
 * list or plays. SOUND_SHOTS=1 writes the print.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1400dp-xxhdpi")
class HomeMixRowsTest {
    @get:Rule val compose = createComposeRule()

    private fun songs(prefix: String, vararg artists: String) =
        (1..12).map { i -> RemoteTrack("$prefix$i", "https://music.youtube.com/watch?v=$prefix$i", "Song $prefix$i", artists[i % artists.size]) }

    private fun mix(id: String, title: String, kind: Mix.Kind, tracks: List<RemoteTrack>, except: String? = null) =
        HomeMixes.build(id, title, tracks, kind, subtitle = { "$it e mais" }, except = except)

    @Test
    fun becauseAndGenresAreOneRowOfMixCardsEach() {
        val state = DiscoverUiState(
            ready = true,
            chosen = true,
            discoveries = songs("d", "Rubel", "Anavitória"),
            because = listOf(
                mix("home:because:mana", "Maná", Mix.Kind.ARTIST, songs("m", "Maná", "Juanes", "Shakira"), except = "Maná"),
                mix("home:because:djavan", "Djavan", Mix.Kind.ARTIST, songs("j", "Djavan", "Caetano"), except = "Djavan"),
            ),
            genres = listOf(
                mix("home:genre:latino", "Latino hoje", Mix.Kind.GENRE, songs("l", "Bad Bunny", "Karol G")),
                mix("home:genre:80s", "Anos 80 hoje", Mix.Kind.GENRE, songs("o", "a-ha", "Queen")),
            ),
        )
        val opened = mutableListOf<String>()
        val played = mutableListOf<String>()
        compose.setContent {
            SoundTheme {
                LazyColumn(Modifier.background(SoundColors.Ink)) {
                    discoverSections(
                        state, PlaybackState(), onPlay = { _, _, _, _ -> }, onEdit = {}, onSongMenu = {},
                        onOpenMix = { opened += it.id }, onPlayMix = { played += it.id },
                    )
                }
            }
        }
        compose.waitForIdle()
        fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
        // One title per row, not one row per artist or genre.
        assertEquals(1, count("Descobertas pra você"))
        assertEquals(1, count("Porque você curte"))
        assertEquals(1, count("Seus gêneros"))
        assertEquals(0, compose.onAllNodesWithText("Porque você curte Maná").fetchSemanticsNodes().size)
        // The artist's card names the others of its mix, not the artist again.
        assertEquals(1, count("Juanes, Shakira e mais"))
        assertEquals(1, count("Latino hoje"))
        compose.onAllNodesWithText("Latino hoje")[0].performClick()
        assertEquals(listOf("home:genre:latino"), opened)
        compose.onAllNodesWithContentDescription("Tocar")[0].performClick()
        assertTrue(played.single().startsWith("home:because:"))
        if (System.getenv("SOUND_SHOTS") == "1") {
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
            File(dir, "home-mix-rows.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun aMixCardHasFourArtistsOnItsArtAndListsItsSongs() {
        val m = mix("home:because:mana", "Maná", Mix.Kind.ARTIST, songs("m", "Maná", "Juanes", "Shakira", "Fonseca", "Reik"), except = "Maná")
        assertEquals(12, m.tracks.size)
        assertTrue(m.tracks.all { it.id < 0 && it.videoId!!.startsWith("m") }) // streamed songs
        assertEquals("Juanes, Shakira, Fonseca e mais", m.subtitle)
        assertEquals(0, m.covers.size) // no thumbnails in the fixture: the card draws its own art
    }
}
