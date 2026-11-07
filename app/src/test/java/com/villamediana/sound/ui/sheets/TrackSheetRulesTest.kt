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

    @Test fun `a downloaded song gets the whole menu, video and both ways to share`() {
        assertEquals(
            listOf(LIKE, PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ALBUM, GO_ARTIST, VIDEO, SHARE, SEND_FILE, EDIT, DELETE),
            TrackSheetRules.actionsFor(downloaded, fileExists = true, inPlaylist = false, fromPlayer = false),
        )
    }

    @Test fun `the player's sheet adds the equalizer and a playlist row can be removed`() {
        val fromPlayer = TrackSheetRules.actionsFor(downloaded, fileExists = true, inPlaylist = false, fromPlayer = true)
        assertTrue(EQUALIZER in fromPlayer)
        val inPlaylist = TrackSheetRules.actionsFor(downloaded, fileExists = true, inPlaylist = true, fromPlayer = false)
        assertEquals(listOf(EDIT, REMOVE_FROM_PLAYLIST, DELETE), inPlaylist.takeLast(3))
        assertFalse(EQUALIZER in inPlaylist)
    }

    @Test fun `no file, no youtube id, no album means fewer lines`() {
        val imported = Track(id = 8, title = "Demo", artist = "Banda", filePath = "/gone.mp3")
        assertEquals(
            listOf(LIKE, PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ARTIST, EDIT, DELETE),
            TrackSheetRules.actionsFor(imported, fileExists = false, inPlaylist = false, fromPlayer = false),
        )
    }

    @Test fun `a streamed song only gets what works without a library row`() {
        val actions = TrackSheetRules.actionsFor(streamed, fileExists = false, inPlaylist = true, fromPlayer = true)
        assertEquals(listOf(LIKE, DOWNLOAD, PLAY_NEXT, ENQUEUE, RADIO, VIDEO, SHARE, EQUALIZER, TrackSheetAction.SLEEP_TIMER), actions)
        for (hidden in listOf(ADD_TO_PLAYLIST, EDIT, DELETE, REMOVE_FROM_PLAYLIST, SEND_FILE, GO_ALBUM, GO_ARTIST)) {
            assertFalse(hidden.name, hidden in actions)
        }
        assertEquals(listOf(LIKE, DOWNLOAD, PLAY_NEXT, ENQUEUE, RADIO, VIDEO, SHARE), TrackSheetRules.actionsFor(streamed, false, false, false))
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
