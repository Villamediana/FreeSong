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
import com.villamediana.sound.chords.Chord
import com.villamediana.sound.chords.ChordChart
import com.villamediana.sound.chords.ChordEngine
import com.villamediana.sound.chords.ChordStore
import com.villamediana.sound.chords.Fingerings
import com.villamediana.sound.chords.Instrument
import com.villamediana.sound.chords.MusicKey
import com.villamediana.sound.chords.SheetChord
import com.villamediana.sound.chords.SheetLine
import com.villamediana.sound.chords.SheetLookup
import com.villamediana.sound.chords.TimedChord
import com.villamediana.sound.chords.WrittenSheet
import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.data.ThemeMode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** "Tocar junto": the lyrics as a stage, with PNGs in build/screenshots for a look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayAlongStageTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val song = RemoteTrack("playalong", "https://music.youtube.com/watch?v=playalong", "Garota de Ipanema", "Tom Jobim", null, 200_000, fromMusicCatalog = true).asTransientTrack()

    private fun heard(): ChordChart {
        val names = listOf(0L to "G", 4_000L to "D", 8_000L to "Em", 10_000L to "C", 12_000L to "G", 14_000L to "D", 16_000L to "Am7", 20_000L to "D7", 24_000L to "G")
        return ChordChart(
            names.mapIndexed { i, (t, n) ->
                val c = Chord.parse(n)!!
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

    private val written = ChordChart(
        emptyList(),
        MusicKey(7, false),
        sheet = WrittenSheet(
            lines = listOf(
                SheetLine("", listOf(SheetChord(0, "G7M"), SheetChord(5, "A7(13)"))),
                SheetLine("Olha que coisa mais linda, mais cheia de graça", listOf(SheetChord(0, "G7M"), SheetChord(21, "A7(13)"))),
                SheetLine("É ela a menina que vem e que passa", listOf(SheetChord(0, "Am7(9)"), SheetChord(20, "Ab7(b5)"))),
                SheetLine("Num doce balanço a caminho do mar", listOf(SheetChord(4, "G7M"), SheetChord(22, "Ab7(b5)"))),
            ),
            source = "Cifra Club",
            url = "https://www.cifraclub.com.br/tom-jobim/garota-de-ipanema/",
            capo = 2,
        ),
        looked = true,
    )

    private val instrument = MutableStateFlow(Instrument.VIOLAO)
    private val playAlong = MutableStateFlow(true)

    private fun deps(chart: ChordChart, sheet: ChordChart? = null) = ChordsDeps(
        engine = ChordEngine(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            store = ChordStore(tmp.newFolder()),
            worker = Dispatchers.Main,
            analyze = { _, _ -> chart },
            io = Dispatchers.Main,
            find = sheet?.let { s -> { _ -> SheetLookup.Found(s) } },
        ),
        fingerings = Fingerings { name -> File("src/main/assets/$name").takeIf { it.isFile }?.readText() },
        enabled = MutableStateFlow(true),
        instrument = instrument,
        setEnabled = {},
        setInstrument = { instrument.value = it },
        playAlong = playAlong,
        setPlayAlong = { playAlong.value = it },
    )

    private fun show(deps: ChordsDeps, positionMs: Long = 13_000, theme: ThemeMode = ThemeMode.DARK, words: ParsedLyrics = lyrics) {
        val clock = PlaybackClock().apply { onTick(positionMs, 0L) }
        compose.setContent {
            SoundTheme(mode = theme) {
                CompositionLocalProvider(LocalChords provides deps) {
                    LyricsScreen(
                        track = song,
                        lyrics = LyricsState.Ready(words),
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

    private fun shot(name: String, dialog: Boolean = false) {
        runCatching {
            val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
            val bitmap = node.captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    @Test fun theChordRingingNowTheNextAndTheLine() {
        show(deps(heard()))
        compose.onNodeWithTag("playAlong").assertExists()
        // At 13 s: G rings (12–14 s), D comes next; the lane is there.
        compose.onNodeWithContentDescription("Sol maior", substring = true).assertExists()
        compose.onNodeWithContentDescription("Ré maior", substring = true).assertExists()
        compose.onNodeWithContentDescription("Acordes no tempo da música").assertExists()
        // The line being sung, with its chords.
        compose.onNodeWithContentDescription("É ela a menina que vem e que passa").assertExists()
        shot("play_along_violao")

        // The chord ringing opens how to play it.
        compose.onNodeWithContentDescription("Sol maior", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chordSheetName").assertExists()
        compose.onNodeWithText("Ukulele").performClick()
        compose.waitForIdle()
        shot("play_along_sheet", dialog = true)
    }

    @Test fun ukuleleAndPiano() {
        instrument.value = Instrument.UKULELE
        show(deps(heard()), positionMs = 17_000)
        compose.onNodeWithTag("playAlong").assertExists()
        shot("play_along_ukulele")
        instrument.value = Instrument.PIANO
        compose.waitForIdle()
        shot("play_along_piano")
    }

    @Test fun beforeTheFirstChordItWaits() {
        show(deps(heard()), positionMs = 0)
        compose.onNodeWithTag("playAlong").assertExists()
        shot("play_along_start")
    }

    @Test fun lightTheme() {
        show(deps(heard()), theme = ThemeMode.LIGHT)
        compose.onNodeWithTag("playAlong").assertExists()
        shot("play_along_light")
    }

    @Test fun aWrittenSheetIsPlayedAlongByItsWords() {
        show(deps(heard(), sheet = written), positionMs = 10_500)
        compose.onNodeWithTag("playAlong").assertExists()
        compose.onAllNodesWithText("Am7(9)").onFirst().assertExists()
        // The capo and the key stay by the play button.
        compose.onNodeWithText("CAPOTRASTE").assertExists()
        shot("play_along_sheet_written")
    }

    @Test fun theButtonTurnsItOnAndOff() {
        playAlong.value = false
        show(deps(heard()))
        compose.onNodeWithTag("playAlong").assertDoesNotExist()
        compose.onNodeWithTag("chordLyrics").assertExists()
        compose.onNodeWithContentDescription("Tocar junto").performClick()
        compose.waitForIdle()
        assertTrue(playAlong.value)
        compose.onNodeWithTag("playAlong").assertExists()
        compose.onNodeWithContentDescription("Voltar à letra").performClick()
        compose.waitForIdle()
        assertFalse(playAlong.value)
        compose.onNodeWithTag("playAlong").assertDoesNotExist()
    }

    @Test fun aSheetOverLyricsWithoutTimesCantBePlayedAlong() {
        // Nothing to follow: no button, and the chords stay over the words as written.
        val plain = ParsedLyrics(false, lyrics.lines.map { LyricLine(0, it.text) })
        show(deps(heard(), sheet = written), words = plain)
        compose.onNodeWithTag("playAlong").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tocar junto").assertDoesNotExist()
        compose.onNodeWithTag("chordLyrics").assertExists()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun lyingDown() {
        show(deps(heard()))
        compose.onNodeWithTag("playAlong").assertExists()
        shot("play_along_landscape")
    }
}
