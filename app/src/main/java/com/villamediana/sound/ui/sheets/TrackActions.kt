package com.villamediana.sound.ui.sheets

import androidx.compose.material.icons.rounded.MusicVideo
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Bedtime
import android.content.res.Resources
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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartDisplay
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.sound.AppGraph
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.MosaicCover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.Share
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.search.Connectivity
import com.villamediana.sound.ui.search.DownloadLookup
import com.villamediana.sound.ui.search.ItemDownload
import com.villamediana.sound.ui.search.toRemoteTrack
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
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
import java.io.File
import kotlin.math.roundToInt

private enum class TrackSheetStage { Actions, AddToPlaylist, Edit, ConfirmDelete }

/**
 * Bottom sheet with everything you can do with a song: like, play next, add to queue, add to
 * playlist, start radio, go to album/artist, watch the video, share it (link or file), edit info,
 * remove from this playlist, delete. A streamed song (not on the phone) gets the short version —
 * download, queue, radio, video, share — until it is downloaded. [showEqualizer] adds the
 * equalizer (the sheet of the player's ⋮).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: Track,
    onDismiss: () -> Unit,
    playlistContext: PlaylistContext? = null,
    showEqualizer: Boolean = false,
    /** From the player: "Timer para dormir" opens its sheet. */
    onSleepTimer: (() -> Unit)? = null,
) {
    val graph = SoundApplication.graph
    val library = graph.library
    val player = graph.player
    val navigator = LocalNavigator.current
    val feedback = rememberSheetFeedback()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val res = context.resources
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stage by rememberSaveable(track.id) { mutableStateOf(TrackSheetStage.Actions) }

    // Live row: the heart reflects changes made elsewhere; deleted elsewhere → close. A streamed
    // song follows its YouTube id instead: once downloaded, the sheet becomes the full one.
    val streamedIn = track.isOnline
    val liveFlow = remember(track.id, track.videoId) {
        val videoId = track.videoId
        when {
            !streamedIn -> library.track(track.id)
            videoId != null -> library.tracks.map { all -> all.firstOrNull { it.videoId == videoId } }.distinctUntilChanged()
            else -> flowOf(null)
        }
    }
    val live by liveFlow.collectAsStateWithLifecycle(initialValue = if (streamedIn) null else track)
    LaunchedEffect(live == null) { if (!streamedIn && live == null) onDismiss() }
    val t = live ?: track

    val online by remember(context) { Connectivity.online(context) }.collectAsStateWithLifecycle(initialValue = remember(context) { Connectivity.isOnline(context) })
    val download by remember(t.id, t.videoId) {
        val videoId = t.videoId
        if (t.isOnline && videoId != null) {
            combine(library.downloadedVideoIds, graph.downloads.tasks) { done, tasks -> DownloadLookup.build(done, tasks).stateOf(videoId) }.distinctUntilChanged()
        } else {
            flowOf(ItemDownload.None)
        }
    }.collectAsStateWithLifecycle(initialValue = ItemDownload.None)
    val fileExists = remember(t.filePath, t.isOnline) { !t.isOnline && runCatching { File(t.filePath).isFile }.getOrDefault(false) }
    val actions = TrackSheetRules.actionsFor(t, fileExists, inPlaylist = playlistContext != null, fromPlayer = showEqualizer)
    // A streamed song is liked by its video id (Curtidas keeps it without a library row).
    val likedIds by graph.library.likedVideoIds.collectAsState(initial = emptySet())
    val isLiked = if (t.isOnline) t.videoId in likedIds else t.liked

    /** Slides the sheet away, then runs [next] (dismiss, navigate, open a dialog…). */
    fun closeThen(next: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { next() }
    }

    fun offline() {
        feedback.show(res.getString(R.string.sheet_offline))
        closeThen(onDismiss)
    }

    when (stage) {
        TrackSheetStage.Actions -> SoundSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            SheetHeader(
                title = t.title,
                subtitle = listOfNotNull(
                    t.artist,
                    t.album?.takeIf { it.isNotBlank() },
                    res.getString(R.string.sheet_streamed).takeIf { t.isOnline },
                ).joinToString(" · "),
                art = { Cover(t.coverPath, Modifier.fillMaxSize(), seed = t.album ?: t.title) },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                actions.forEach { action ->
                    val dimmed = !online && TrackSheetRules.needsNetwork(action, t)
                    when (action) {
                        TrackSheetAction.LIKE -> SheetAction(
                            icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            label = stringResource(if (isLiked) R.string.sheet_unlike else R.string.sheet_like),
                            tint = if (isLiked) SoundColors.Liked else SoundColors.TextMuted,
                            onClick = {
                                val nowLiked = !isLiked
                                haptic.performHapticFeedback(if (nowLiked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                val remote = if (t.isOnline) t.toRemoteTrack() else null
                                if (remote != null) graph.likeOnline(remote, nowLiked)
                                else graph.likeTrack(t.id, nowLiked)
                                feedback.show(res.getString(if (nowLiked) R.string.sheet_liked_done else R.string.sheet_unliked_done))
                                closeThen(onDismiss)
                            },
                        )
                        TrackSheetAction.DOWNLOAD -> {
                            val busy = download == ItemDownload.Queued || download is ItemDownload.Running
                            val running = download as? ItemDownload.Running
                            SheetAction(
                                icon = Icons.Rounded.Download,
                                label = stringResource(if (busy) R.string.sheet_downloading else R.string.sheet_download),
                                tint = SoundColors.Accent,
                                dimmed = busy,
                                trailing = when {
                                    running != null -> stringResource(R.string.search_link_percent, (running.progress * 100).roundToInt())
                                    busy -> stringResource(R.string.search_dl_queued)
                                    !online -> stringResource(R.string.sheet_when_online)
                                    else -> null
                                },
                                onClick = {
                                    if (!busy) {
                                        t.toRemoteTrack()?.let { remote ->
                                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                            graph.scope.launch { runCatching { graph.downloads.download(listOf(remote)) } }
                                            feedback.show(res.getString(R.string.sheet_download_started, t.title))
                                        }
                                    }
                                    closeThen(onDismiss)
                                },
                            )
                        }
                        TrackSheetAction.PLAY_NEXT -> SheetAction(Icons.Rounded.SubdirectoryArrowRight, stringResource(R.string.sheet_play_next), dimmed = dimmed, onClick = {
                            if (dimmed) return@SheetAction offline()
                            player.playNext(listOf(t))
                            feedback.show(res.getString(R.string.sheet_play_next_done))
                            closeThen(onDismiss)
                        })
                        TrackSheetAction.ENQUEUE -> SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.sheet_enqueue), dimmed = dimmed, onClick = {
                            if (dimmed) return@SheetAction offline()
                            player.enqueue(listOf(t))
                            feedback.show(res.getString(R.string.sheet_enqueue_done))
                            closeThen(onDismiss)
                        })
                        TrackSheetAction.ADD_TO_PLAYLIST -> SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.sheet_add_to_playlist), onClick = {
                            closeThen { stage = TrackSheetStage.AddToPlaylist }
                        })
                        TrackSheetAction.RADIO -> SheetAction(Icons.Rounded.Sensors, stringResource(R.string.sheet_start_radio), dimmed = dimmed, onClick = {
                            when {
                                dimmed -> offline()
                                // Online, a song from YouTube gets YouTube Music's radio (new songs alike);
                                // offline, the radio of what is on the phone.
                                t.isOnline || (t.videoId != null && online) -> {
                                    startOnlineRadio(t, graph, feedback, res)
                                    closeThen(onDismiss)
                                }
                                else -> {
                                    player.playSmart(SmartSeed.Radio(t))
                                    feedback.show(res.getString(R.string.sheet_radio_done, t.title))
                                    closeThen(onDismiss)
                                }
                            }
                        })
                        TrackSheetAction.GO_ALBUM -> when {
                            // A streamed song: its album's page on YouTube Music.
                            t.isOnline -> t.album?.takeIf { it.isNotBlank() }?.let { album ->
                                SheetAction(Icons.Rounded.Album, stringResource(R.string.sheet_go_album), dimmed = dimmed, onClick = {
                                    if (dimmed) offline() else openOnline(graph, feedback, res.getString(R.string.sheet_album_not_found), navigator, onDismiss) {
                                        graph.discover.album(album, com.villamediana.sound.stats.Recaps.mainArtist(t.artist))
                                    }
                                })
                            }
                            else -> t.albumKey?.let { key ->
                                SheetAction(Icons.Rounded.Album, stringResource(R.string.sheet_go_album), onClick = {
                                    closeThen { onDismiss(); navigator.openAlbum(key) }
                                })
                            }
                        }
                        TrackSheetAction.GO_ARTIST -> if (t.isOnline) {
                            // A streamed song: the artist's page on YouTube Music.
                            SheetAction(Icons.Rounded.Person, stringResource(R.string.sheet_go_artist), dimmed = dimmed, onClick = {
                                if (dimmed) offline() else openOnline(graph, feedback, res.getString(R.string.sheet_artist_not_found), navigator, onDismiss) {
                                    graph.discover.artist(com.villamediana.sound.stats.Recaps.mainArtist(t.artist))
                                }
                            })
                        } else {
                            SheetAction(Icons.Rounded.Person, stringResource(R.string.sheet_go_artist), onClick = {
                                closeThen { onDismiss(); navigator.openArtist(t.artist) }
                            })
                        }
                        TrackSheetAction.VIDEO -> t.videoId?.let { videoId ->
                            SheetAction(Icons.Rounded.MusicVideo, stringResource(R.string.sheet_video), dimmed = dimmed, onClick = {
                                if (dimmed) offline() else closeThen { onDismiss(); navigator.openVideo(videoId, t.title) }
                            })
                        }
                        TrackSheetAction.SHARE -> SheetAction(Icons.Rounded.Share, stringResource(R.string.sheet_share), onClick = {
                            closeThen { onDismiss(); Share.trackLink(context, t) }
                        })
                        TrackSheetAction.SEND_FILE -> SheetAction(Icons.Rounded.AttachFile, stringResource(R.string.sheet_send_file), onClick = {
                            closeThen { onDismiss(); Share.trackFile(context, t) }
                        })
                        TrackSheetAction.SLEEP_TIMER -> SheetAction(Icons.Rounded.Bedtime, stringResource(R.string.sheet_sleep_timer), onClick = {
                            closeThen { onDismiss(); onSleepTimer?.invoke() }
                        })
                        TrackSheetAction.EQUALIZER -> SheetAction(Icons.Rounded.Equalizer, stringResource(R.string.sheet_equalizer), onClick = {
                            closeThen { onDismiss(); navigator.openEqualizer() }
                        })
                        TrackSheetAction.EDIT -> SheetAction(Icons.Rounded.Edit, stringResource(R.string.sheet_edit), onClick = {
                            closeThen { stage = TrackSheetStage.Edit }
                        })
                        TrackSheetAction.REMOVE_FROM_PLAYLIST -> playlistContext?.let { ctx ->
                            SheetAction(Icons.Rounded.PlaylistRemove, stringResource(R.string.sheet_remove_from_playlist), onClick = {
                                removeFromPlaylist(library, graph.scope, t, ctx, feedback, res.getString(R.string.sheet_removed_from_playlist), res.getString(R.string.sheet_undo))
                                closeThen(onDismiss)
                            })
                        }
                        TrackSheetAction.DELETE -> SheetAction(
                            Icons.Rounded.DeleteOutline,
                            stringResource(R.string.sheet_delete_from_phone),
                            tint = SoundColors.Danger,
                            labelColor = SoundColors.Danger,
                            onClick = { closeThen { stage = TrackSheetStage.ConfirmDelete } },
                        )
                    }
                }
            }
        }

        TrackSheetStage.AddToPlaylist -> AddToPlaylistSheet(
            trackIds = listOf(t.id),
            onDismiss = onDismiss,
            // A streamed song goes in as itself (it plays online; nothing is downloaded).
            online = if (t.isOnline) t.toRemoteTrack() else null,
        )

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

