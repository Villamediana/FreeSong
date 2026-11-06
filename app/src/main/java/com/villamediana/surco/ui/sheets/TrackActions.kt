package com.villamediana.surco.ui.sheets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.PlaylistSummary
import com.villamediana.surco.playback.SmartSeed
import com.villamediana.surco.ui.components.Cover
import com.villamediana.surco.ui.components.MosaicCover
import com.villamediana.surco.ui.components.PillButton
import com.villamediana.surco.ui.nav.LocalNavigator
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TrackSheetStage { Actions, AddToPlaylist, Edit, ConfirmDelete }

/**
 * Bottom sheet with everything you can do with a song: like, play next, add to queue, add to
 * playlist, start radio, go to album/artist, edit info, remove from this playlist, delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(track: Track, onDismiss: () -> Unit, playlistContext: PlaylistContext? = null) {
    val graph = SurcoApplication.graph
    val library = graph.library
    val player = graph.player
    val navigator = LocalNavigator.current
    val feedback = rememberSheetFeedback()
    val haptic = LocalHapticFeedback.current
    val res = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stage by rememberSaveable(track.id) { mutableStateOf(TrackSheetStage.Actions) }

    // Live row: the heart reflects changes made elsewhere; deleted elsewhere → close.
    val live by remember(track.id) { library.track(track.id) }.collectAsStateWithLifecycle(initialValue = track)
    LaunchedEffect(live == null) { if (live == null) onDismiss() }
    val t = live ?: track

    /** Slides the sheet away, then runs [next] (dismiss, navigate, open a dialog…). */
    fun closeThen(next: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { next() }
    }

    when (stage) {
        TrackSheetStage.Actions -> SurcoSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            SheetHeader(
                title = t.title,
                subtitle = listOfNotNull(t.artist, t.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                art = { Cover(t.coverPath, Modifier.fillMaxSize(), seed = t.album ?: t.title) },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetAction(
                    icon = if (t.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    label = stringResource(if (t.liked) R.string.sheet_unlike else R.string.sheet_like),
                    tint = if (t.liked) SurcoColors.Liked else SurcoColors.TextMuted,
                    onClick = {
                        val nowLiked = !t.liked
                        haptic.performHapticFeedback(if (nowLiked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        graph.scope.launch { library.setLiked(t.id, nowLiked) }
                        feedback.show(res.getString(if (nowLiked) R.string.sheet_liked_done else R.string.sheet_unliked_done))
                        closeThen(onDismiss)
                    },
                )
                SheetAction(Icons.Rounded.SubdirectoryArrowRight, stringResource(R.string.sheet_play_next), onClick = {
                    player.playNext(listOf(t))
                    feedback.show(res.getString(R.string.sheet_play_next_done))
                    closeThen(onDismiss)
                })
                SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.sheet_enqueue), onClick = {
                    player.enqueue(listOf(t))
                    feedback.show(res.getString(R.string.sheet_enqueue_done))
                    closeThen(onDismiss)
                })
                SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.sheet_add_to_playlist), onClick = {
                    closeThen { stage = TrackSheetStage.AddToPlaylist }
                })
                SheetAction(Icons.Rounded.Radio, stringResource(R.string.sheet_start_radio), onClick = {
                    player.playSmart(SmartSeed.Radio(t))
                    feedback.show(res.getString(R.string.sheet_radio_done, t.title))
                    closeThen(onDismiss)
                })
                t.albumKey?.let { key ->
                    SheetAction(Icons.Rounded.Album, stringResource(R.string.sheet_go_album), onClick = {
                        closeThen { onDismiss(); navigator.openAlbum(key) }
                    })
                }
                SheetAction(Icons.Rounded.Person, stringResource(R.string.sheet_go_artist), onClick = {
                    closeThen { onDismiss(); navigator.openArtist(t.artist) }
                })
                SheetAction(Icons.Rounded.Edit, stringResource(R.string.sheet_edit), onClick = {
                    closeThen { stage = TrackSheetStage.Edit }
                })
                if (playlistContext != null) {
                    SheetAction(Icons.Rounded.PlaylistRemove, stringResource(R.string.sheet_remove_from_playlist), onClick = {
                        removeFromPlaylist(library, graph.scope, t, playlistContext, feedback, res.getString(R.string.sheet_removed_from_playlist), res.getString(R.string.sheet_undo))
                        closeThen(onDismiss)
                    })
                }
                SheetAction(
                    Icons.Rounded.DeleteOutline,
                    stringResource(R.string.sheet_delete_from_phone),
                    tint = SurcoColors.Danger,
                    labelColor = SurcoColors.Danger,
                    onClick = { closeThen { stage = TrackSheetStage.ConfirmDelete } },
                )
            }
        }

        TrackSheetStage.AddToPlaylist -> AddToPlaylistSheet(trackIds = listOf(t.id), onDismiss = onDismiss)

        TrackSheetStage.Edit -> EditTrackDialog(
            track = t,
            onDismiss = onDismiss,
            onSave = { e ->
                graph.scope.launch {
                    library.editMetadata(t.id, e.title, e.artist, e.album, e.albumArtist, e.genre, e.year, e.trackNumber)
                }
                feedback.show(res.getString(R.string.sheet_edit_saved))
                onDismiss()
            },
        )

        TrackSheetStage.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.sheet_delete_title),
            body = stringResource(R.string.sheet_delete_body, t.title),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                graph.scope.launch { library.deleteTracks(listOf(t.id)) }
                feedback.show(res.getString(R.string.sheet_deleted))
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** Removes one row of a playlist and offers "Desfazer", which puts it back in the same slot. */
private fun removeFromPlaylist(
    library: LibraryRepository,
    appScope: CoroutineScope,
    track: Track,
    ctx: PlaylistContext,
    feedback: SheetFeedback,
    message: String,
    undo: String,
) {
    appScope.launch {
        val position = library.playlist(ctx.playlistId).first()?.entries?.firstOrNull { it.rowId == ctx.rowId }?.position
        library.removeFromPlaylist(ctx.playlistId, ctx.rowId)
        withContext(Dispatchers.Main) {
            feedback.show(message, undo) {
                appScope.launch {
                    if (position != null) library.addToPlaylistAt(ctx.playlistId, track.id, position)
                    else library.addToPlaylist(ctx.playlistId, listOf(track.id))
                }
            }
        }
    }
}

