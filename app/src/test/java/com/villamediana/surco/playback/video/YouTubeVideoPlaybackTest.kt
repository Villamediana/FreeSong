package com.villamediana.surco.playback.video

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlayerConnection
import com.villamediana.surco.playback.SleepTimer
import com.villamediana.surco.playback.SmartSeed
import com.villamediana.surco.playback.stream.FakeStreams
import com.villamediana.surco.playback.stream.RangeServer
import com.villamediana.surco.playback.stream.wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** "Ver o vídeo": pauses the music, plays the video (two merged streams), hands the position back on close. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YouTubeVideoPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = RangeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val youTube = FakeStreams(server)
    private val song = Track(id = 3, videoId = "mv1", title = "De Música Ligera", artist = "Soda Stereo", durationMs = 60_000, filePath = "/music/3.m4a")
    private val music = FakeMusic(PlaybackState(current = song, isPlaying = true, durationMs = 60_000))
    private val video = YouTubeVideoPlayback(context, scope, { youTube }, { music })

    @After fun tearDown() {
        video.close(resumeMusic = false)
        idle(50)
        server.close()
        scope.cancel()
    }

    private fun idle(ms: Long) {
        repeat((ms / 10).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(10)
        }
    }

    private fun idleUntil(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: ${video.state.value}")
    }

    @Test fun opensPausingTheMusicAndClosesHandingThePositionBack() {
        youTube.serveVideo("mv1", wav(60), wav(60))
        video.open("mv1", "De Música Ligera", startAtMs = 20_000)
        idleUntil { video.state.value.playing }
        assertEquals(1, music.pauses)
        val state = video.state.value
        assertEquals("mv1", state.videoId)
        assertEquals("De Música Ligera", state.title)
        assertNull(state.error)
        val player = video.player.value
        assertNotNull(player)
        assertTrue(player!!.currentPosition in 20_000..25_000)
        // Two parts (video + audio) read in ranges.
        assertTrue(server.hits.any { it.path.startsWith("/video-mv1") })
        assertTrue(server.hits.any { it.path.startsWith("/video-audio-mv1") })

        video.togglePlayPause()
        idleUntil { !video.state.value.playing }
        video.seekTo(31_000)
        idleUntil { player.currentPosition in 31_000..31_500 && player.playbackState == Player.STATE_READY }

        video.close(resumeMusic = true)
        idleUntil { video.player.value == null }
        assertEquals(VideoStateDefaults.closed, video.state.value)
        assertEquals(Player.STATE_IDLE, player.playbackState) // released
        assertEquals(1, music.seeks.size)
        assertTrue(music.seeks.single() in 31_000..31_500)
        assertEquals(1, music.toggles)
    }

    @Test fun anotherSongsVideoResumesTheMusicOnlyIfItWasPlaying() {
        youTube.serveVideo("other", wav(10), null) // muxed
        video.open("other", null, startAtMs = 0)
        idleUntil { video.state.value.playing }
        video.close(resumeMusic = true)
        idleUntil { video.player.value == null }
        assertEquals(emptyList<Long>(), music.seeks) // not the same song: its position stays
        assertEquals(1, music.toggles) // it was playing before

        music.state.value = music.state.value.copy(isPlaying = false)
        music.toggles = 0
        video.open("other", null, startAtMs = 0)
        idleUntil { video.state.value.playing }
        video.close(resumeMusic = true)
        idleUntil { video.player.value == null }
        assertEquals(0, music.toggles)
    }

    @Test fun reopeningTheSameVideoKeepsItPlaying() {
        youTube.serveVideo("mv1", wav(30), wav(30))
        video.open("mv1", "A", startAtMs = 0)
        idleUntil { video.state.value.playing }
        val player = video.player.value
        video.open("mv1", "A", startAtMs = 0)
        idle(50)
        assertTrue(video.player.value === player)
        assertEquals(1, youTube.calls("video:mv1"))
    }

    @Test fun unavailableVideoShowsWhyAndCanBeRetried() {
        video.open("nope", "Nada", startAtMs = 0)
        idleUntil { video.state.value.error != null }
        assertEquals(context.getString(R.string.online_error_not_found), video.state.value.error)
        assertEquals(false, video.state.value.loading)
        assertEquals(1, music.pauses)

        // Play again once it exists (back online): the streams are looked up again.
        youTube.serveVideo("nope", wav(10), wav(10))
        video.togglePlayPause()
        idleUntil { video.state.value.playing }
        assertNull(video.state.value.error)
    }
}

private object VideoStateDefaults {
    val closed = com.villamediana.surco.playback.VideoState()
}

/** Records what the video player asks of the music. */
private class FakeMusic(initial: PlaybackState) : PlayerConnection {
    override val state = MutableStateFlow(initial)
    override val positionMs: Flow<Long> = MutableStateFlow(0L)
    override val sleepTimer: StateFlow<SleepTimer> = MutableStateFlow(SleepTimer.Off)
    var pauses = 0
    var toggles = 0
    val seeks = mutableListOf<Long>()

    override fun pause() {
        pauses++
        state.value = state.value.copy(isPlaying = false)
    }

    override fun togglePlayPause() {
        toggles++
        state.value = state.value.copy(isPlaying = !state.value.isPlaying)
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean) = Unit
    override fun playSmart(seed: SmartSeed) = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun skipTo(queueIndex: Int) = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun cycleRepeat() = Unit
    override fun playNext(tracks: List<Track>) = Unit
    override fun enqueue(tracks: List<Track>) = Unit
    override fun removeAt(queueIndex: Int) = Unit
    override fun move(from: Int, to: Int) = Unit
    override fun setSleepTimer(timer: SleepTimer) = Unit
}
