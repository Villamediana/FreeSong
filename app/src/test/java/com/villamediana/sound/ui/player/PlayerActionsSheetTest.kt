package com.villamediana.sound.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.testing.blockMediaSessionBinding
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.search.FakePlayer
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The full player's ⋮ sheet against the real library (the sheet reads it through the app graph); phone-sized screen so the sheet fits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class PlayerActionsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun blockPlaybackBinding() = blockMediaSessionBinding()

    private val expandMini = SemanticsMatcher("opens the player") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Abrir player" }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun theSheetStaysOnItsSongWhenTheNextOneStarts() {
        val library = SoundApplication.graph.library
        val tracks = runBlocking {
            listOf(
                Track(title = "Aurora", artist = "Lua Nova", album = "Disco", durationMs = 180_000, filePath = "/x/1.m4a"),
                Track(title = "Maré", artist = "Lua Nova", album = "Disco", durationMs = 200_000, filePath = "/x/2.m4a"),
            ).map { library.getTrack(library.insertTrack(it))!! }
        }
        val playing = PlaybackState(current = tracks[0], isPlaying = true, durationMs = 180_000, queue = tracks, queueIndex = 0)
        val player = FakePlayer().apply { stateFlow.value = playing }
        val deps = PlayerDeps(player, { id -> library.track(id) }, { _, _ -> })
        compose.setContent {
            SoundTheme {
                val nav = rememberNavController()
                CompositionLocalProvider(LocalNavigator provides Navigator(nav)) {
                    PlayerHost(deps, bottomBarHeight = 64.dp)
                }
            }
        }

        compose.onNode(expandMini).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Mais opções").onFirst().performClick()
        waitFor(hasText("Editar informações"))
        compose.onNode(hasText("Editar informações")).performClick()
        waitFor(hasSetTextAction() and hasText("Aurora"))
        compose.onNode(hasSetTextAction() and hasText("Aurora")).performTextReplacement("Aurora (ao vivo)")
        compose.waitForIdle()

        // The song ends and the next one starts while the dialog is open.
        player.stateFlow.value = playing.copy(current = tracks[1], queueIndex = 1)
        compose.waitForIdle()

        compose.onNode(hasSetTextAction() and hasText("Aurora (ao vivo)")).assertExists()
        compose.onNode(hasText("Tocar a seguir")).assertDoesNotExist()
    }
}
