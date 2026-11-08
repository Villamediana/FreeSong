package com.villamediana.sound.ui.chords

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.villamediana.sound.chords.ChordChart
import com.villamediana.sound.chords.ChordEngine
import com.villamediana.sound.chords.ChordQuality
import com.villamediana.sound.chords.ChordStore
import com.villamediana.sound.chords.Fingerings
import com.villamediana.sound.chords.Instrument
import com.villamediana.sound.chords.MusicKey
import com.villamediana.sound.chords.TimedChord
import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.ui.player.LyricsScreen
import com.villamediana.sound.ui.player.LyricsState
import com.villamediana.sound.ui.player.PlaybackClock
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The chords on the lyrics screen and the chord sheet, with PNGs in build/screenshots for a look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChordScreensTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val song = RemoteTrack("chordsvideo", "https://music.youtube.com/watch?v=chordsvideo", "Garota de Ipanema", "Tom Jobim", null, 200_000, fromMusicCatalog = true).asTransientTrack()

    private fun chart(): ChordChart {
        val names = listOf(0L to "G", 4_000L to "D", 8_000L to "Em", 10_000L to "C", 12_000L to "G", 14_000L to "D", 16_000L to "Am7", 20_000L to "D7", 24_000L to "G")
        return ChordChart(
            names.mapIndexed { i, (t, n) ->
                val c = com.villamediana.sound.chords.Chord.parse(n)!!
                TimedChord(t, names.getOrNull(i + 1)?.first ?: 30_000, c.root, c.quality, c.bass)
            },
            key = MusicKey(7, false),
            bpm = 120.0,
        )
    }

    private val lyrics = ParsedLyrics(
        true,
        listOf(
            LyricLine(8_000, "Olha que coisa mais linda, mais cheia de graça"),
            LyricLine(12_000, "É ela a menina que vem e que passa"),
            LyricLine(16_000, "Num doce balanço a caminho do mar"),
            LyricLine(24_000, ""),
        ),
    )

    private val enabled = MutableStateFlow(true)
    private val instrument = MutableStateFlow(Instrument.VIOLAO)
    private var heard = 0

    private fun deps(chart: ChordChart?): ChordsDeps {
        val engine = ChordEngine(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            store = ChordStore(tmp.newFolder()),
            worker = Dispatchers.Main,
            analyze = { _, _ -> heard++; chart ?: ChordChart(emptyList()) },
            io = Dispatchers.Main,
        )
        return ChordsDeps(
            engine = engine,
            fingerings = Fingerings { name -> File("src/main/assets/$name").takeIf { it.isFile }?.readText() },
            enabled = enabled,
            instrument = instrument,
            setEnabled = { enabled.value = it },
            setInstrument = { instrument.value = it },
        )
    }

    private fun show(deps: ChordsDeps, positionMs: Long = 13_000) {
        val clock = PlaybackClock().apply { onTick(positionMs, 0L) }
        compose.setContent {
            // The app's default look: dark.
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.DARK) {
                CompositionLocalProvider(LocalChords provides deps) {
                    LyricsScreen(
                        track = song,
                        lyrics = LyricsState.Ready(lyrics),
                        clock = clock,
                        wash = mutableStateOf(Color(0xFF3A5F7A)),
                        washTarget = Color(0xFF3A5F7A),
                        playing = true,
                        durationMs = 200_000,
                        onSeek = {},
                        onTogglePlay = {},
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    private fun shotDialog(name: String) {
        runCatching {
            val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    @Test fun chordsOverTheLyricsAndHowToPlayThem() {
        show(deps(chart()))
        compose.onNodeWithTag("chordLyrics").assertExists()
        // The intro (G D) and the lines' chords.
        compose.onAllNodesWithText("Em").onFirst().assertExists()
        compose.onAllNodesWithText("Am7").onFirst().assertExists()
        shot("chords_lyrics")

        compose.onAllNodesWithText("Am7").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chordSheetName").assertExists()
        compose.onNodeWithText("Lá menor com sétima").assertExists()
        shotDialog("chords_sheet_violao")

        compose.onNodeWithText("Ukulele").performClick()
        compose.waitForIdle()
        assertEquals(Instrument.UKULELE, instrument.value)
        shotDialog("chords_sheet_ukulele")

        compose.onNodeWithText("Cavaquinho").performClick()
        compose.waitForIdle()
        shotDialog("chords_sheet_cavaquinho")

        compose.onNodeWithText("Piano").performClick()
        compose.waitForIdle()
        assertEquals(Instrument.PIANO, instrument.value)
        shotDialog("chords_sheet_piano")

        // Another chord of the song, from the row at the bottom.
        compose.onAllNodesWithText("D7").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ré com sétima").assertExists()
    }

    @Test fun theButtonTurnsChordsOnAndOff() {
        enabled.value = false
        show(deps(chart()))
        // Off (the default): the lyrics open, and no song is heard until the button is touched.
        assertEquals(0, heard)
        compose.onNodeWithContentDescription("Mostrar acordes").performClick()
        compose.waitForIdle()
        assertTrue(enabled.value)
        compose.onNodeWithTag("chordLyrics").assertExists()
        compose.onNodeWithContentDescription("Esconder acordes").performClick()
        compose.waitForIdle()
        assertTrue(!enabled.value)
        compose.onNodeWithTag("chordLyrics").assertDoesNotExist()
    }

    @Test fun thePlayersLyricsCardNeverHearsASongItself() {
        // Chords on, but only the full player open: nothing runs (the lyrics screen and the button are the ask).
        val d = deps(chart())
        compose.setContent {
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.DARK) {
                CompositionLocalProvider(LocalChords provides d) {
                    com.villamediana.sound.ui.player.LyricsCard(
                        lyrics = LyricsState.Ready(lyrics),
                        clock = PlaybackClock().apply { onTick(13_000, 0L) },
                        wash = mutableStateOf(Color(0xFF3A5F7A)),
                        washTarget = Color(0xFF3A5F7A),
                        onSeek = {},
                        onOpen = {},
                        track = song,
                        durationMs = 200_000,
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, heard)
        compose.onNodeWithTag("chordLyrics").assertDoesNotExist()
    }

    @Test fun nothingHeardSaysSo() {
        show(deps(null))
        compose.onNodeWithText("Nenhum acorde nesta música.").assertExists()
        // The plain lyrics stay.
        compose.onNodeWithText("Num doce balanço a caminho do mar").assertExists()
    }

    @Test fun lightThemeToo() {
        val d = deps(chart())
        val clock = PlaybackClock().apply { onTick(9_000, 0L) }
        compose.setContent {
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalChords provides d) {
                    LyricsScreen(song, LyricsState.Ready(lyrics), clock, mutableStateOf(Color(0xFFB0563A)), Color(0xFFB0563A), true, 200_000, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chordLyrics").assertExists()
        shot("chords_lyrics_light")
        compose.onAllNodesWithText("C").onFirst().performClick()
        compose.waitForIdle()
        shotDialog("chords_sheet_light")
    }
}
