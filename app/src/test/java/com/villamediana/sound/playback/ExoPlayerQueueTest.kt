package com.villamediana.sound.playback

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.smart.TestLibrary.Companion.library
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The queue edits the service makes, checked against a real ExoPlayer: what the listener sees
 * (play order, read through [PlayOrder]) must be exactly what was asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExoPlayerQueueTest {
    private val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Application>()).build()

    @After
    fun release() = player.release()

    private fun item(i: Int) = MediaItem.Builder().setMediaId("$i").setUri("file:///music/$i.m4a").build()

    private fun playOrder() = PlayOrder.of(player.currentTimeline, player.shuffleModeEnabled)

    private fun idsInPlayOrder() = playOrder().toList().map { player.getMediaItemAt(it).mediaId.toInt() }

    @Test
    fun `shuffle order set on the player is the play order the UI reads`() {
        player.setMediaItems((0 until 5).map(::item))
        player.shuffleModeEnabled = true
        player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 0, 4, 1, 2), 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(3, 0, 4, 1, 2), playOrder().toList())
        player.seekTo(4, 0)
        assertEquals(1, player.nextMediaItemIndex)
        assertEquals(0, player.previousMediaItemIndex)
    }

    @Test
    fun `play next while shuffled lands right after the playing song`() {
        player.setMediaItems((0 until 5).map(::item))
        player.shuffleModeEnabled = true
        player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 0, 4, 1, 2), 1))
        player.seekTo(4, 0) // playing song "4", play position 2
        val before = playOrder()
        player.addMediaItems(5, listOf(item(5), item(6)))
        player.setShuffleOrder(DefaultShuffleOrder(before.inserted(before.playIndexOf(4) + 1, 5, 2).toIntArray(), 2))
        assertEquals(listOf(3, 0, 4, 5, 6, 1, 2), idsInPlayOrder())
        assertEquals(4, player.currentMediaItem!!.mediaId.toInt())
        assertEquals(5, player.nextMediaItemIndex)
    }

    @Test
    fun `moving while shuffled only reorders, the playing song keeps playing`() {
        player.setMediaItems((0 until 5).map(::item))
        player.shuffleModeEnabled = true
        player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 0, 4, 1, 2), 1))
        player.seekTo(0, 0)
        player.setShuffleOrder(DefaultShuffleOrder(playOrder().moved(3, 1).toIntArray(), 3))
        assertEquals(listOf(3, 1, 0, 4, 2), idsInPlayOrder())
        assertEquals("0", player.currentMediaItem!!.mediaId)
    }

    @Test
    fun `removing while shuffled keeps the order of the rest`() {
        player.setMediaItems((0 until 5).map(::item))
        player.shuffleModeEnabled = true
        player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 0, 4, 1, 2), 1))
        val p = playOrder().playIndexOf(4)
        player.removeMediaItem(playOrder().timelineIndexOf(p))
        assertEquals(listOf(3, 0, 1, 2), idsInPlayOrder())
    }

    @Test
    fun `queue shuffler order applied with the playing song first`() {
        val lib = library { repeat(12) { add("Artist ${it % 4}") } }
        player.setMediaItems(lib.map { MediaItems.of(it) }, 5, 0)
        player.shuffleModeEnabled = true
        val order = QueueShuffler.order(lib, first = 5, smart = true, taste = com.villamediana.sound.smart.neutralTaste(), seed = 9)
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), 9))
        assertEquals(order, playOrder())
        assertEquals(5, player.currentMediaItemIndex)
        assertFalse(player.hasPreviousMediaItem())
        assertTrue(player.hasNextMediaItem())
    }

    @Test
    fun `media items carry the track and match it`() {
        val t = library { add("Djavan", genre = "MPB", album = "Luz", year = 1982, title = "Sina") }.single()
        val item = MediaItems.of(t)
        assertEquals(t.id.toString(), item.mediaId)
        assertEquals("Sina", item.mediaMetadata.title.toString())
        assertEquals("Djavan", item.mediaMetadata.artist.toString())
        assertEquals("Luz", item.mediaMetadata.albumTitle.toString())
        assertEquals(1982, item.mediaMetadata.recordingYear)
        assertEquals("file:///covers/Djavan-Luz.jpg", item.mediaMetadata.artworkUri.toString())
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals("file:///music/${t.id}.m4a", item.localConfiguration!!.uri.toString())
        assertTrue(MediaItems.matches(item, t))
        assertFalse(MediaItems.matches(item, t.copy(title = "Sina (ao vivo)")))
        assertFalse(MediaItems.matches(item, t.copy(coverPath = "/covers/new.jpg")))
        assertTrue(MediaItems.matches(item, t.copy(liked = true, playCount = 9)))
    }

    @Test
    fun `session protocol bundles round trip`() {
        val b = Bundle()
        val source = PlaySource(PlaySource.Type.PLAYLIST, "Viagem", "12")
        SessionProtocol.putSource(b, source)
        SessionProtocol.putSleep(b, SleepTimer.At(endsAtElapsedMs = 90_000, totalMs = 30_000))
        assertEquals(source, SessionProtocol.getSource(b))
        assertEquals(SleepTimer.At(90_000, 30_000), SessionProtocol.getSleep(b))
        SessionProtocol.putSleep(b, SleepTimer.EndOfTrack)
        assertEquals(SleepTimer.EndOfTrack, SessionProtocol.getSleep(b))
        SessionProtocol.putSleep(b, SleepTimer.Off)
        assertEquals(SleepTimer.Off, SessionProtocol.getSleep(b))
        SessionProtocol.putSource(b, null)
        assertEquals(null, SessionProtocol.getSource(b))
        assertEquals(SleepTimer.Off, SessionProtocol.getSleep(null))
    }

    @Test
    fun `browse ids survive any album or artist name`() {
        val name = "AC/DC | Back in Black \u0001 100%"
        assertEquals(name, LibraryBrowser.decode(LibraryBrowser.encode(name)))
        assertFalse(LibraryBrowser.encode(name).contains('|'))
        assertFalse(LibraryBrowser.encode(name).contains('/'))
    }
}
