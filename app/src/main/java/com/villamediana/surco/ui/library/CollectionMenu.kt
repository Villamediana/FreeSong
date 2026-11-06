package com.villamediana.surco.ui.library

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
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.AlbumSummary
import com.villamediana.surco.data.model.PlaylistSummary
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.ui.components.Cover
import com.villamediana.surco.ui.components.MosaicCover
import com.villamediana.surco.ui.components.Share
import com.villamediana.surco.ui.components.songCount
import com.villamediana.surco.ui.sheets.ConfirmDialog
import com.villamediana.surco.ui.sheets.SheetAction
import com.villamediana.surco.ui.sheets.SheetHeader
import com.villamediana.surco.ui.sheets.SurcoSheet
import com.villamediana.surco.ui.sheets.rememberSheetFeedback
import com.villamediana.surco.ui.theme.SurcoColors
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
    private val graph get() = SurcoApplication.graph

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

    SurcoSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CollectionMenuHeader(target)
        SheetAction(Icons.Rounded.PlayArrow, stringResource(R.string.play), onClick = { closeThen { CollectionCommands.play(target, shuffle = false) } })
        SheetAction(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), onClick = { closeThen { CollectionCommands.play(target, shuffle = true) } })
        SheetAction(
            if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
            stringResource(if (pinned) R.string.home_lib_unpin else R.string.home_lib_pin),
            onClick = { closeThen { CollectionCommands.setPinned(target, !pinned) } },
            tint = if (pinned) SurcoColors.Accent else SurcoColors.TextMuted,
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
                tint = SurcoColors.Danger,
                labelColor = SurcoColors.Danger,
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
