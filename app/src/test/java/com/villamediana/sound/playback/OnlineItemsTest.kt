package com.villamediana.sound.playback

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.data.onlineTrackId
import com.villamediana.sound.data.shareUrl
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.stream.StreamUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Online songs as transient tracks, media items (service and controller side) and command payloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnlineItemsTest {
    private val remote = RemoteTrack(
        videoId = "sWft4cMiwXQ",
        url = "https://music.youtube.com/watch?v=sWft4cMiwXQ",
        title = "De Música Ligera (Remasterizado 2007)",
        artist = "Soda Stereo",
        album = "Canción Animal",
        durationMs = 213_000,
        thumbnailUrl = "https://lh3.googleusercontent.com/abc=w720-h720-l90-rj",
        fromMusicCatalog = true,
    )
    private val local = Track(id = 7, videoId = "local01", title = "Persiana Americana", artist = "Soda Stereo", filePath = "/music/7.m4a", coverPath = "/covers/7.jpg")

    @Test fun onlineItemTransientTrackRoundTrip() {
        val item = OnlineItem.of(remote)
        val track = item.toTrack()
        assertEquals(remote.asTransientTrack(), track)
        assertTrue(track.isOnline)
        assertEquals(onlineTrackId("sWft4cMiwXQ"), track.id)
        assertEquals(item.trackId, track.id)
        assertEquals(remote.thumbnailUrl, track.coverPath)
        assertEquals(remote.url, track.shareUrl)
        assertEquals(item, OnlineItem.of(track))
        assertNull(OnlineItem.of(local))
        // Without a url, the YouTube Music page is assumed.
        assertEquals("https://music.youtube.com/watch?v=x1", OnlineItem("x1", "T", "A").toRemote().url)
    }

    @Test fun onlineMediaItemCarriesWhatThePlayerAndTheAppNeed() {
        val track = OnlineItem.of(remote).toTrack()
        val item = MediaItems.of(track, AudioQuality.SAVER)
        assertEquals("yt:sWft4cMiwXQ", item.mediaId)
        assertEquals(StreamUris.audio("sWft4cMiwXQ", AudioQuality.SAVER), item.localConfiguration!!.uri)
        val m = item.mediaMetadata
        assertEquals(remote.title, m.title.toString())
        assertEquals("Soda Stereo", m.artist.toString())
        assertEquals("Canción Animal", m.albumTitle.toString())
        assertEquals(213_000L, m.durationMs)
        assertEquals(remote.thumbnailUrl, m.artworkUri.toString())
        assertNull(MediaItems.trackId(item))
        assertEquals("sWft4cMiwXQ", MediaItems.videoId(item))
        assertTrue(MediaItems.isOnline(item))
        assertEquals(track, MediaItems.onlineTrack(item))

        // What a MediaController receives: no uri, but the id and metadata (extras included).
        val controllerSide = MediaItem.fromBundle(item.toBundle())
        assertNull(controllerSide.localConfiguration)
        assertEquals(OnlineItem.of(remote), MediaItems.onlineItem(controllerSide))
        assertEquals(track, MediaItems.onlineTrack(controllerSide))
    }

    @Test fun libraryMediaItemsStayAsTheyWere() {
        val item = MediaItems.of(local)
        assertEquals("7", item.mediaId)
        assertEquals("file:///music/7.m4a", item.localConfiguration!!.uri.toString())
        assertEquals("file:///covers/7.jpg", item.mediaMetadata.artworkUri.toString())
        assertEquals(7L, MediaItems.trackId(item))
        assertNull(MediaItems.videoId(item))
        assertNull(MediaItems.onlineItem(item))
        assertFalse(MediaItems.isOnline(item))
        assertTrue(MediaItems.matches(item, local))
    }

    @Test fun commandsDescribeTheirOnlineSongs() {
        val online = OnlineItem.of(remote).toTrack()
        val b = Bundle()
        SessionProtocol.putOnline(b, listOf(local, online, online))
        val back = SessionProtocol.getOnline(b)
        assertEquals(mapOf(online.id to OnlineItem.of(remote)), back)

        val onlyLocal = Bundle()
        SessionProtocol.putOnline(onlyLocal, listOf(local))
        assertFalse(onlyLocal.containsKey(SessionProtocol.ARG_ONLINE))
        assertEquals(emptyMap<Long, OnlineItem>(), SessionProtocol.getOnline(onlyLocal))
        assertEquals(emptyMap<Long, OnlineItem>(), SessionProtocol.getOnline(Bundle().apply { putString(SessionProtocol.ARG_ONLINE, "{broken") }))
    }

    @Test fun commandIdsResolveToLibraryOnlineOrTheDownloadedFile() {
        val a = OnlineItem("aaa", "A", "X")
        val b = OnlineItem("bbb", "B", "Y")
        val downloadedB = Track(id = 20, videoId = "bbb", title = "B", artist = "Y", filePath = "/music/20.m4a")
        val out = OnlineQueue.resolve(
            ids = listOf(7, a.trackId, 99, b.trackId, onlineTrackId("unknown")),
            online = mapOf(a.trackId to a, b.trackId to b),
            library = mapOf(7L to local, 20L to downloadedB),
            byVideoId = mapOf("bbb" to downloadedB),
        )
        assertEquals(listOf(local, a.toTrack(), null, downloadedB, null), out)
    }

    @Test fun shufflingTreatsOnlineSongsLikeAnyOther() {
        val online = (1..6).map { OnlineItem("v$it", "Song $it", "Artist ${it % 3}").toTrack() }
        val items: List<Track?> = online + local
        val order = QueueShuffler.order(items, first = 2, smart = true, taste = com.villamediana.sound.smart.neutralTaste(), seed = 42)
        assertEquals(7, order.size)
        assertEquals(2, order.timelineIndexOf(0))
        assertEquals((0..6).toSet(), order.toList().toSet())
    }
}
