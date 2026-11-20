package com.villamediana.sound.ui.player

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LyricsLinesTest {
    @get:Rule val compose = createComposeRule()

    private fun lyrics(name: String, synced: Boolean) =
        ParsedLyrics(synced, (1..80).map { LyricLine(if (synced) it * 4_000L else 0L, "$name $it") })

    @Test
    fun translationsSitUnderTheirLines() {
        val song = ParsedLyrics(true, listOf(LyricLine(0, "I'm in the business of misery"), LyricLine(4_000, ""), LyricLine(8_000, "Let's take it from the top")))
        compose.setContent {
            SoundTheme {
                LyricsLines(
                    lyrics = song,
                    clock = PlaybackClock(),
                    onSeek = {},
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors = LyricColors.over(Color.Black),
                    translations = listOf("Estou no negócio da miséria", null, "Vamos começar do início"),
                )
            }
        }
        compose.onNodeWithText("I'm in the business of misery").assertExists()
        compose.onNodeWithText("Estou no negócio da miséria").assertExists()
        compose.onNodeWithText("Vamos começar do início").assertExists()
    }

    @Test
    fun theNextSongsLyricsStartAtTheirFirstLine() {
        var shown by mutableStateOf(lyrics("Aurora", synced = true))
        compose.setContent {
            SoundTheme {
                LyricsLines(
                    lyrics = shown,
                    clock = PlaybackClock(),
                    onSeek = {},
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors = LyricColors.over(Color.Black),
                )
            }
        }
        // The first song's lyrics were followed down to the middle.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        compose.onNodeWithText("Aurora 1").assertDoesNotExist()

        // Next song, plain lyrics (they don't follow the music): they must show from the top.
        shown = lyrics("Maré", synced = false)
        compose.waitForIdle()
        compose.onNodeWithText("Maré 1").assertExists()
    }

    /** Synced lyrics with line 50 being sung (lines are 4 s apart): the list keeps it near the top. */
    private fun showSungAtLine50(clock: PlaybackClock) {
        clock.onTick(200_000, 0L)
        compose.setContent {
            SoundTheme {
                LyricsLines(
                    lyrics = lyrics("Aurora", synced = true),
                    clock = clock,
                    onSeek = {},
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors = LyricColors.over(Color.Black),
                )
            }
        }
        compose.onNodeWithText("Aurora 50").assertExists()
    }

    /** The reader drags the list back to the first lines. */
    private fun dragBackToTheStart() {
        repeat(6) { compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() } }
        compose.onNodeWithText("Aurora 1").assertExists()
        compose.onNodeWithText("Aurora 50").assertDoesNotExist()
    }

    @Test
    fun pausedLyricsStayWhereTheReaderLeftThemUntilPlay() {
        val clock = PlaybackClock()
        showSungAtLine50(clock)

        // Paused: the reader can look around for as long as they like.
        dragBackToTheStart()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        compose.onNodeWithText("Aurora 1").assertExists()
        compose.onNodeWithText("Aurora 50").assertDoesNotExist()

        // Play brings the sung line back at once.
        compose.runOnIdle { clock.onState(playing = true, durationMs = 320_000, nowNanos = 0L) }
        compose.waitForIdle()
        compose.onNodeWithText("Aurora 50").assertExists()
        compose.onNodeWithText("Aurora 1").assertDoesNotExist()
    }

    @Test
    fun playingLyricsComeBackToTheSungLineAFewSecondsAfterADrag() {
        val clock = PlaybackClock().apply { onState(playing = true, durationMs = 320_000, nowNanos = 0L) }
        showSungAtLine50(clock)

        dragBackToTheStart()
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        compose.onNodeWithText("Aurora 50").assertExists()
        compose.onNodeWithText("Aurora 1").assertDoesNotExist()
    }

    @Test
    fun pausingRightAfterADragKeepsTheListWhereTheReaderLeftIt() {
        val clock = PlaybackClock().apply { onState(playing = true, durationMs = 320_000, nowNanos = 0L) }
        showSungAtLine50(clock)

        dragBackToTheStart()
        compose.runOnIdle { clock.onState(playing = false, durationMs = 320_000, nowNanos = 0L) }
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        compose.onNodeWithText("Aurora 1").assertExists()
        compose.onNodeWithText("Aurora 50").assertDoesNotExist()
    }
}
