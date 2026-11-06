package com.villamediana.surco.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.playback.stream.wav
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val expected = runBlocking { graph.settings.current() }.copy(lastQueueJson = null)
        idleUntil { serviceSettings().copy(lastQueueJson = null) == expected }
    }

    private fun serviceSettings(): Settings =
        PlaybackService::class.java.getDeclaredField("settings").apply { isAccessible = true }.get(service.get()) as Settings

    /** Autoplay of similar songs (on by default), applied before the service starts. */
    private fun autoplay(on: Boolean) = runBlocking { graph.settings.setAutoplaySimilar(on) }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.release()
        if (::service.isInitialized) {
            service.destroy()
            // The queue saved on the way out must land now, not in the next test's settings.
            val persist = PlaybackService::class.java.getDeclaredField("persistJob").apply { isAccessible = true }
            (persist.get(service.get()) as Job?)?.let { runBlocking { withTimeout(5_000) { it.join() } } }
        }
        autoplay(true) // the settings store outlives the test
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

    /** Timeline indexes in play order. */
    private fun playOrderIds(): List<Long> =
        PlayOrder.of(player.currentTimeline, player.shuffleModeEnabled).toList().map { player.getMediaItemAt(it).mediaId.toLong() }

    // ------------------------------------------------------------------ starting a list

    @Test fun tappingTheFirstRowWhileShuffledPlaysThatRow() {
        start()
        repeat(5) {
            play(tracks.map { it.id }, 0, SessionProtocol.SHUFFLE_ON)
            assertTrue(player.shuffleModeEnabled)
            assertEquals(tracks[0].id, playOrderIds().first())
        }
    }

    private fun add(ids: List<Long>, where: String) = send(
        SessionProtocol.QUEUE_ADD,
        Bundle().apply {
            putLongArray(SessionProtocol.ARG_IDS, ids.toLongArray())
            putString(SessionProtocol.ARG_WHERE, where)
        },
    )

    /** Plays [ids] to their very end (autoplay off: nothing gets appended). */
    private fun playToTheEnd(ids: List<Long>) {
        play(ids, 0)
        player.seekTo(ids.lastIndex, 20_000)
        idleUntil { player.playbackState == Player.STATE_ENDED }
    }

    // ------------------------------------------------------------------ after the end

    @Test fun songsAddedAfterTheQueueEndedAreWhatPlayGoesTo() {
        autoplay(false)
        start()
        playToTheEnd(tracks.take(2).map { it.id })
        add(listOf(tracks[4].id), SessionProtocol.WHERE_END)
        assertEquals(tracks[4].id, currentId())
        assertFalse(player.playWhenReady) // waits for Play
        idleUntil { player.playbackState == Player.STATE_READY }

        playToTheEnd(tracks.take(2).map { it.id })
        add(listOf(tracks[5].id), SessionProtocol.WHERE_NEXT)
        assertEquals(tracks[5].id, currentId())
        assertEquals(listOf(tracks[0].id, tracks[1].id, tracks[5].id), playOrderIds())
    }

    @Test fun aQueueSavedAfterItEndedComesBackAtTheStartOfItsLastSong() {
        autoplay(false)
        start()
        playToTheEnd(tracks.take(3).map { it.id })
        idleUntil { QueueSnapshot.decode(savedJson())?.let { it.index == 2 && it.positionMs == 0L } == true }
    }

    // ------------------------------------------------------------------ listening stats

    @Test fun swipingTheAppAwayWhilePausedHandsTheListenToTheStats() {
        start()
        play(tracks.map { it.id }, 0)
        idleUntil { player.isPlaying }
        player.pause()
        idle(50)
        val listen = PlaybackService::class.java.getDeclaredField("listen").apply { isAccessible = true }.get(service.get()) as ListenTracker
        assertEquals(currentId(), listen.currentTrackId)
        // The app's controller keeps the service bound: no onDestroy follows, so the listen can't wait for it.
        service.get().onTaskRemoved(null)
        assertNull(listen.currentTrackId)
    }

    // ------------------------------------------------------------------ resuming the saved queue

    /** What Media3 does with a resumption result for a play press (not started: Robolectric plays through songs in no time). */
    private fun resumeLikeMedia3(): MediaSession.MediaItemsWithStartPosition {
        val result = await(callback.onPlaybackResumption(session, appController, true))
        player.setMediaItems(result.mediaItems, result.startIndex, result.startPositionMs)
        player.prepare()
        idle(300) // room for a (wrong) reshuffle to land
        return result
    }

    @Test fun headsetResumeKeepsTheSavedShuffleOrder() {
        val ids = tracks.map { it.id }
        val order = listOf(3, 0, 5, 1, 4, 2)
        runBlocking {
            graph.settings.setLastQueueJson(
                QueueSnapshot(ids = ids, index = 3, positionMs = 1_000, shuffle = true, order = order, repeat = RepeatMode.ALL).encode(),
            )
        }
        start()
        resumeLikeMedia3()
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertEquals(ids[3], currentId())
        assertEquals(order.map { ids[it] }, playOrderIds())
    }

    @Test fun theSystemResumeCardDoesNotTouchThePlayer() {
        runBlocking {
            graph.settings.setLastQueueJson(
                QueueSnapshot(ids = tracks.map { it.id }, index = 2, shuffle = true, order = listOf(2, 0, 1, 3, 4, 5), repeat = RepeatMode.ONE).encode(),
            )
        }
        start()
        val result = await(callback.onPlaybackResumption(session, appController, false))
        assertEquals(tracks[2].id.toString(), result.mediaItems[result.startIndex].mediaId)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    // ------------------------------------------------------------------ shuffle right after play

    private fun shuffleCommand(enabled: Boolean) =
        controller.sendCustomCommand(SessionProtocol.command(SessionProtocol.SET_SHUFFLE), Bundle().apply { putBoolean(SessionProtocol.ARG_ENABLED, enabled) })

    private fun playCommand(ids: List<Long>, start: Int) = controller.sendCustomCommand(
        SessionProtocol.command(SessionProtocol.PLAY),
        Bundle().apply {
            putLongArray(SessionProtocol.ARG_IDS, ids.toLongArray())
            putInt(SessionProtocol.ARG_START, start)
            putInt(SessionProtocol.ARG_SHUFFLE, SessionProtocol.SHUFFLE_KEEP)
        },
    )

    @Test fun shuffleOnRightAfterPlayKeepsTheStartSongFirst() {
        start()
        val ids = tracks.map { it.id }
        playCommand(ids, 2)
        await(shuffleCommand(true))
        assertTrue(player.shuffleModeEnabled)
        idleUntil { playOrderIds().first() == ids[2] && playOrderIds().toSet() == ids.toSet() }
    }

    @Test fun shuffleOffRightAfterPlayWinsOverTheShuffleBeingComputed() {
        start()
        val ids = tracks.map { it.id }
        play(ids.take(3), 0)
        await(shuffleCommand(true))
        // The new queue is shuffled (shuffle was on) while "off" is already on its way.
        playCommand(ids, 3)
        await(shuffleCommand(false))
        assertFalse(player.shuffleModeEnabled)
        assertEquals(ids, playOrderIds())
    }

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