/**
 * "Iniciar rádio" for a streamed song: the song, then YouTube Music's "up next" for it, streamed.
 * Runs on the app scope (the sheet is gone by then).
 */
private fun startOnlineRadio(track: Track, graph: AppGraph, feedback: SheetFeedback, res: Resources) {
    val seed = track.toRemoteTrack() ?: return
    graph.scope.launch {
        val related = runCatching { graph.youTube.related(seed.videoId) }.getOrDefault(emptyList())
            .filter { it.videoId != seed.videoId }
            .distinctBy { it.videoId }
        withContext(Dispatchers.Main) {
            if (related.isEmpty()) {
                feedback.show(res.getString(R.string.sheet_radio_unavailable))
            } else {
                graph.player.playOnline(listOf(seed) + related, 0, PlaySource(PlaySource.Type.RADIO, res.getString(R.string.playback_source_radio, track.title), seed.videoId))
                feedback.show(res.getString(R.string.sheet_radio_done, track.title))
            }
        }
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
                    library.putBackInPlaylist(ctx.playlistId, track, position)
                }
            }
        }
    }
}

/** Finds an online page (album, artist) and opens it; says so when there is none. */
private fun openOnline(
    graph: AppGraph,
    feedback: SheetFeedback,
    notFound: String,
    navigator: com.villamediana.sound.ui.nav.Navigator,
    onDismiss: () -> Unit,
    find: suspend () -> com.villamediana.sound.online.RemoteCollection?,
) {
    graph.scope.launch {
        val page = runCatching { find() }.getOrNull()
        withContext(Dispatchers.Main) {
            if (page != null) {
                onDismiss()
                navigator.openRemote(page)
            } else {
                feedback.show(notFound)
            }
        }
    }
}