/** When the sheet is opened from inside a playlist: adds "Remove from this playlist". */
data class PlaylistContext(val playlistId: Long, val rowId: Long)

/** Picker to add songs to an existing or new playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(trackIds: List<Long>, onDismiss: () -> Unit) {
    val graph = SurcoApplication.graph
    val library = graph.library
    val feedback = rememberSheetFeedback()
    val haptic = LocalHapticFeedback.current
    val res = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val playlists by library.playlists.collectAsStateWithLifecycle(initialValue = null)
    val membership by remember { playlistMembership(library) }.collectAsStateWithLifecycle(initialValue = emptyMap())
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var addedTo by remember { mutableStateOf<Long?>(null) }
    val ids = remember(trackIds) { trackIds.distinct() }

    fun finish(message: String) {
        feedback.show(message)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun addTo(p: PlaylistSummary) {
        if (addedTo != null || ids.isEmpty()) return
        addedTo = p.id
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            // The write runs in the app scope: it finishes even if the sheet is swiped away meanwhile.
            val added = graph.scope.async { library.addToPlaylist(p.id, ids) }.await()
            delay(320) // let the check land before the sheet slides away
            finish(
                if (added == 0) res.getString(R.string.sheet_already_in, p.name)
                else res.getQuantityString(R.plurals.sheet_added_to, added, added, p.name),
            )
        }
    }

    fun create() {
        val playlistName = name.trim()
        if (playlistName.isEmpty() || addedTo != null) return
        addedTo = -1L
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            val added = graph.scope.async {
                val id = library.createPlaylist(playlistName)
                if (ids.isEmpty()) 0 else library.addToPlaylist(id, ids)
            }.await()
            finish(
                if (added == 0) res.getString(R.string.sheet_playlist_created, playlistName)
                else res.getQuantityString(R.plurals.sheet_added_to, added, added, playlistName),
            )
        }
    }

    SurcoSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(
            title = stringResource(R.string.sheet_add_title),
            subtitle = pluralStringResource(R.plurals.n_songs, ids.size, ids.size),
        )
        AnimatedContent(
            targetState = creating,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "new-playlist",
        ) { isCreating ->
            if (!isCreating) {
                NewPlaylistRow(onClick = { creating = true })
            } else {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurcoTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        label = stringResource(R.string.sheet_new_playlist_name),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        imeAction = ImeAction.Done,
                        onDone = { create() },
                    )
                    Spacer(Modifier.width(12.dp))
                    PillButton(
                        text = stringResource(R.string.sheet_create),
                        onClick = { create() },
                        filled = true,
                        enabled = name.isNotBlank() && addedTo == null,
                    )
                }
            }
        }
        HorizontalDivider(color = SurcoColors.Hairline, thickness = 1.dp)
        val lists = playlists
        if (lists != null && lists.isEmpty()) {
            Text(
                stringResource(R.string.sheet_no_playlists),
                style = MaterialTheme.typography.bodyMedium,
                color = SurcoColors.TextMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                items(lists.orEmpty(), key = { it.id }) { p ->
                    val inside = membership[p.id]?.containsAll(ids) == true
                    PlaylistPickRow(
                        playlist = p,
                        checked = inside || addedTo == p.id,
                        onClick = {
                            if (inside) finish(res.getString(R.string.sheet_already_in, p.name)) else addTo(p)
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(Dimens.CoverCorner)).background(SurcoColors.Surface3),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, null, tint = SurcoColors.Text, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.sheet_new_playlist), style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text)
    }
}

@Composable
private fun PlaylistPickRow(playlist: PlaylistSummary, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverPath = playlist.coverPath
        if (coverPath != null) Cover(coverPath, Modifier.size(48.dp), seed = playlist.name)
        else MosaicCover(playlist.mosaic, Modifier.size(48.dp), seed = playlist.name)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                pluralStringResource(R.plurals.n_songs, playlist.trackCount, playlist.trackCount),
                style = MaterialTheme.typography.bodyMedium,
                color = SurcoColors.TextMuted,
            )
        }
        AnimatedVisibility(visible = checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Rounded.CheckCircle, stringResource(R.string.sheet_in_playlist), tint = SurcoColors.Accent, modifier = Modifier.size(24.dp))
        }
    }
}

/** playlistId → ids of the songs in it, kept live. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun playlistMembership(library: LibraryRepository): Flow<Map<Long, Set<Long>>> =
    library.playlists
        .map { lists -> lists.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val perPlaylist: List<Flow<Pair<Long, Set<Long>>>> = ids.map { id ->
                    library.playlist(id).map { d ->
                        val songs: Set<Long> = d?.entries?.mapTo(HashSet<Long>()) { it.track.id } ?: emptySet()
                        id to songs
                    }
                }
                combine(perPlaylist) { pairs -> pairs.toMap() }
            }
        }
