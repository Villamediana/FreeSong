package com.villamediana.sound.ui.karaoke

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.villamediana.sound.chords.ChordSubject
import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.karaoke.KaraokeState
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.ui.player.LyricsScreen
import com.villamediana.sound.ui.player.LyricsState
import com.villamediana.sound.ui.player.PlaybackClock
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The microphone by the lyrics and what it says while a song's voice is on its way out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KaraokeUiTest {
    @get:Rule val compose = createComposeRule()

    private val song = RemoteTrack("karaokevid", "https://music.youtube.com/watch?v=karaokevid", "Evidências", "Chitãozinho & Xororó", null, 280_000, fromMusicCatalog = true).asTransientTrack()
    private val lyrics = ParsedLyrics(true, listOf(LyricLine(8_000, "Quando eu digo que deixei de te amar"), LyricLine(12_000, "É porque eu te amo")))
    private val on = MutableStateFlow(false)
    private val state = MutableStateFlow<KaraokeState>(KaraokeState.Idle)
    private val asked = ArrayList<String>()
    private var retries = 0

    private fun show(deps: KaraokeDeps?) {
        val clock = PlaybackClock().apply { onTick(9_000, 0L) }
        compose.setContent {
            SoundTheme(mode = com.villamediana.sound.data.ThemeMode.DARK) {
                CompositionLocalProvider(LocalKaraoke provides deps) {
                    LyricsScreen(
                        track = song,
                        lyrics = LyricsState.Ready(lyrics),
                        clock = clock,
                        wash = mutableStateOf(Color(0xFF3A5F7A)),
                        washTarget = Color(0xFF3A5F7A),
                        playing = true,
                        durationMs = 280_000,
                        onSeek = {},
                        onTogglePlay = {},
                        onClose = {},
                        onKaraokeRetry = { retries++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun deps(supported: Boolean = true) = KaraokeDeps(on, { on.value = it }, { key -> asked += key; state }, supported)

    @Test fun `the microphone turns the karaoke on and off`() {
        show(deps())
        compose.onNodeWithContentDescription("Karaokê: tirar a voz").performClick()
        compose.waitForIdle()
        assertEquals(true, on.value)
        // The song's own state is followed (the same key as its chords).
        assertEquals(ChordSubject.of(song)!!.key, asked.last())
        compose.onNodeWithContentDescription("Karaokê: voltar a voz").performClick()
        compose.waitForIdle()
        assertEquals(false, on.value)
        // The words stay as they are either way.
        compose.onNodeWithText("É porque eu te amo").assertExists()
    }

    @Test fun `while the voice is on its way out it says how far`() {
        on.value = true
        show(deps())
        // Ready or nothing asked yet: nothing over the words.
        compose.onAllNodesWithTag("karaokeProgress").assertCountEquals(0)
        state.value = KaraokeState.Fetching(0.4f)
        compose.waitForIdle()
        compose.onNodeWithText("Baixando o karaokê… 40%").assertExists()
        state.value = KaraokeState.Working(0.25f)
        compose.waitForIdle()
        compose.onNodeWithText("Tirando a voz… 25%").assertExists()
        state.value = KaraokeState.Ready
        compose.waitForIdle()
        compose.onAllNodesWithTag("karaokeProgress").assertCountEquals(0)
        state.value = KaraokeState.Failed(KaraokeState.Reason.OFFLINE)
        compose.waitForIdle()
        compose.onNodeWithText("Sem conexão pra preparar o karaokê.").assertExists()
        compose.onNodeWithText("Tentar de novo").performClick()
        assertEquals(1, retries)
        state.value = KaraokeState.Failed(KaraokeState.Reason.TOO_LONG)
        compose.waitForIdle()
        compose.onNodeWithText("Música longa demais pro karaokê.").assertExists()
        compose.onNodeWithText("Tentar de novo").assertDoesNotExist()
        // The microphone off: nothing is said.
        on.value = false
        compose.waitForIdle()
        compose.onNodeWithText("Música longa demais pro karaokê.").assertDoesNotExist()
    }

    @Test fun `no microphone where the karaoke can't run`() {
        on.value = true
        show(deps(supported = false))
        compose.onAllNodesWithContentDescription("Karaokê: tirar a voz").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Karaokê: voltar a voz").assertCountEquals(0)
        state.value = KaraokeState.Working(0.5f)
        compose.waitForIdle()
        compose.onAllNodesWithTag("karaokeProgress").assertCountEquals(0)
    }
}