/** When the sheet is opened from inside a playlist: adds "Remove from this playlist". */
data class PlaylistContext(val playlistId: Long, val rowId: Long)

/** Picker to add songs to an existing or new playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(trackIds: List<Long>, onDismiss: () -> Unit, online: com.villamediana.sound.online.RemoteTrack? = null) {
    val graph = SoundApplication.graph
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
            val added = graph.scope.async {
                if (online != null) (if (library.addOnlineToPlaylist(p.id, online)) 1 else 0) else library.addToPlaylist(p.id, ids)
            }.await()
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
                when {
                    online != null -> if (library.addOnlineToPlaylist(id, online)) 1 else 0
                    ids.isEmpty() -> 0
                    else -> library.addToPlaylist(id, ids)
                }
            }.await()
            finish(
                if (added == 0) res.getString(R.string.sheet_playlist_created, playlistName)
                else res.getQuantityString(R.plurals.sheet_added_to, added, added, playlistName),
            )
        }
    }

    SoundSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(
            title = stringResource(R.string.sheet_add_title),
            subtitle = songCount(ids.size),
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
                    SoundTextField(
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
        HorizontalDivider(color = SoundColors.Hairline, thickness = 1.dp)
        val lists = playlists
        if (lists != null && lists.isEmpty()) {
            Text(
                stringResource(R.string.sheet_no_playlists),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
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
            Modifier.size(48.dp).clip(RoundedCornerShape(Dimens.CoverCorner)).background(SoundColors.Surface3),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, null, tint = SoundColors.Text, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.sheet_new_playlist), style = MaterialTheme.typography.titleMedium, color = SoundColors.Text)
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
            Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                songCount(playlist.trackCount),
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.TextMuted,
            )
        }
        AnimatedVisibility(visible = checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Rounded.CheckCircle, stringResource(R.string.sheet_in_playlist), tint = SoundColors.Accent, modifier = Modifier.size(24.dp))
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
