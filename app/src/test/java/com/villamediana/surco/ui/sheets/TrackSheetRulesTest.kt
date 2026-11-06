package com.villamediana.surco.ui.sheets

import com.villamediana.surco.data.asTransientTrack
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.online.RemoteTrack
import com.villamediana.surco.ui.sheets.TrackSheetAction.ADD_TO_PLAYLIST
import com.villamediana.surco.ui.sheets.TrackSheetAction.DELETE
import com.villamediana.surco.ui.sheets.TrackSheetAction.DOWNLOAD
import com.villamediana.surco.ui.sheets.TrackSheetAction.EDIT
import com.villamediana.surco.ui.sheets.TrackSheetAction.ENQUEUE
import com.villamediana.surco.ui.sheets.TrackSheetAction.EQUALIZER
import com.villamediana.surco.ui.sheets.TrackSheetAction.GO_ALBUM
import com.villamediana.surco.ui.sheets.TrackSheetAction.GO_ARTIST
import com.villamediana.surco.ui.sheets.TrackSheetAction.LIKE
import com.villamediana.surco.ui.sheets.TrackSheetAction.PLAY_NEXT
import com.villamediana.surco.ui.sheets.TrackSheetAction.RADIO
import com.villamediana.surco.ui.sheets.TrackSheetAction.REMOVE_FROM_PLAYLIST
import com.villamediana.surco.ui.sheets.TrackSheetAction.SEND_FILE
import com.villamediana.surco.ui.sheets.TrackSheetAction.SHARE
import com.villamediana.surco.ui.sheets.TrackSheetAction.VIDEO
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
        assertEquals(listOf(DOWNLOAD, PLAY_NEXT, ENQUEUE, RADIO, VIDEO, SHARE, EQUALIZER), actions)
        for (hidden in listOf(LIKE, ADD_TO_PLAYLIST, EDIT, DELETE, REMOVE_FROM_PLAYLIST, SEND_FILE, GO_ALBUM, GO_ARTIST)) {
            assertFalse(hidden.name, hidden in actions)
        }
        assertEquals(listOf(DOWNLOAD, PLAY_NEXT, ENQUEUE, RADIO, VIDEO, SHARE), TrackSheetRules.actionsFor(streamed, false, false, false))
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
