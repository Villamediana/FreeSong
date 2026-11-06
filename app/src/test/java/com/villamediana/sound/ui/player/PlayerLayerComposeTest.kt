package com.villamediana.sound.ui.player

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.Lyrics
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.playback.RepeatMode
import com.villamediana.sound.playback.SleepTimer
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.search.DownloadLookup
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun pressBack() {
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** [below] stands for the screen under the player (composed after it, as a NavHost page can be). */
    private fun setUp(below: @Composable () -> Unit = {}): FakePlayer {
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
            SoundTheme {
                val nav = rememberNavController()
                backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                CompositionLocalProvider(LocalNavigator provides Navigator(nav)) {
                    PlayerHost(deps, bottomBarHeight = 64.dp)
                    below()
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
    fun backWithThePlayerOrTheLyricsOpenIsTheirsNotTheScreenBelow() {
        var screenBacks = 0
        var screenShown by mutableStateOf(false)
        // Like Search after the first switch to its tab: its Back handler comes after the player's.
        setUp(below = { if (screenShown) BackHandler { screenBacks++ } })
        screenShown = true
        compose.waitForIdle()

        expand()
        compose.onAllNodesWithContentDescription("Letra em tela cheia").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Fechar letra").assertIsDisplayed()

        pressBack()
        compose.onNodeWithContentDescription("Fechar letra").assertDoesNotExist()
        compose.onNodeWithText("TOCANDO DE").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("TOCANDO DE").assertDoesNotExist()
        assertEquals(0, screenBacks)

        // Collapsed, Back belongs to the screen again.
        pressBack()
        assertEquals(1, screenBacks)
    }

    // ------------------------------------------------------------------ streamed songs

    private val remote = RemoteTrack(
        "abcdefghijk", "https://music.youtube.com/watch?v=abcdefghijk", "Té para Tres", "Soda Stereo", "Canción Animal", 140_000,
        thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/hq.jpg", fromMusicCatalog = true,
    )
    private val downloadsAsked = mutableListOf<RemoteTrack>()
    private val libraryCopy = MutableStateFlow<Track?>(null)
    private val lookup = MutableStateFlow(DownloadLookup.Empty)
    private val lyricsAsked = mutableListOf<LyricsQuery>()

    private fun setUpStreaming(online: Boolean = true): FakePlayer {
        val streamed = remote.asTransientTrack()
        val player = FakePlayer(PlaybackState(current = streamed, isPlaying = true, durationMs = 140_000, queue = listOf(streamed), queueIndex = 0, source = PlaySource(PlaySource.Type.SEARCH, "soda")))
        val deps = PlayerDeps(
            player = player,
            trackFlow = { flowOf(null) },
            setLiked = { id, liked -> likes += id to liked },
            libraryTrack = { videoId -> libraryCopy.map { it?.takeIf { t -> t.videoId == videoId } } },
            downloads = lookup,
            download = { downloadsAsked += it },
            lyrics = OnlineLyricsCache({ q ->
                lyricsAsked += q
                Lyrics("[00:01.00]Una verdad sin fin\n[00:40.00]Té para tres", synced = true)
            }),
            online = MutableStateFlow(online),
        )
        compose.setContent {
            SoundTheme {
                val nav = rememberNavController()
                CompositionLocalProvider(LocalNavigator provides Navigator(nav)) {
                    PlayerHost(deps, bottomBarHeight = 64.dp)
                }
            }
        }
        return player
    }

    @Test
    fun aStreamedSongOffersDownloadInsteadOfTheHeartUntilItIsInTheLibrary() {
        setUpStreaming()
        compose.onAllNodesWithContentDescription("Tocando online").onFirst().assertExists()
        expand()
        compose.onNodeWithContentDescription("Curtir").assertDoesNotExist()
        compose.onNodeWithContentDescription("Baixar").performClick()
        compose.waitForIdle()
        assertEquals(listOf(remote), downloadsAsked)

        // The queue takes it: the button follows the download.
        lookup.value = DownloadLookup.build(emptySet(), listOf(DownloadTask(id = 1, videoId = remote.videoId, url = remote.url, title = remote.title, artist = remote.artist, state = DownloadState.RUNNING, progress = 0.5f)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Baixando, 50%").assertExists()

        // Downloaded: it is a library song now — the heart is back and likes the library row.
        libraryCopy.value = Track(id = 50, videoId = remote.videoId, title = remote.title, artist = remote.artist, filePath = "/music/abc.m4a")
        lookup.value = DownloadLookup.build(setOf(remote.videoId), emptyList())
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Tocando online").assertDoesNotExist()
        compose.onNodeWithContentDescription("Curtir").performClick()
        compose.waitForIdle()
        assertEquals(listOf(50L to true), likes)
    }

    @Test
    fun aStreamedSongHasLyricsVideoAndTheLetraButton() {
        setUpStreaming()
        expand()
        compose.onNodeWithContentDescription("Ver vídeo").assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Una verdad sin fin").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(LyricsQuery("Té para Tres", "Soda Stereo", "Canción Animal", 140_000)), lyricsAsked.distinct())
        // The "Letra" pill of the bottom row (the card below has its own open button too).
        compose.onAllNodesWithContentDescription("Letra em tela cheia").onFirst().assert(hasText("Letra")).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Fechar letra").assertIsDisplayed()
    }

    @Test
    fun offlineTheLyricsOfAStreamedSongWaitForTheNetwork() {
        setUpStreaming(online = false)
        expand()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A letra aparece quando a conexão voltar.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(lyricsAsked.isEmpty())
    }

    @Test
    fun theHeartLikesAtOnce() {
        setUp()
        expand()
        compose.onNodeWithContentDescription("Curtir").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L to true), likes)
        compose.onNodeWithContentDescription("Remover das curtidas").assertIsDisplayed()
    }
}
