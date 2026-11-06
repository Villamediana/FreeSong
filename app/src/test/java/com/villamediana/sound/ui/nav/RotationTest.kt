package com.villamediana.sound.ui.nav

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.villamediana.sound.MainActivity
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.VideoPlayback
import com.villamediana.sound.playback.VideoState
import com.villamediana.sound.testing.blockMediaSessionBinding
import com.villamediana.sound.ui.theme.SoundTheme
import com.villamediana.sound.ui.video.OverlayState
import com.villamediana.sound.ui.video.VideoDeps
import com.villamediana.sound.ui.video.VideoHost
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MainActivity handles rotation itself (configChanges): nothing is rebuilt, the video carries on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RotationTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun blockPlaybackBinding() = blockMediaSessionBinding()

    @Test
    fun rotatingKeepsTheSameActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val landscape = Configuration(activity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 891
            screenHeightDp = 411
        }
        controller.configurationChange(landscape)
        assertSame(activity, controller.get())
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, controller.get().resources.configuration.orientation)
        controller.pause().stop().destroy()
    }

    private class FakeVideo : VideoPlayback {
        override val state = MutableStateFlow(VideoState())
        override val player = MutableStateFlow<Player?>(null)
        val opens = mutableListOf<String>()
        val closes = mutableListOf<Boolean>()

        override fun open(videoId: String, title: String?, startAtMs: Long) {
            opens += videoId
            state.value = VideoState(videoId = videoId, title = title, loading = false, playing = true)
        }

        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun close(resumeMusic: Boolean) {
            closes += resumeMusic
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h800dp")
    fun theVideoScreenTurnsInPlaceWithoutReopeningTheVideo() {
        val video = FakeVideo()
        val song = Track(id = 7, videoId = "abc123", title = "Aurora", artist = "Lua Nova", durationMs = 231_000, filePath = "/x/7.m4a")
        val deps = VideoDeps(video, MutableStateFlow(PlaybackState(current = song, isPlaying = true)), MutableStateFlow(42_000L))
        var landscape by mutableStateOf(false)
        compose.setContent {
            SoundTheme {
                Box(Modifier.size(if (landscape) DpSize(720.dp, 360.dp) else DpSize(360.dp, 720.dp))) {
                    VideoHost(VideoRoute("abc123", "Aurora"), deps, onLeave = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Voltar ao áudio").assertIsDisplayed()

        // The phone turns: same composition, new size.
        landscape = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(OverlayState.AUTO_HIDE_MS + 800)
        compose.waitForIdle()
        // Landscape layout: the controls (with "Voltar ao áudio") fade over the video.
        compose.onNodeWithText("Voltar ao áudio").assertDoesNotExist()
        assertEquals(listOf("abc123"), video.opens)
        assertTrue(video.closes.isEmpty())
    }
}
