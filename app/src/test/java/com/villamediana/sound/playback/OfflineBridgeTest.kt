package com.villamediana.sound.playback

import androidx.media3.common.PlaybackException
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.stream.StreamErrors
import com.villamediana.sound.smart.neutralTaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineBridgeTest {
    private fun mine(id: Long, genre: String = "Rock") =
        Track(id = id, title = "Mine $id", artist = "Artist $id", genre = genre, filePath = "/music/$id.m4a", durationMs = 200_000)

    private fun online(v: String) = RemoteTrack(v, "https://music.youtube.com/watch?v=$v", "New $v", "Someone").asTransientTrack()

    @Test fun anOnlineSongThatCantGetThroughIsTheConnectionsFault() {
        // No network on the phone, and a network that carries nothing (the metro): both.
        assertTrue(OfflineBridge.isConnectionLoss(StreamErrors.code(OnlineException.Kind.NO_NETWORK), currentIsOnline = true))
        assertTrue(OfflineBridge.isConnectionLoss(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, currentIsOnline = true))
        assertTrue(OfflineBridge.isConnectionLoss(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, currentIsOnline = true))
        // The song's own problem, or a song on the phone: not the connection.
        assertFalse(OfflineBridge.isConnectionLoss(StreamErrors.code(OnlineException.Kind.UNAVAILABLE), currentIsOnline = true))
        assertFalse(OfflineBridge.isConnectionLoss(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, currentIsOnline = false))
        assertFalse(OfflineBridge.isConnectionLoss(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, currentIsOnline = false))
    }

    @Test fun yourSongsLikeWhatWasHeardGoOnNoneOfThemAgain() {
        val library = List(30) { mine(it + 1L, genre = if (it < 15) "Rock" else "Samba") }
        val heard = listOf(online("a"), library[0], online("b"))
        val songs = OfflineBridge.songs(null, heard, library, neutralTaste(), seed = 7)
        assertEquals(OfflineBridge.SIZE, songs.size)
        assertTrue(songs.none { it.id == library[0].id })
        assertTrue(songs.none { it.isOnline })
        assertEquals(songs.size, songs.distinctBy { it.id }.size)
    }

    @Test fun everySongOfYoursHeardAlreadyTheOnesHeardLongestAgoComeBack() {
        val library = List(4) { mine(it + 1L) }
        val songs = OfflineBridge.songs(null, library + online("a"), library, neutralTaste(), seed = 7)
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { s -> library.any { it.id == s.id } })
    }

    @Test fun nothingOnThePhoneNothingToGoOnWith() {
        assertEquals(emptyList<Track>(), OfflineBridge.songs(null, listOf(online("a")), emptyList(), neutralTaste(), seed = 7))
    }
}
