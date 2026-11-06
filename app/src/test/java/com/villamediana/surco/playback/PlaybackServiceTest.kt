package com.villamediana.surco.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.playback.stream.wav
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutionException

/**
 * The real [PlaybackService] (ExoPlayer, session, Room library of short WAV files) driven the way
 * the app drives it: queue commands from a [MediaController], resumption the way Media3 asks for
 * it. Robolectric's controller only gets the custom commands, so the player is checked directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<SurcoApplication>()
    private val graph get() = SurcoApplication.graph
    private lateinit var service: ServiceController<PlaybackService>
    private lateinit var session: MediaLibrarySession
    private lateinit var controller: MediaController
    private lateinit var tracks: List<Track>
    private val appController = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.villamediana.surco", 0, 0, 0, 0, false, Bundle.EMPTY, false,
    )

    @Before fun setUp() {
        com.villamediana.surco.testing.blockMediaSessionBinding()
        val audio = wav(20)
        tracks = runBlocking {
            graph.settings.setLastQueueJson(null)
            (1..6).map { i ->
                val f = File(tmp.root, "$i.wav").apply { writeBytes(audio) }
                val t = Track(title = "Song $i", artist = "Artist", genre = "MPB", filePath = f.absolutePath, durationMs = 20_000)
                t.copy(id = graph.db.tracks().insert(t))
            }
        }
        ResumableQueue.set(app, false)
    }

    private fun start() {
        service = Robolectric.buildService(PlaybackService::class.java).create()
        session = service.get().onGetSession(appController)
        val future = MediaController.Builder(app, session.token).buildAsync()
        idleUntil { future.isDone }
        controller = future.get()
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.release()
        if (::service.isInitialized) service.destroy()
        idle(50)
    }

    // ------------------------------------------------------------------ harness

    private val player: ExoPlayer
        get() = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(service.get()) as ExoPlayer

    /** The service's session callback, as Media3 calls it. */
    private val callback: MediaLibrarySession.Callback
        get() = Class.forName("${PlaybackService::class.java.name}\$SessionCallback")
            .getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }
            .newInstance(service.get()) as MediaLibrarySession.Callback

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
        throw AssertionError("timed out")
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        idleUntil { future.isDone }
        return future.get()
    }

    private fun send(action: String, args: Bundle = Bundle.EMPTY) {
        await(controller.sendCustomCommand(SessionProtocol.command(action), args))
    }

    private fun play(ids: List<Long>, start: Int, shuffle: Int = SessionProtocol.SHUFFLE_KEEP) = send(
        SessionProtocol.PLAY,
        Bundle().apply {
            putLongArray(SessionProtocol.ARG_IDS, ids.toLongArray())
            putInt(SessionProtocol.ARG_START, start)
            putInt(SessionProtocol.ARG_SHUFFLE, shuffle)
        },
    )

    private fun savedJson(): String? = runBlocking { graph.settings.current().lastQueueJson }

    private fun currentId(): Long? = player.currentMediaItem?.mediaId?.toLongOrNull()

    // ------------------------------------------------------------------ resuming with nothing saved

    @Test fun emptyingTheQueueForgetsTheSavedOneAndTheHeadsetFlag() {
        start()
        play(tracks.map { it.id }, 0)
        player.pause()
        idleUntil { savedJson() != null && ResumableQueue.available(app) }

        player.clearMediaItems()
        idleUntil { savedJson() == null }
        assertFalse(ResumableQueue.available(app))
    }

    @Test fun headsetPlayWithNothingSavedPlaysTheLibraryInsteadOfFailing() {
        start()
        val result = await(callback.onPlaybackResumption(session, appController, true))
        assertEquals(tracks.map { it.id }.toSet(), result.mediaItems.map { it.mediaId.toLong() }.toSet())
        assertEquals(0, result.startIndex)
    }

    @Test fun savedQueueWhoseSongsWereAllDeletedFallsBackToTheLibraryAndIsForgotten() {
        runBlocking {
            graph.settings.setLastQueueJson(QueueSnapshot(ids = listOf(9_999L, 9_998L), index = 0).encode())
        }
        ResumableQueue.set(app, true)
        start()
        val result = await(callback.onPlaybackResumption(session, appController, true))
        assertEquals(6, result.mediaItems.size)
        idleUntil { savedJson() == null }
        assertFalse(ResumableQueue.available(app))
    }

    @Test fun nothingToResumeForTheSystemCardIsAnError() {
        start()
        val error = runCatching { await(callback.onPlaybackResumption(session, appController, false)) }.exceptionOrNull()
        assertTrue(error is ExecutionException)
        assertNull(currentId())
    }
}
