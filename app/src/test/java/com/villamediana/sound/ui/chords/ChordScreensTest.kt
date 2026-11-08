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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    private fun deps(chart: ChordChart?, find: (suspend (com.villamediana.sound.chords.ChordSubject) -> com.villamediana.sound.chords.SheetLookup)? = null): ChordsDeps {
        val engine = ChordEngine(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            store = ChordStore(tmp.newFolder()),
            worker = Dispatchers.Main,
            analyze = { _, _ -> heard++; chart ?: ChordChart(emptyList()) },
            io = Dispatchers.Main,
            find = find,
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

        // Set aside for now: Violão · Ukulele · Piano.
        compose.onNodeWithText("Cavaquinho").assertDoesNotExist()

        compose.onNodeWithText("Piano").performClick()
        compose.waitForIdle()
        assertEquals(Instrument.PIANO, instrument.value)
        shotDialog("chords_sheet_piano")

        // Another chord of the song, from the row at the bottom.
        compose.onAllNodesWithText("D7").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ré com sétima").assertExists()
    }

    @Test fun aPlayersSheetComesFirstOverTheSameWords() {
        // As a sheet writes it: the first two lines as one, chords over syllables, a capo.
        val sheet = com.villamediana.sound.chords.WrittenSheet(
            lines = listOf(
                com.villamediana.sound.chords.SheetLine("", listOf(com.villamediana.sound.chords.SheetChord(0, "G7M"), com.villamediana.sound.chords.SheetChord(5, "A7(13)"))),
                com.villamediana.sound.chords.SheetLine(
                    "Olha que coisa mais linda, mais cheia de graça É ela a menina que vem e que passa",
                    listOf(
                        com.villamediana.sound.chords.SheetChord(0, "G7M"), com.villamediana.sound.chords.SheetChord(21, "A7(13)"),
                        com.villamediana.sound.chords.SheetChord(48, "Am7(9)"), com.villamediana.sound.chords.SheetChord(66, "Ab7(b5)"),
                    ),
                ),
                com.villamediana.sound.chords.SheetLine("Num doce balanço a caminho do mar", listOf(com.villamediana.sound.chords.SheetChord(4, "G7M"), com.villamediana.sound.chords.SheetChord(22, "Ab7(b5)"))),
            ),
            source = "Cifra Club",
            url = "https://www.cifraclub.com.br/tom-jobim/garota-de-ipanema/",
            capo = 2,
        )
        val written = ChordChart(emptyList(), MusicKey(7, false), sheet = sheet, looked = true)
        show(deps(chart(), find = { com.villamediana.sound.chords.SheetLookup.Found(written) }))
        assertEquals(0, heard)
        compose.onNodeWithTag("chordLyrics").assertExists()
        // No source label (Miguel: not needed); the capo and the key are, by the play button.
        compose.onNodeWithText("Cifra Club", substring = true).assertDoesNotExist()
        compose.onNodeWithText("CAPOTRASTE").assertExists()
        compose.onNodeWithText("2ª casa").assertExists()
        compose.onNodeWithText("TOM").assertExists()
        compose.onNodeWithContentDescription("Tom G").assertExists()
        compose.onAllNodesWithText("Am7(9)").onFirst().assertExists()
        shot("chords_written_sheet")

        // A whole step up: every name moves, the extras kept.
        compose.onNodeWithContentDescription("Meio tom acima").performClick()
        compose.onNodeWithContentDescription("Meio tom acima").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Tom A +2").assertExists()
        compose.onNodeWithText("A+2").assertExists()
        compose.onAllNodesWithText("A7M").onFirst().assertExists()
        compose.onAllNodesWithText("Bm7(9)").onFirst().assertExists()
        compose.onAllNodesWithText("Am7(9)").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        shot("chords_transposed")
        // A tap on the key brings it back.
        compose.onNodeWithContentDescription("Tom A +2").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Tom G").assertExists()
        compose.onAllNodesWithText("Am7(9)").onFirst().assertExists()
        compose.onAllNodesWithText("A7(13)").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lá com sétima e décima terceira").assertExists()
        shotDialog("chords_written_sheet_shape")
    }

    @Test fun chordsAndTranslationTogether() {
        val d = deps(chart())
        val translations = mapOf(
            "Olha que coisa mais linda, mais cheia de graça" to "Look, what a beautiful thing, so full of grace",
            "É ela a menina que vem e que passa" to "It's her, the girl who comes and goes by",
        )
        compose.setContent {
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.DARK) {
                CompositionLocalProvider(LocalChords provides d) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(Color(0xFF1F2B38))) {
                        ChordLyricsLines(
                            lyrics = lyrics,
                            chart = chart(),
                            durationMs = 200_000,
                            clock = PlaybackClock().apply { onTick(13_000, 0L) },
                            onSeek = {},
                            onChord = {},
                            textStyle = androidx.compose.material3.MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 35.sp),
                            colors = com.villamediana.sound.ui.player.LyricColors.over(Color(0xFF1F2B38)),
                            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                            translations = translations,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("It's her, the girl who comes and goes by").assertExists()
        compose.onAllNodesWithText("Am7").onFirst().assertExists()
        shot("chords_translated")
    }

    @Test fun aShortLineUnderManyChordsNeverStacksThem() = crowdedLine(fontScale = 1f, name = "chords_crowded_line")

    /** Bigger type (the phone's font size): the six don't fit across and are set smaller, still one row. */
    @Test fun aShortLineUnderManyChordsStaysOneRowWhenTheyDontFit() = crowdedLine(fontScale = 1.3f, name = "chords_crowded_line_big")

    private fun crowdedLine(fontScale: Float, name: String) {
        // Sparks (Coldplay) on the phone: "I say, oh" under six chords; the first ones landed on one another.
        val names = listOf("E7M(6/9)", "Em", "Em7", "F6(9/11+)", "C#m7(5-/9)", "C7M(9)")
        val sheet = com.villamediana.sound.chords.WrittenSheet(
            lines = listOf(
                com.villamediana.sound.chords.SheetLine("I say, oh", names.mapIndexed { i, n -> com.villamediana.sound.chords.SheetChord(10 + i * 11, n) }),
                com.villamediana.sound.chords.SheetLine("My heart is yours", listOf(com.villamediana.sound.chords.SheetChord(0, "G7M"), com.villamediana.sound.chords.SheetChord(9, "Em7"))),
                com.villamediana.sound.chords.SheetLine("It's you that I hold on to", listOf(com.villamediana.sound.chords.SheetChord(0, "G7M"), com.villamediana.sound.chords.SheetChord(16, "Em7"), com.villamediana.sound.chords.SheetChord(24, "Am9"))),
            ),
            source = "Cifra Club",
            url = "https://www.cifraclub.com.br/coldplay/sparks/",
            capo = 6,
        )
        val sparks = ParsedLyrics(true, listOf(LyricLine(2_000, "I say, oh"), LyricLine(6_000, "My heart is yours"), LyricLine(10_000, "It's you that I hold on to")))
        val chart = ChordChart(emptyList(), MusicKey(0, false), sheet = sheet, looked = true)
        val d = deps(chart)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.DARK) {
                CompositionLocalProvider(LocalChords provides d, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(Color(0xFF4A3424))) {
                        ChordLyricsLines(
                            lyrics = sparks,
                            chart = chart,
                            durationMs = 227_000,
                            clock = PlaybackClock().apply { onTick(2_500, 0L) },
                            onSeek = {},
                            onChord = {},
                            textStyle = androidx.compose.material3.MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            colors = com.villamediana.sound.ui.player.LyricColors.over(Color(0xFF4A3424)),
                            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                            horizontalPadding = 28.dp,
                            lineGap = 18.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        shot(name)
        val boxes = names.map { compose.onAllNodesWithText(it).onFirst().fetchSemanticsNode().boundsInRoot }
        for (i in boxes.indices) for (j in 0 until i) {
            assertTrue("${names[j]} and ${names[i]} overlap: ${boxes[j]} ${boxes[i]}", !boxes[i].overlaps(boxes[j]))
        }
        // All read above the words of their line (one 35 sp line at xxhdpi, the chords tucked 3 dp into it).
        val words = compose.onNodeWithContentDescription("I say, oh").fetchSemanticsNode().boundsInRoot
        val wordsTop = words.bottom - 35 * 3f * fontScale
        boxes.forEach { assertTrue("$it sits on the words ($words)", it.bottom <= wordsTop + 12f) }
        // One row, read left to right.
        for (i in 1 until boxes.size) {
            assertEquals(boxes[0].bottom, boxes[i].bottom, 1f)
            assertTrue("${names[i]} reads before ${names[i - 1]}", boxes[i].left >= boxes[i - 1].right)
        }
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

    @Test fun lyricsWithoutTimesKeepTheWordsUnderTheChords() {
        // Only plain lyrics exist for some recordings: the words stay, the chords at a guessed place.
        val plain = ParsedLyrics(
            false,
            listOf(
                "El confesor me dice que no te quiera,", "y yo le digo padre si usted la viera", "Dice que tus amores me vuelven loco",
                "que a mi deber no atiendo", "que duermo poco... que duermo poco.", "", "Dice que nuestras muchas conversaciones",
                "son las que me tienen así", "que ya no tengo salvación",
            ).map { LyricLine(0, it) } + (1..35).map { LyricLine(0, "verso $it de una letra que no marca el tiempo") },
        )
        val names = listOf("Dm", "Bb", "Gm7", "F", "Eb", "Bb", "F", "Gm")
        val chart = ChordChart(
            (0 until 60).map { k -> val c = com.villamediana.sound.chords.Chord.parse(names[k % names.size])!!; TimedChord(k * 3_500L, (k + 1) * 3_500L, c.root, c.quality) },
            key = MusicKey(10, false),
            bpm = 103.0,
        )
        val d = deps(chart)
        val clock = PlaybackClock().apply { onTick(37_000, 0L) }
        compose.setContent {
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.DARK) {
                CompositionLocalProvider(LocalChords provides d) {
                    LyricsScreen(song, LyricsState.Ready(plain), clock, mutableStateOf(Color(0xFF3F3E26)), Color(0xFF3F3E26), true, 248_000, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chordLyrics").assertExists()
        // At 0:37 the list follows the guessed line: words on screen, under their chords.
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("que", substring = true)).fetchSemanticsNodes().isNotEmpty())
        shot("chords_plain_lyrics")
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
