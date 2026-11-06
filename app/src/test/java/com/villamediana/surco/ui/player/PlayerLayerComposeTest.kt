package com.villamediana.surco.ui.player

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.PlayerConnection
import com.villamediana.surco.playback.RepeatMode
import com.villamediana.surco.playback.SleepTimer
import com.villamediana.surco.playback.SmartSeed
import com.villamediana.surco.ui.nav.LocalNavigator
import com.villamediana.surco.ui.nav.Navigator
import com.villamediana.surco.ui.theme.SurcoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player layer composes and reacts with a fake player (no service, no database). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerLayerComposeTest {
    @get:Rule val compose = createComposeRule()

    private val tracks = listOf(
        Track(id = 1, title = "Aurora", artist = "Lua Nova", album = "Disco", durationMs = 180_000, filePath = "/x/1.m4a"),
        Track(id = 2, title = "Maré", artist = "Lua Nova", album = "Disco", durationMs = 200_000, filePath = "/x/2.m4a"),
        Track(id = 3, title = "Farol", artist = "Lua Nova", album = "Disco", durationMs = 210_000, filePath = "/x/3.m4a"),
    )

    private class FakePlayer(initial: PlaybackState) : PlayerConnection {
        override val state = MutableStateFlow(initial)
        override val positionMs = MutableStateFlow(30_000L)
        override val sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
        var toggles = 0
        var nexts = 0
        var skippedTo = -1
        var timer: SleepTimer? = null
        override fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean) = Unit
        override fun playSmart(seed: SmartSeed) = Unit
        override fun togglePlayPause() {
            toggles++
            state.update { it.copy(isPlaying = !it.isPlaying) }
        }
        override fun pause() = Unit
        override fun next() {
            nexts++
        }
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun skipTo(queueIndex: Int) {
            skippedTo = queueIndex
        }
        override fun setShuffle(enabled: Boolean) = state.update { it.copy(shuffle = enabled) }
        override fun cycleRepeat() = Unit
        override fun playNext(tracks: List<Track>) = Unit
        override fun enqueue(tracks: List<Track>) = Unit
        override fun removeAt(queueIndex: Int) = Unit
        override fun move(from: Int, to: Int) = Unit
        override fun setSleepTimer(timer: SleepTimer) {
            this.timer = timer
            sleepTimer.value = timer
        }
    }

    private val expandMini = SemanticsMatcher("opens the player") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Abrir player" }

    private fun expand() {
        compose.onNode(expandMini).performClick()
        compose.waitForIdle()
    }

    private val likes = mutableListOf<Pair<Long, Boolean>>()

    private fun setUp(): FakePlayer {
        val player = FakePlayer(
            PlaybackState(
                current = tracks[0],
                isPlaying = true,
                durationMs = 180_000,
                queue = tracks,
                queueIndex = 0,
                repeat = RepeatMode.OFF,
                source = PlaySource(PlaySource.Type.ALBUM, "Disco"),
            ),
        )
        val deps = PlayerDeps(player, { id -> flowOf(tracks.firstOrNull { it.id == id }) }, { id, liked -> likes += id to liked })
        compose.setContent {
            SurcoTheme {
                val nav = rememberNavController()
                CompositionLocalProvider(LocalNavigator provides Navigator(nav)) {
                    PlayerHost(deps, bottomBarHeight = 64.dp)
                }
            }
        }
        return player
    }

    @Test
    fun miniPlayerShowsTheSongAndTogglesPlayback() {
        val player = setUp()
        compose.onAllNodesWithText("Aurora").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Lua Nova").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Pausar").performClick()
        compose.waitForIdle()
        assertEquals(1, player.toggles)
        compose.onNodeWithContentDescription("Tocar").assertIsDisplayed()
    }

    @Test
    fun tapExpandsAndChevronCollapses() {
        setUp()
        compose.onNodeWithText("TOCANDO DE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pausar").assertIsDisplayed()
        expand()
        compose.onNodeWithText("TOCANDO DE").assertIsDisplayed()
        compose.onAllNodesWithText("Disco").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Minimizar player").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("TOCANDO DE").assertDoesNotExist()
    }

    @Test
    fun queueSheetListsWhatComesNextAndSkips() {
        val player = setUp()
        expand()
        compose.onNodeWithContentDescription("Fila").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("A SEGUIR").assertIsDisplayed()
        compose.onNodeWithText("Tocando de Disco").assertIsDisplayed()
        compose.onNodeWithText("Farol").performClick()
        compose.waitForIdle()
        assertEquals(2, player.skippedTo)
    }

    @Test
    fun sleepTimerSheetSetsATimer() {
        val player = setUp()
        expand()
        compose.onNodeWithContentDescription("Timer de sono").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("15 minutos").performClick()
        compose.waitForIdle()
        val t = player.timer
        assertTrue(t is SleepTimer.At && t.totalMs == 15 * 60_000L)
        compose.onNodeWithText("15 min").assertIsDisplayed()
    }

    @Test
    fun dragUpOnTheMiniExpandsAndPullingDownCollapses() {
        setUp()
        compose.onNode(expandMini).performTouchInput { swipeUp(startY = centerY, endY = centerY - 260f, durationMillis = 250) }
        compose.waitForIdle()
        compose.onNodeWithText("TOCANDO DE").assertIsDisplayed()
        compose.onNodeWithText("TOCANDO DE").performTouchInput { swipeDown(startY = centerY, endY = centerY + 260f, durationMillis = 250) }
        compose.waitForIdle()
        compose.onNodeWithText("TOCANDO DE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pausar").assertIsDisplayed()
    }

    @Test
    fun swipingTheMiniLeftGoesToTheNextSong() {
        val player = setUp()
        compose.onNode(expandMini).performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 200) }
        compose.waitForIdle()
        assertEquals(1, player.nexts)
        compose.onNodeWithText("TOCANDO DE").assertDoesNotExist()
    }

    @Test
    fun theHeartLikesAtOnce() {
        setUp()
        compose.onNodeWithContentDescription("Curtir").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L to true), likes)
        compose.onNodeWithContentDescription("Remover das curtidas").assertIsDisplayed()
    }
}
