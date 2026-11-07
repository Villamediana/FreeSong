package com.villamediana.sound.playback

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRequestsTest {
    private val song = Track(id = 7, title = "Iris", artist = "Goo Goo Dolls", filePath = "/x/7", videoId = "iris1234567")
    private val other = Track(id = 8, title = "Slide", artist = "Goo Goo Dolls", filePath = "/x/8")
    private val list = PlaySource(PlaySource.Type.LIKED, "Músicas", "liked")

    @Test fun theSongPlayingTappedAgainInItsListKeepsGoing() {
        assertTrue(PlayRequests.keepsCurrent(song, list, song, list))
        // The same list asked by title only (no id) is the same list too.
        val byTitle = PlaySource(PlaySource.Type.SONGS, "Tocadas recentemente")
        assertTrue(PlayRequests.keepsCurrent(song, byTitle, song, byTitle.copy()))
    }

    @Test fun anotherSongOrAnotherListStartsAsAsked() {
        assertFalse(PlayRequests.keepsCurrent(song, list, other, list))
        assertFalse(PlayRequests.keepsCurrent(song, list, song, PlaySource(PlaySource.Type.ALBUM, "Iris", "album")))
        assertFalse(PlayRequests.keepsCurrent(song, list, song, PlaySource(PlaySource.Type.LIKED, "Músicas", "other")))
        assertFalse(PlayRequests.keepsCurrent(null, null, song, list))
    }

    @Test fun anOnlineSongIsTheSameSongAsItsDownloadedCopy() {
        val streamed = RemoteTrack("iris1234567", "https://music.youtube.com/watch?v=iris1234567", "Iris", "Goo Goo Dolls").asTransientTrack()
        val radio = PlaySource(PlaySource.Type.RADIO, "Rádio de Iris", "iris1234567")
        assertTrue(PlayRequests.keepsCurrent(song, radio, streamed, radio))
        assertTrue(PlayRequests.keepsCurrent(streamed, radio, streamed, radio))
    }
}
