package com.villamediana.surco.ui.player

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
import com.villamediana.surco.data.LyricLine
import com.villamediana.surco.data.ParsedLyrics
import com.villamediana.surco.ui.theme.SurcoTheme
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
    fun theNextSongsLyricsStartAtTheirFirstLine() {
        var shown by mutableStateOf(lyrics("Aurora", synced = true))
        compose.setContent {
            SurcoTheme {
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
