package com.villamediana.surco.ui.video

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.VideoPlayback
import com.villamediana.surco.playback.VideoState
import com.villamediana.surco.ui.nav.VideoRoute
import com.villamediana.surco.ui.theme.SurcoTheme
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
    }

    private val song = Track(id = 7, videoId = "abc123", title = "Aurora", artist = "Lua Nova", durationMs = 231_000, filePath = "/x/7.m4a")
    private val music = MutableStateFlow(PlaybackState(current = song, isPlaying = true, durationMs = 231_000))
    private val musicPosition = MutableStateFlow(42_000L)
    private var leaves = 0

    private fun show(video: FakeVideo, route: VideoRoute = VideoRoute("abc123", "Aurora")): () -> Unit {
        var visible by mutableStateOf(true)
        compose.setContent {
            SurcoTheme {
                if (visible) VideoHost(route, VideoDeps(video, music, musicPosition), onLeave = { leaves++ })
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
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, activity.requestedOrientation)
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
}
