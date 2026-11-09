package com.villamediana.sound.ui.recap

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.villamediana.sound.stats.Recap
import com.villamediana.sound.stats.RecapArtist
import com.villamediana.sound.stats.RecapChapter
import com.villamediana.sound.stats.RecapSong
import com.villamediana.sound.stats.RecapStoryPlan
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The retrospective as a story: every chapter tells its part, taps walk it, and (SOUND_SHOTS=1) a
 * print of each chapter. SOUND_FILM=1 renders the whole story frame by frame (build/recap-film) to
 * be put together as a video.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class RecapStoryTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val locale = java.util.Locale.getDefault()

    @org.junit.Before
    fun setUp() {
        com.villamediana.sound.testing.blockMediaSessionBinding()
        // Months, days and numbers as a Brazilian phone says them.
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-BR"))
    }

    @org.junit.After
    fun tearDown() = java.util.Locale.setDefault(locale)

    private val shots = System.getenv("SOUND_SHOTS") == "1"
    private val film = System.getenv("SOUND_FILM") == "1"

    /**
     * Covers stay the app's drawn placeholders here: decoding an image file off the main thread
     * aborts Robolectric's native graphics (JniConstants), so no files.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun cover(name: String, a: Int, b: Int): String? = null

    private fun recap(): Recap {
        val songs = listOf(
            RecapSong("v1", "Oceano", "Djavan", cover("c1", 0xFF1E88E5.toInt(), 0xFF00BFA5.toInt()), plays = 214, listenedMs = 52_000_000),
            RecapSong("v2", "Trem-Bala", "Ana Vilela", cover("c2", 0xFFFF7043.toInt(), 0xFFD81B60.toInt()), plays = 171, listenedMs = 40_000_000),
            RecapSong("v3", "Velha Infância", "Tribalistas", cover("c3", 0xFF8E24AA.toInt(), 0xFF3949AB.toInt()), plays = 140, listenedMs = 31_000_000),
            RecapSong("v4", "Partilhar", "Rubel", cover("c4", 0xFF43A047.toInt(), 0xFFFDD835.toInt()), plays = 118, listenedMs = 26_000_000),
            RecapSong("v5", "Garota de Ipanema", "Tom Jobim", cover("c5", 0xFFF4511E.toInt(), 0xFF6D4C41.toInt()), plays = 97, listenedMs = 19_000_000),
        )
        val artists = listOf(
            RecapArtist("Djavan", songs[0].cover, 380, 148_000_000),
            RecapArtist("Tribalistas", songs[2].cover, 260, 97_000_000),
            RecapArtist("Ana Vilela", songs[1].cover, 201, 72_000_000),
            RecapArtist("Rubel", songs[3].cover, 160, 55_000_000),
            RecapArtist("Tom Jobim", songs[4].cover, 120, 41_000_000),
        )
        return Recap(
            year = 2026,
            listenedMs = 48_211L * 60_000L,
            plays = 5_902,
            songs = 1_284,
            artists = 312,
            topSongs = songs,
            topArtists = artists,
            topGenre = "MPB",
            favoriteHour = 22,
            favoriteDay = DayOfWeek.FRIDAY,
            // ~803 h over the year, July the loudest.
            months = listOf(180, 150, 210, 260, 230, 300, 420, 360, 280, 310, 340, 390).map { it * 843_000L },
            daysListened = 287,
            longestStreak = 34,
            firstSong = songs[1],
            firstAt = LocalDateTime.of(2026, 1, 1, 8, 14).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
    }

    private fun shot(name: String) {
        if (!shots) return
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        save(File(System.getProperty("user.dir"), "build/sound-shots"), "$name.png")
    }

    private fun save(dir: File, name: String) {
        dir.mkdirs()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) {
        try {
            compose.waitUntil(5_000) { exists(text) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("\"$text\" never showed", e)
        }
    }

    @Test
    fun everyChapterTellsItsPart() {
        val r = recap()
        val chapters = RecapStoryPlan.chapters(r)
        assertEquals(RecapChapter.entries.toList(), chapters)
        val state = StoryState()
        compose.setContent { SoundTheme { RecapStory(r, onClose = {}, state = state, auto = false) } }
        val expected = mapOf(
            RecapChapter.INTRO to "2",
            RecapChapter.FIRST to "Trem-Bala",
            RecapChapter.TIME to "48.211",
            RecapChapter.MONTHS to "Julho",
            RecapChapter.RHYTHM to "noite",
            RecapChapter.GENRE to "MPB",
            RecapChapter.ARTIST to "Djavan",
            RecapChapter.ARTISTS to "Tribalistas",
            RecapChapter.SONG to "Oceano",
            RecapChapter.SONGS to "Garota de Ipanema",
            RecapChapter.OUTRO to "Ver de novo",
        )
        chapters.forEachIndexed { i, c ->
            compose.runOnIdle {
                state.go(i)
                // Late in the chapter: everything has arrived.
                state.t = if (c.advances) c.seconds - 0.6f else 6f
                state.clock = 20f + i
            }
            waitFor(expected.getValue(c))
            shot("recap-${i + 1}-${c.name.lowercase()}")
        }
    }

    @Test
    fun tapsWalkTheStoryAndTheEndTellsItAgain() {
        val r = recap()
        val state = StoryState()
        var closed = false
        compose.setContent { SoundTheme { RecapStory(r, onClose = { closed = true }, state = state, auto = false) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(width * 0.85f, height * 0.5f)) }
        compose.runOnIdle { assertEquals(1, state.index) }
        compose.onRoot().performTouchInput { click(Offset(width * 0.85f, height * 0.5f)) }
        compose.onRoot().performTouchInput { click(Offset(width * 0.1f, height * 0.5f)) }
        compose.runOnIdle { assertEquals(1, state.index) }
        // To the end, then "Ver de novo" starts over.
        compose.runOnIdle { state.go(RecapStoryPlan.chapters(r).lastIndex); state.t = 6f }
        waitFor("Ver de novo")
        compose.onAllNodesWithText("Ver de novo")[0].performClick()
        compose.runOnIdle { assertEquals(0, state.index) }
        compose.onAllNodesWithText("Fechar", substring = false).fetchSemanticsNodes() // the X has its description
        compose.onRoot().performTouchInput { click(Offset(width * 0.85f, height * 0.5f)) }
        compose.runOnIdle { assertEquals(1, state.index); assertEquals(false, closed) }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun theStoryAsAFilm() {
        if (!film) return
        val r = recap()
        val chapters = RecapStoryPlan.chapters(r)
        val state = StoryState()
        val fps = 12
        val dt = 1f / fps
        val dir = File(System.getProperty("user.dir"), "build/recap-film").apply { deleteRecursively(); mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent { SoundTheme { RecapStory(r, onClose = {}, state = state, auto = false) } }
        var frame = 0
        val outroFrames = (4.5f * fps).toInt()
        var outro = 0
        while (outro < outroFrames) {
            compose.runOnIdle {
                state.clock += dt
                state.t += dt
                val c = chapters[state.index]
                if (c.advances && state.t >= c.seconds) state.go(state.index + 1)
            }
            compose.mainClock.advanceTimeBy((1000L / fps))
            save(dir, "f%05d.png".format(frame++))
            if (!chapters[state.index].advances) outro++
        }
    }
}
