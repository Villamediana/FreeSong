package com.villamediana.sound.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.MosaicCover
import com.villamediana.sound.ui.components.Share
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.sheets.SheetAction
import com.villamediana.sound.ui.sheets.SheetHeader
import com.villamediana.sound.ui.sheets.SoundSheet
import com.villamediana.sound.ui.sheets.rememberSheetFeedback
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** What a long-press on an album or playlist acts on. */
sealed interface CollectionTarget {
    data class Album(val summary: AlbumSummary) : CollectionTarget
    data class Playlist(val summary: PlaylistSummary) : CollectionTarget
}

/** Play / shuffle / pin / delete for albums and playlists, run on the app scope (outlives the sheet). */
object CollectionCommands {
    private val graph get() = SoundApplication.graph

    fun play(target: CollectionTarget, shuffle: Boolean) {
        graph.scope.launch {
            val (tracks, source) = when (target) {
                is CollectionTarget.Album -> {
                    val s = target.summary
                    graph.library.album(s.key).first()?.tracks.orEmpty() to
                        PlaySource(PlaySource.Type.ALBUM, s.title, s.key.normalized)
                }
                is CollectionTarget.Playlist -> {
                    val s = target.summary
                    graph.library.playlist(s.id).first()?.tracks.orEmpty() to
                        PlaySource(PlaySource.Type.PLAYLIST, s.name, s.id.toString())
                }
            }
            playTracks(tracks, source, shuffle)
        }
    }

    suspend fun playTracks(tracks: List<Track>, source: PlaySource, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val start = if (shuffle) Random.nextInt(tracks.size) else 0
        withContext(Dispatchers.Main) { graph.player.play(tracks, start, source, shuffle) }
    }

    fun setPinned(target: CollectionTarget, pinned: Boolean) {
        graph.scope.launch {
            when (target) {
                is CollectionTarget.Album -> graph.library.setAlbumPinned(target.summary.key, pinned)
                is CollectionTarget.Playlist -> graph.library.setPlaylistPinned(target.summary.id, pinned)
            }
        }
    }

    fun deletePlaylist(id: Long) {
        graph.scope.launch { graph.library.deletePlaylist(id) }
    }
}

/** Quick menu for a long-pressed album or playlist — same sheet and dialog as the collection's own ⋮. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionMenuSheet(target: CollectionTarget, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val feedback = rememberSheetFeedback()
    var confirmDelete by remember { mutableStateOf(false) }

    fun closeThen(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val pinned = when (target) {
        is CollectionTarget.Album -> target.summary.pinned
        is CollectionTarget.Playlist -> target.summary.pinned
    }

    SoundSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CollectionMenuHeader(target)
        SheetAction(Icons.Rounded.PlayArrow, stringResource(R.string.play), onClick = { closeThen { CollectionCommands.play(target, shuffle = false) } })
        SheetAction(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), onClick = { closeThen { CollectionCommands.play(target, shuffle = true) } })
        SheetAction(
            if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
            stringResource(if (pinned) R.string.home_lib_unpin else R.string.home_lib_pin),
            onClick = { closeThen { CollectionCommands.setPinned(target, !pinned) } },
            tint = if (pinned) SoundColors.Accent else SoundColors.TextMuted,
        )
        val shareUrl = (target as? CollectionTarget.Playlist)?.summary?.playlist?.sourceUrl?.takeIf { it.startsWith("http") }
        if (target is CollectionTarget.Playlist && shareUrl != null) {
            // Imported from YouTube: the link of the original playlist.
            SheetAction(Icons.Rounded.Share, stringResource(R.string.home_lib_share), onClick = {
                closeThen { Share.collection(context, RemoteCollection(CollectionKind.PLAYLIST, shareUrl, target.summary.name)) }
            })
        }
        if (target is CollectionTarget.Playlist) {
            SheetAction(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.home_lib_delete_playlist),
                onClick = { confirmDelete = true },
                tint = SoundColors.Danger,
                labelColor = SoundColors.Danger,
            )
        }
    }

    if (confirmDelete && target is CollectionTarget.Playlist) {
        val name = target.summary.name
        ConfirmDialog(
            title = stringResource(R.string.detail_delete_playlist_title),
            body = stringResource(R.string.detail_delete_playlist_body, name),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                confirmDelete = false
                closeThen { CollectionCommands.deletePlaylist(target.summary.id) }
                feedback.show(context.getString(R.string.detail_playlist_deleted, name))
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CollectionMenuHeader(target: CollectionTarget) {
    val (title, subtitle) = when (target) {
        is CollectionTarget.Album -> target.summary.title to
            listOfNotNull(target.summary.artist, target.summary.year?.toString()).joinToString(" · ")
        is CollectionTarget.Playlist -> target.summary.name to songCount(target.summary.trackCount)
    }
    SheetHeader(title = title, subtitle = subtitle, art = {
        when (target) {
            is CollectionTarget.Album -> Cover(target.summary.coverPath, Modifier.fillMaxSize(), seed = target.summary.title)
            is CollectionTarget.Playlist -> {
                val s = target.summary
                if (s.coverPath != null) Cover(s.coverPath, Modifier.fillMaxSize(), seed = s.name)
                else MosaicCover(s.mosaic, Modifier.fillMaxSize(), seed = s.name)
            }
        }
    })
}
