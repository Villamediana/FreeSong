package com.villamediana.sound.ui.sheets

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.data.shareUrl

/** One line of the song sheet. */
enum class TrackSheetAction {
    LIKE, DOWNLOAD, PLAY_NEXT, ENQUEUE, ADD_TO_PLAYLIST, RADIO, GO_ALBUM, GO_ARTIST,
    VIDEO, SHARE, SEND_FILE, EQUALIZER, SLEEP_TIMER, EDIT, REMOVE_FROM_PLAYLIST, DELETE,
}

/** Which lines the song sheet shows, in order. */
object TrackSheetRules {
    /**
     * The same short menu for every song, wherever it is opened: play next, queue, playlist, radio,
     * album, artist, share, sleep timer and — for a song on the phone — delete it. The heart and the
     * download are on the rows and in the player, the video opens from the cover.
     * A streamed song (transient, no row in the library) has no playlist, album or artist page here.
     *
     * @param fileExists kept for callers (the file itself is no longer sent from here)
     * @param inPlaylist the sheet was opened from a playlist row (it can leave the playlist)
     * @param fromPlayer kept for callers (the menu is the same in the player)
     */
    @Suppress("UNUSED_PARAMETER")
    fun actionsFor(track: Track, fileExists: Boolean, inPlaylist: Boolean, fromPlayer: Boolean): List<TrackSheetAction> = buildList {
        val canShare = track.shareUrl != null
        add(TrackSheetAction.PLAY_NEXT)
        add(TrackSheetAction.ENQUEUE)
        if (track.isOnline) {
            // Streamed: it goes into a playlist as itself (no download); album and artist open
            // their pages on YouTube Music.
            add(TrackSheetAction.ADD_TO_PLAYLIST)
            if (track.videoId != null) add(TrackSheetAction.RADIO)
            if (!track.album.isNullOrBlank()) add(TrackSheetAction.GO_ALBUM)
            add(TrackSheetAction.GO_ARTIST)
            if (canShare) add(TrackSheetAction.SHARE)
            add(TrackSheetAction.SLEEP_TIMER)
            if (inPlaylist) add(TrackSheetAction.REMOVE_FROM_PLAYLIST)
            return@buildList
        }
        add(TrackSheetAction.ADD_TO_PLAYLIST)
        add(TrackSheetAction.RADIO)
        if (track.albumKey != null) add(TrackSheetAction.GO_ALBUM)
        add(TrackSheetAction.GO_ARTIST)
        if (canShare) add(TrackSheetAction.SHARE)
        add(TrackSheetAction.SLEEP_TIMER)
        if (inPlaylist) add(TrackSheetAction.REMOVE_FROM_PLAYLIST)
        add(TrackSheetAction.DELETE)
    }

    /** Lines that need the internet right now (dimmed, with a short message, when offline). */
    fun needsNetwork(action: TrackSheetAction, track: Track): Boolean = when (action) {
        TrackSheetAction.VIDEO -> true
        TrackSheetAction.RADIO, TrackSheetAction.PLAY_NEXT, TrackSheetAction.ENQUEUE,
        TrackSheetAction.GO_ALBUM, TrackSheetAction.GO_ARTIST,
        -> track.isOnline
        else -> false
    }
}
