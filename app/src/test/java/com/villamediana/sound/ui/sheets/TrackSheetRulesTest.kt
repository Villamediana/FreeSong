package com.villamediana.sound.ui.sheets

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.ui.sheets.TrackSheetAction.ADD_TO_PLAYLIST
import com.villamediana.sound.ui.sheets.TrackSheetAction.DELETE
import com.villamediana.sound.ui.sheets.TrackSheetAction.DOWNLOAD
import com.villamediana.sound.ui.sheets.TrackSheetAction.EDIT
import com.villamediana.sound.ui.sheets.TrackSheetAction.ENQUEUE
import com.villamediana.sound.ui.sheets.TrackSheetAction.EQUALIZER
import com.villamediana.sound.ui.sheets.TrackSheetAction.GO_ALBUM
import com.villamediana.sound.ui.sheets.TrackSheetAction.GO_ARTIST
import com.villamediana.sound.ui.sheets.TrackSheetAction.LIKE
import com.villamediana.sound.ui.sheets.TrackSheetAction.PLAY_NEXT
import com.villamediana.sound.ui.sheets.TrackSheetAction.RADIO
import com.villamediana.sound.ui.sheets.TrackSheetAction.REMOVE_FROM_PLAYLIST
import com.villamediana.sound.ui.sheets.TrackSheetAction.SEND_FILE
import com.villamediana.sound.ui.sheets.TrackSheetAction.SHARE
import com.villamediana.sound.ui.sheets.TrackSheetAction.VIDEO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which lines the song sheet shows for songs on the phone and for streamed ones. */
class TrackSheetRulesTest {
    private val downloaded = Track(
        id = 7, videoId = "abc", title = "Signos", artist = "Soda Stereo", album = "Signos",
        filePath = "/music/abc.m4a", sourceUrl = "https://music.youtube.com/watch?v=abc",
    )
    private val streamed = RemoteTrack("xyz", "https://music.youtube.com/watch?v=xyz", "Prófugos", "Soda Stereo", fromMusicCatalog = true).asTransientTrack()

    private val SLEEP = TrackSheetAction.SLEEP_TIMER
    private val gone = listOf(LIKE, DOWNLOAD, VIDEO, SEND_FILE, EDIT, EQUALIZER)

    @Test fun `every song gets the same short menu, wherever it is opened`() {
        val menu = listOf(PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ALBUM, GO_ARTIST, SHARE, SLEEP, DELETE)
        assertEquals(menu, TrackSheetRules.actionsFor(downloaded, fileExists = true, inPlaylist = false, fromPlayer = false))
        assertEquals(menu, TrackSheetRules.actionsFor(downloaded, fileExists = true, inPlaylist = false, fromPlayer = true))
        // Heart and download on the rows/player, video from the cover, no file sending, no editing.
        for (g in gone) assertFalse(g.name, g in TrackSheetRules.actionsFor(downloaded, true, false, false))
    }

    @Test fun `a playlist row can leave the playlist`() {
        val inPlaylist = TrackSheetRules.actionsFor(downloaded, fileExists = true, inPlaylist = true, fromPlayer = false)
        assertEquals(listOf(SLEEP, REMOVE_FROM_PLAYLIST, DELETE), inPlaylist.takeLast(3))
    }

    @Test fun `no youtube id, no album means fewer lines`() {
        val imported = Track(id = 8, title = "Demo", artist = "Banda", filePath = "/gone.mp3")
        assertEquals(
            listOf(PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ARTIST, SLEEP, DELETE),
            TrackSheetRules.actionsFor(imported, fileExists = false, inPlaylist = false, fromPlayer = false),
        )
    }

    @Test fun `a streamed song goes into playlists and opens its online album and artist, no delete`() {
        assertEquals(
            listOf(PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ARTIST, SHARE, SLEEP),
            TrackSheetRules.actionsFor(streamed, false, false, false),
        )
        val withAlbum = streamed.copy(album = "Signos")
        assertEquals(
            listOf(PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ALBUM, GO_ARTIST, SHARE, SLEEP, REMOVE_FROM_PLAYLIST),
            TrackSheetRules.actionsFor(withAlbum, fileExists = false, inPlaylist = true, fromPlayer = true),
        )
        assertTrue(TrackSheetRules.needsNetwork(GO_ARTIST, streamed))
        assertFalse(TrackSheetRules.needsNetwork(GO_ARTIST, downloaded))
    }

    @Test fun `what needs the internet`() {
        assertTrue(TrackSheetRules.needsNetwork(VIDEO, downloaded))
        assertTrue(TrackSheetRules.needsNetwork(VIDEO, streamed))
        // A song on the phone queues and makes its radio offline.
        assertFalse(TrackSheetRules.needsNetwork(PLAY_NEXT, downloaded))
        assertFalse(TrackSheetRules.needsNetwork(RADIO, downloaded))
        assertTrue(TrackSheetRules.needsNetwork(PLAY_NEXT, streamed))
        assertTrue(TrackSheetRules.needsNetwork(ENQUEUE, streamed))
        assertTrue(TrackSheetRules.needsNetwork(RADIO, streamed))
        // Downloads wait in the queue for the network; sharing a link works offline.
        assertFalse(TrackSheetRules.needsNetwork(DOWNLOAD, streamed))
        assertFalse(TrackSheetRules.needsNetwork(SHARE, streamed))
    }
}
