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
     * A song on the phone gets the whole menu. A streamed song (transient, no row in the library)
     * only gets what makes sense without one: like it (Curtidas keeps streamed songs), download it,
     * queue it, a radio from it, share it, watch its video — nothing that edits, deletes or files
     * it into a playlist.
     *
     * @param fileExists the audio file is on the phone (needed to send the file itself)
     * @param inPlaylist the sheet was opened from a playlist row
     * @param fromPlayer the sheet was opened from the player (adds the sleep timer; drops what the
     *   player already shows: heart, download, equalizer, "play next")
     */
    fun actionsFor(track: Track, fileExists: Boolean, inPlaylist: Boolean, fromPlayer: Boolean): List<TrackSheetAction> = buildList {
        val hasVideo = track.videoId != null
        val canShare = track.shareUrl != null
        // From the player the heart and the download sit beside the title, the equalizer has its
        // own button, and "play next" of the song playing makes no sense: none of them here.
        if (track.isOnline) {
            if (hasVideo && !fromPlayer) add(TrackSheetAction.LIKE)
            if (!fromPlayer) add(TrackSheetAction.DOWNLOAD)
            if (!fromPlayer) add(TrackSheetAction.PLAY_NEXT)
            add(TrackSheetAction.ENQUEUE)
            if (hasVideo) add(TrackSheetAction.RADIO)
            if (hasVideo) add(TrackSheetAction.VIDEO)
            if (canShare) add(TrackSheetAction.SHARE)
            if (fromPlayer) add(TrackSheetAction.SLEEP_TIMER)
            return@buildList
        }
        if (!fromPlayer) add(TrackSheetAction.LIKE)
        if (!fromPlayer) add(TrackSheetAction.PLAY_NEXT)
        add(TrackSheetAction.ENQUEUE)
        add(TrackSheetAction.ADD_TO_PLAYLIST)
        add(TrackSheetAction.RADIO)
        if (track.albumKey != null) add(TrackSheetAction.GO_ALBUM)
        add(TrackSheetAction.GO_ARTIST)
        if (hasVideo) add(TrackSheetAction.VIDEO)
        if (canShare) add(TrackSheetAction.SHARE)
        if (fileExists) add(TrackSheetAction.SEND_FILE)
        if (fromPlayer) add(TrackSheetAction.SLEEP_TIMER)
        add(TrackSheetAction.EDIT)
        if (inPlaylist) add(TrackSheetAction.REMOVE_FROM_PLAYLIST)
        add(TrackSheetAction.DELETE)
    }

    /** Lines that need the internet right now (dimmed, with a short message, when offline). */
    fun needsNetwork(action: TrackSheetAction, track: Track): Boolean = when (action) {
        TrackSheetAction.VIDEO -> true
        TrackSheetAction.RADIO, TrackSheetAction.PLAY_NEXT, TrackSheetAction.ENQUEUE -> track.isOnline
        else -> false
    }
}
