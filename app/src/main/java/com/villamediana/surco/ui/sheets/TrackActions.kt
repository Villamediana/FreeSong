package com.villamediana.surco.ui.sheets

import androidx.compose.runtime.Composable
import com.villamediana.surco.data.db.Track

/**
 * Bottom sheet with everything you can do with a song: play next, add to queue, like, add to
 * playlist, start radio, go to album/artist, edit info, delete from phone.
 * Placeholder — implemented by the detail module.
 */
@Composable
fun TrackActionsSheet(track: Track, onDismiss: () -> Unit, playlistContext: PlaylistContext? = null) {
}

/** When the sheet is opened from inside a playlist: adds "Remove from this playlist". */
data class PlaylistContext(val playlistId: Long, val rowId: Long)

/** Picker to add songs to an existing or new playlist. Placeholder — implemented by the detail module. */
@Composable
fun AddToPlaylistSheet(trackIds: List<Long>, onDismiss: () -> Unit) {
}
