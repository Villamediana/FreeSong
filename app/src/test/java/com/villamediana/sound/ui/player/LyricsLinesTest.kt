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
}
