package com.villamediana.sound.ui.video

import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.VideoPlayback
import com.villamediana.sound.playback.VideoState
import com.villamediana.sound.ui.nav.VideoRoute
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The video screen against a fake [VideoPlayback] (no real player, no service, no database). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoScreenSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeVideo : VideoPlayback {
        override val state = MutableStateFlow(VideoState())
        override val player = MutableStateFlow<Player?>(null)
        val opens = mutableListOf<Triple<String, String?, Long>>()
        val closes = mutableListOf<Boolean>()
        val seeks = mutableListOf<Long>()
        var toggles = 0

        override fun open(videoId: String, title: String?, startAtMs: Long) {
            opens += Triple(videoId, title, startAtMs)
            state.value = VideoState(videoId = videoId, title = title, loading = false, playing = true)
        }

        override fun togglePlayPause() {
            toggles++
            state.update { it.copy(playing = !it.playing) }
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun close(resumeMusic: Boolean) {
            closes += resumeMusic
            state.value = VideoState()
        }

        val speeds = mutableListOf<Float>()
        override fun setSpeed(speed: Float) {
            speeds += speed
        }
    }

    private val song = Track(id = 7, videoId = "abc123", title = "Aurora", artist = "Lua Nova", durationMs = 231_000, filePath = "/x/7.m4a")
    private val music = MutableStateFlow(PlaybackState(current = song, isPlaying = true, durationMs = 231_000))
    private val musicPosition = MutableStateFlow(42_000L)
    private var leaves = 0

    private var lyricsCache: com.villamediana.sound.ui.player.OnlineLyricsCache? = null

    private fun show(video: FakeVideo, route: VideoRoute = VideoRoute("abc123", "Aurora")): () -> Unit {
        var visible by mutableStateOf(true)
        compose.setContent {
            SoundTheme {
                if (visible) VideoHost(route, VideoDeps(video, music, musicPosition, lyricsCache), onLeave = { leaves++ })
            }
        }
        compose.waitForIdle()
        return { visible = false }
    }

    private val activity: Activity get() = compose.activity

    /** Best effort PNG of the screen into build/screenshots (only with native graphics). */
    private fun screenshot(name: String) {
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    @Test fun opensAtTheSongPositionAndShowsTitleAndArtist() {
        val video = FakeVideo()
        show(video)
        assertEquals(listOf(Triple("abc123", "Aurora", 42_000L)), video.opens)
        compose.onNodeWithText("Aurora").assertIsDisplayed()
        compose.onNodeWithText("Lua Nova").assertIsDisplayed()
        compose.onNodeWithText("Voltar ao áudio").assertIsDisplayed()
        // Opens sideways; once the phone is turned it follows the phone again.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        screenshot("video_portrait")
    }

    @Test fun anotherVideoStartsFromTheTop() {
        val video = FakeVideo()
        show(video, VideoRoute("other", "Clipe"))
        assertEquals(listOf(Triple("other", "Clipe", 0L)), video.opens)
        compose.onNodeWithText("Clipe").assertIsDisplayed()
    }

    @Test fun backToAudioClosesOnceAndLeaves() {
        val video = FakeVideo()
        val hide = show(video)
        compose.onNodeWithText("Voltar ao áudio").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), video.closes)
        assertEquals(1, leaves)
        hide()
        compose.waitForIdle()
        assertEquals(listOf(true), video.closes)
    }

    @Test fun leavingAnyOtherWayClosesWithMusicAndRestoresTheWindow() {
        val video = FakeVideo()
        val before = activity.requestedOrientation
        val hide = show(video)
        hide()
        compose.waitForIdle()
        assertEquals(listOf(true), video.closes)
        assertEquals(before, activity.requestedOrientation)
    }

    @Test fun centerButtonTogglesPlayback() {
        val video = FakeVideo()
        show(video)
        compose.onNodeWithContentDescription("Pausar vídeo").performClick()
        compose.waitForIdle()
        assertEquals(1, video.toggles)
        compose.onNodeWithContentDescription("Tocar vídeo").assertIsDisplayed()
    }

    @Test fun controlsHideByThemselvesAndComeBackWithATap() {
        val video = FakeVideo()
        show(video)
        compose.onNodeWithContentDescription("Pausar vídeo").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(OverlayState.AUTO_HIDE_MS + 800)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pausar vídeo").assertDoesNotExist()
        screenshot("video_portrait_hidden")

        compose.onNode(stageTap).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pausar vídeo").assertIsDisplayed()
    }

    /** A real (idle) player, so the stage takes jumps and holds. */
    private fun withPlayer(video: FakeVideo): androidx.media3.exoplayer.ExoPlayer {
        val p = compose.runOnIdle { androidx.media3.exoplayer.ExoPlayer.Builder(activity).build() }
        compose.runOnIdle { video.player.value = p }
        compose.waitForIdle()
        return p
    }

    @Test fun aTapShowsTheControlsAtOnceAndASecondTapJumpsInstead() {
        val video = FakeVideo()
        show(video)
        val p = withPlayer(video)
        compose.mainClock.advanceTimeBy(OverlayState.AUTO_HIDE_MS + 800)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pausar vídeo").assertDoesNotExist()

        // One tap: the controls are there right away, not after the double-tap wait.
        compose.onNode(stage).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithContentDescription("Pausar vídeo").assertIsDisplayed()

        // A tap pair on the right: a 10 s jump, and the controls back to how they were.
        compose.mainClock.advanceTimeBy(OverlayState.AUTO_HIDE_MS + 800)
        compose.waitForIdle()
        compose.onNode(stage).performTouchInput { doubleClick(centerRight - androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.waitForIdle()
        assertEquals(1, video.seeks.size)
        compose.onNodeWithContentDescription("Pausar vídeo").assertDoesNotExist()
        compose.runOnIdle { p.release() }
    }

    @Test fun holdingTheVideoPlaysItTwiceAsFast() {
        val video = FakeVideo()
        show(video)
        val p = withPlayer(video)
        // Right half: 2× forward.
        compose.onNode(stage).performTouchInput { down(percentOffset(0.8f, 0.2f)) }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        assertEquals(listOf(2f), video.speeds)
        compose.onNodeWithText("2×").assertExists()
        compose.onNode(stage).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(2f, 1f), video.speeds)
        compose.runOnIdle { p.release() }
    }

    @Test fun holdingTheLeftHalfRewinds() {
        val video = FakeVideo()
        show(video)
        val p = withPlayer(video)
        compose.onNode(stage).performTouchInput { down(percentOffset(0.2f, 0.2f)) }
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
        // Stepping back while held (nothing to go back from at 0 here: it stays at 0), no 2× speed.
        assertTrue(video.speeds.isEmpty())
        compose.onNode(stage).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(1f), video.speeds)
        compose.runOnIdle { p.release() }
    }

    @Test fun swipingTheVideoTurnsTheScreen() {
        val video = FakeVideo()
        show(video)
        compose.onNode(stage).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        compose.onNode(stage).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }

    @Test fun upright_theLyricsAndTheSongFactsSitUnderTheVideo() {
        lyricsCache = com.villamediana.sound.ui.player.OnlineLyricsCache({ com.villamediana.sound.online.Lyrics("[00:01.00]Una verdad sin fin\n[00:40.00]Té para tres", synced = true) })
        val video = FakeVideo()
        show(video)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Una verdad sin fin").fetchSemanticsNodes().isNotEmpty() }
        // No "Duração" under the lyrics.
        compose.onNodeWithText("DURAÇÃO").assertDoesNotExist()
        screenshot("video_portrait_lyrics")
    }

    @Test fun loadingShowsTheSpinnerInsteadOfThePlayButton() {
        val video = FakeVideo()
        show(video)
        video.state.update { it.copy(loading = true, playing = false) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Carregando vídeo").assertExists()
        screenshot("video_loading")
        compose.onNodeWithContentDescription("Tocar vídeo").assertDoesNotExist()
    }

    @Test fun errorOffersRetryFromTheStart() {
        val video = FakeVideo()
        show(video)
        video.state.update { it.copy(playing = false, error = "Vídeo indisponível") }
        compose.waitForIdle()
        compose.onNodeWithText("Não deu para abrir o vídeo").assertIsDisplayed()
        compose.onNodeWithText("Vídeo indisponível").assertIsDisplayed()
        screenshot("video_error")
        compose.onNodeWithText("Tentar de novo").performClick()
        compose.waitForIdle()
        assertEquals(2, video.opens.size)
        assertEquals(Triple("abc123", "Aurora", 42_000L), video.opens.last())
        // The way back is still there.
        compose.onNodeWithContentDescription("Voltar").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapePutsTitleAndBackToAudioOverTheVideo() {
        val video = FakeVideo()
        show(video)
        compose.onNodeWithText("Aurora").assertIsDisplayed()
        compose.onNodeWithText("Voltar ao áudio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Posição do vídeo").assertIsDisplayed()
        screenshot("video_landscape")
        compose.mainClock.advanceTimeBy(OverlayState.AUTO_HIDE_MS + 800)
        compose.waitForIdle()
        compose.onNodeWithText("Voltar ao áudio").assertDoesNotExist()
        assertTrue(video.closes.isEmpty())
    }

    private val stageTap = SemanticsMatcher("taps the stage") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Mostrar controles" }
    /** The video stage, controls shown or not. */
    private val stage = SemanticsMatcher("the stage") { it.config.getOrNull(SemanticsActions.OnClick)?.label in setOf("Mostrar controles", "Ocultar controles") }
}
