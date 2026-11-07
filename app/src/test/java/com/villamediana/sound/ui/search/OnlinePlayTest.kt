package com.villamediana.sound.ui.search

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaybackState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tap-to-play vs download, and the way back from a streamed song to a download request. */
class OnlinePlayTest {
    private val running = ItemDownload.Running(0.4f)
    private val failed = ItemDownload.Failed(9, "boom")

    @Test fun `with online play on and network every tap streams`() {
        for (state in listOf(ItemDownload.None, ItemDownload.Queued, running, ItemDownload.Done, failed)) {
            assertEquals(state.toString(), SongTapAction.PlayOnline, SongTapAction.of(state, playOnline = true, online = true))
        }
    }

    @Test fun `with online play on but no network only downloaded songs play`() {
        assertEquals(SongTapAction.PlayDownloaded, SongTapAction.of(ItemDownload.Done, playOnline = true, online = false))
        for (state in listOf(ItemDownload.None, ItemDownload.Queued, running, failed)) {
            assertEquals(state.toString(), SongTapAction.Offline, SongTapAction.of(state, playOnline = true, online = false))
        }
    }

    @Test fun `with online play off a tap downloads like before`() {
        for (online in listOf(true, false)) {
            assertEquals(SongTapAction.Download, SongTapAction.of(ItemDownload.None, playOnline = false, online = online))
            assertEquals(SongTapAction.Retry(9), SongTapAction.of(failed, playOnline = false, online = online))
            assertEquals(SongTapAction.PlayDownloaded, SongTapAction.of(ItemDownload.Done, playOnline = false, online = online))
            assertEquals(SongTapAction.Busy, SongTapAction.of(ItemDownload.Queued, playOnline = false, online = online))
            assertEquals(SongTapAction.Busy, SongTapAction.of(running, playOnline = false, online = online))
        }
    }

    @Test fun `the trailing button is always about keeping the song`() {
        assertEquals(SongTapAction.Download, SongTapAction.ofIndicator(ItemDownload.None))
        assertEquals(SongTapAction.Retry(9), SongTapAction.ofIndicator(failed))
        // Tapped again: a downloaded song leaves the phone, one on its way stops.
        assertEquals(SongTapAction.RemoveDownload, SongTapAction.ofIndicator(ItemDownload.Done))
        assertEquals(SongTapAction.RemoveDownload, SongTapAction.ofIndicator(ItemDownload.Queued))
        assertEquals(SongTapAction.RemoveDownload, SongTapAction.ofIndicator(running))
    }

    @Test fun `a streamed song goes back to the remote track it came from`() {
        val catalog = RemoteTrack("abc", "https://music.youtube.com/watch?v=abc", "De Música Ligera", "Soda Stereo", "Canción Animal", 211_000, "https://i.ytimg.com/a.jpg", fromMusicCatalog = true)
        assertEquals(catalog, catalog.asTransientTrack().toRemoteTrack())

        val video = RemoteTrack("xyz", "https://www.youtube.com/watch?v=xyz", "Soda Stereo - Persiana Americana (Official Video)", "SodaStereoVEVO", durationMs = 300_000, thumbnailUrl = "https://i.ytimg.com/x.jpg")
        assertEquals(video, video.asTransientTrack().toRemoteTrack())
    }

    @Test fun `a downloaded song keeps its link but not its local cover`() {
        val local = Track(id = 4, videoId = "abc", title = "T", artist = "A", album = " ", filePath = "/music/abc.m4a", coverPath = "/covers/abc.jpg", sourceUrl = "https://music.youtube.com/watch?v=abc")
        val remote = local.toRemoteTrack()!!
        assertEquals("https://music.youtube.com/watch?v=abc", remote.url)
        assertNull(remote.album)
        assertNull(remote.thumbnailUrl)
        assertNull(remote.durationMs)
        assertEquals(true, remote.fromMusicCatalog)
        assertNull(Track(id = 5, title = "Imported", artist = "A", filePath = "/x.mp3").toRemoteTrack())
    }

    @Test fun `now playing follows the current song by youtube id`() = runBlocking {
        val song = Track(id = 1, videoId = "a", title = "A", artist = "B", filePath = "/a")
        val states = flowOf(
            PlaybackState(),
            PlaybackState(current = song, isPlaying = true),
            PlaybackState(current = song, isPlaying = true, durationMs = 5),
            PlaybackState(current = song, isPlaying = false),
            PlaybackState(current = song.copy(videoId = null), isPlaying = true),
        )
        assertEquals(listOf(null, NowPlaying("a", true), NowPlaying("a", false), null), states.nowPlaying().toList())
        assertEquals(true, NowPlaying("a", true).of("a"))
        assertNull(NowPlaying("a", true).of("b"))
        assertNull((null as NowPlaying?).of("a"))
    }
}
