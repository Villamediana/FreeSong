package com.villamediana.sound.playback.stream

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.MediaItems
import com.villamediana.sound.playback.OnlineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A real ExoPlayer, built like the service builds it, preparing a queue that mixes an online song
 * (placeholder uri → resolved → read in ranges from a local server) and a library file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnlineExoPlayerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = RangeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val youTube = FakeStreams(server)
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val streams = OnlineStreams(scope) { youTube }
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(StreamDataSources.factory(context, streams)).setLoadErrorHandlingPolicy(StreamLoadErrorPolicy()))
        .build()

    @After fun tearDown() {
        player.release()
        server.close()
        scope.cancel()
    }

    /** Runs the main looper (and the fake clock) until [done] or a player error; network threads run on their own. */
    private fun idleUntil(timeoutMs: Long = 10_000, done: () -> Boolean): PlaybackException? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            // Robolectric's clock only moves when told to; ExoPlayer's playback thread schedules work on it.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            player.playerError?.let { return it }
            if (done()) return null
            Thread.sleep(10)
        }
        throw AssertionError("timed out (state ${player.playbackState})")
    }

    @Test fun preparesAnOnlineSongAndALocalOneInTheSameQueue() {
        youTube.serve("online1", wav(30)) // ~5 MB: more than one 2 MB range
        val file = File(tmp.root, "local.wav").apply { writeBytes(wav(4)) }
        val online = OnlineItem("online1", "Online", "Artist").toTrack()
        val local = Track(id = 5, title = "Local", artist = "Artist", filePath = file.absolutePath)

        player.setMediaItems(listOf(MediaItems.of(online, AudioQuality.COMPATIBLE), MediaItems.of(local)))
        player.prepare()
        assertNull(idleUntil { player.playbackState == Player.STATE_READY && player.duration != C.TIME_UNSET })
        assertEquals(30_000.0, player.duration.toDouble(), 50.0)
        assertEquals("yt:online1", player.currentMediaItem!!.mediaId)
        assertEquals(listOf(AudioQuality.COMPATIBLE), youTube.qualities("online1"))
        // Every request to googlevideo is a bounded range, never "everything from here".
        val ranges = server.hits.map { it.range }
        assertTrue(ranges.isNotEmpty())
        assertTrue(ranges.all { it != null && Regex("bytes=\\d+-\\d+").matches(it) })

        // A seek far into the online song opens a new range there.
        player.seekTo(25_000)
        assertNull(idleUntil { player.playbackState == Player.STATE_READY })
        assertTrue(server.hits.any { h -> h.range!!.removePrefix("bytes=").substringBefore('-').toLong() > 4_000_000 })

        // On to the library file.
        player.seekTo(1, 0)
        assertNull(idleUntil { player.playbackState == Player.STATE_READY && player.currentMediaItemIndex == 1 && player.duration != C.TIME_UNSET })
        assertEquals("5", player.currentMediaItem!!.mediaId)
        assertEquals(4_000.0, player.duration.toDouble(), 50.0)
        assertEquals(1, youTube.calls("online1"))
    }

    @Test fun anUnavailableSongFailsFastWithItsKind() {
        val missing = OnlineItem("missing", "Gone", "Nobody").toTrack()
        player.setMediaItem(MediaItems.of(missing, AudioQuality.BEST))
        player.prepare()
        val error = idleUntil { false } // until the error arrives
        assertEquals(StreamErrors.code(com.villamediana.sound.online.OnlineException.Kind.NOT_FOUND), error!!.errorCode)
        // No retries for a stream that can't exist: resolved once, given up at once.
        assertEquals(1, youTube.calls("missing"))
    }
}
