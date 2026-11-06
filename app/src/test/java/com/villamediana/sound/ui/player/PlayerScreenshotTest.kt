package com.villamediana.sound.ui.player

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.Lyrics
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.playback.SleepTimer
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the player on a streamed song (best effort PNGs in build/screenshots, for eyeballing). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private class StillPlayer(initial: PlaybackState) : PlayerConnection {
        override val state = MutableStateFlow(initial)
        override val positionMs = MutableStateFlow(42_000L)
        override val sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
        override fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean) = Unit
        override fun playSmart(seed: SmartSeed) = Unit
        override fun togglePlayPause() = Unit
        override fun pause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun skipTo(queueIndex: Int) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun cycleRepeat() = Unit
        override fun playNext(tracks: List<Track>) = Unit
        override fun enqueue(tracks: List<Track>) = Unit
        override fun removeAt(queueIndex: Int) = Unit
        override fun move(from: Int, to: Int) = Unit
        override fun setSleepTimer(timer: SleepTimer) = Unit
    }

    private fun shot(name: String) {
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    @Test
    fun streamedSong() {
        val song = RemoteTrack("abcdefghijk", "https://music.youtube.com/watch?v=abcdefghijk", "Té para Tres", "Soda Stereo", "Canción Animal", 140_000, fromMusicCatalog = true).asTransientTrack()
        val player = StillPlayer(PlaybackState(current = song, isPlaying = true, durationMs = 140_000, queue = listOf(song), queueIndex = 0, source = PlaySource(PlaySource.Type.SEARCH, "soda stereo")))
        val deps = PlayerDeps(
            player = player,
            trackFlow = { flowOf(null) },
            setLiked = { _, _ -> },
            lyrics = OnlineLyricsCache({ Lyrics("[00:01.00]Una verdad sin fin\n[00:40.00]Té para tres\n[00:50.00]Nada más", synced = true) }),
        )
        compose.setContent {
            SoundTheme {
                val nav = rememberNavController()
                CompositionLocalProvider(LocalNavigator provides Navigator(nav)) {
                    PlayerHost(deps, bottomBarHeight = 64.dp)
                }
            }
        }
        compose.waitForIdle()
        shot("player_streamed_mini")
        compose.onNode(SemanticsMatcher("opens the player") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Abrir player" }).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Una verdad sin fin").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        shot("player_streamed_full")
    }
}
