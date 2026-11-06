package com.villamediana.surco.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.villamediana.surco.ui.theme.Dimens
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

/** Quick menu for a long-pressed album or playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionMenuSheet(target: CollectionTarget, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    fun closeThen(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val pinned = when (target) {
        is CollectionTarget.Album -> target.summary.pinned
        is CollectionTarget.Playlist -> target.summary.pinned
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurcoColors.Surface2,
        contentColor = SurcoColors.Text,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(SurcoColors.Hairline))
        },
    ) {
        CollectionMenuHeader(target)
        HorizontalDivider(color = SurcoColors.Hairline, modifier = Modifier.padding(vertical = 6.dp))
        MenuAction(Icons.Rounded.PlayArrow, stringResource(R.string.play)) { closeThen { CollectionCommands.play(target, shuffle = false) } }
        MenuAction(Icons.Rounded.Shuffle, stringResource(R.string.shuffle)) { closeThen { CollectionCommands.play(target, shuffle = true) } }
        MenuAction(
            if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
            stringResource(if (pinned) R.string.home_lib_unpin else R.string.home_lib_pin),
            tint = if (pinned) SurcoColors.Accent else SurcoColors.Text,
        ) { closeThen { CollectionCommands.setPinned(target, !pinned) } }
        val shareUrl = (target as? CollectionTarget.Playlist)?.summary?.playlist?.sourceUrl?.takeIf { it.startsWith("http") }
        if (target is CollectionTarget.Playlist && shareUrl != null) {
            // Imported from YouTube: the link of the original playlist.
            MenuAction(Icons.Rounded.Share, stringResource(R.string.home_lib_share)) {
                closeThen { Share.collection(context, RemoteCollection(CollectionKind.PLAYLIST, shareUrl, target.summary.name)) }
            }
        }
        if (target is CollectionTarget.Playlist) {
            MenuAction(Icons.Outlined.DeleteOutline, stringResource(R.string.home_lib_delete_playlist), tint = SurcoColors.Danger) { confirmDelete = true }
        }
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }

    if (confirmDelete && target is CollectionTarget.Playlist) {
        DeletePlaylistDialog(
            name = target.summary.name,
            onConfirm = {
                confirmDelete = false
                closeThen { CollectionCommands.deletePlaylist(target.summary.id) }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CollectionMenuHeader(target: CollectionTarget) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (target) {
            is CollectionTarget.Album -> Cover(target.summary.coverPath, Modifier.size(52.dp), seed = target.summary.title)
            is CollectionTarget.Playlist -> {
                val s = target.summary
                if (s.coverPath != null) Cover(s.coverPath, Modifier.size(52.dp), seed = s.name)
                else MosaicCover(s.mosaic, Modifier.size(52.dp), seed = s.name)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val (title, subtitle) = when (target) {
                is CollectionTarget.Album -> target.summary.title to
                    listOfNotNull(target.summary.artist, target.summary.year?.toString()).joinToString(" · ")
                is CollectionTarget.Playlist -> target.summary.name to songCount(target.summary.trackCount)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, tint: Color = SurcoColors.Text, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tint == SurcoColors.Danger) tint else SurcoColors.Text)
    }
}

@Composable
fun DeletePlaylistDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurcoColors.Surface2,
        shape = RoundedCornerShape(22.dp),
        title = { Text(stringResource(R.string.home_lib_delete_title, name), style = MaterialTheme.typography.titleLarge, color = SurcoColors.Text) },
        text = { Text(stringResource(R.string.home_lib_delete_body), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = SurcoColors.Danger, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = SurcoColors.TextMuted, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

@Composable
internal fun songCount(count: Int): String = pluralStringResource(R.plurals.n_songs, count, count)
