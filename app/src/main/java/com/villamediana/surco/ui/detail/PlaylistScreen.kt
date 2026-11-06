package com.villamediana.surco.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.surco.R
import com.villamediana.surco.data.db.PlaylistEntry
import com.villamediana.surco.data.model.PlaylistDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.ui.components.Cover
import com.villamediana.surco.ui.components.EmptyState
import com.villamediana.surco.ui.components.MosaicCover
import com.villamediana.surco.ui.components.Share
import com.villamediana.surco.ui.components.songCount
import com.villamediana.surco.ui.nav.PlaylistRoute
import com.villamediana.surco.ui.sheets.ConfirmDialog
import com.villamediana.surco.ui.sheets.PlaylistContext
import com.villamediana.surco.ui.sheets.SheetTitle
import com.villamediana.surco.ui.sheets.SurcoSheet
import com.villamediana.surco.ui.sheets.SurcoTextField
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.LocalBottomInset
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Playlist covers chosen in the app are copied as "playlist-<id>-<time>.jpg" (owned by the playlist). */
internal object PlaylistCovers {
    fun fileName(playlistId: Long, now: Long) = "playlist-$playlistId-$now"
    fun isOwned(path: String?, playlistId: Long): Boolean =
        path != null && File(path).name.startsWith("playlist-$playlistId-")

    /** Cover choices: the current custom cover first (if it isn't one of the songs'), then the songs' covers. */
    fun choices(current: String?, trackCovers: List<String>): List<String> {
        val distinct = trackCovers.distinct()
        return if (current != null && current !in distinct) listOf(current) + distinct else distinct
    }
}

internal class PlaylistViewModel(private val id: Long) : ViewModel() {
    private val library = graph.library
    /** Row order while the user is dragging / until the database catches up. */
    private val localOrder = MutableStateFlow<List<Long>?>(null)
    private val commitLock = Mutex()

    val playlist: StateFlow<Load<PlaylistDetail>> = combine(library.playlist(id), localOrder) { detail, order ->
        detail?.let { d -> if (order == null) d else d.copy(entries = PlayOrder.applyOrder(d.entries, order) { it.rowId }) }
    }.asLoad().stateIn(this)

    /** The order being shown while a drag (or its save) is in flight; null = the database order. */
    fun pendingOrder(): List<Long>? = localOrder.value

    fun beginReorder(order: List<Long>) {
        localOrder.value = order
    }

    fun moveLocal(fromRow: Long, toRow: Long): Boolean {
        val current = localOrder.value ?: return false
        val from = current.indexOf(fromRow)
        val to = current.indexOf(toRow)
        if (from < 0 || to < 0 || from == to) return false
        localOrder.value = PlayOrder.move(current, from, to)
        return true
    }

    /** Persists one drag: the row went from [originalIndex] to wherever it is now. */
    fun commitReorder(rowId: Long, originalIndex: Int) {
        val order = localOrder.value ?: return
        val to = order.indexOf(rowId)
        viewModelScope.launch {
            commitLock.withLock {
                if (to >= 0 && originalIndex >= 0 && to != originalIndex) {
                    library.movePlaylistEntry(id, originalIndex, to)
                    withTimeoutOrNull(2_000) { library.playlist(id).first { d -> d?.entries?.map { it.rowId } == order } }
                }
                if (localOrder.value == order) localOrder.value = null
            }
        }
    }

    fun remove(entry: PlaylistEntry) {
        localOrder.update { it?.minus(entry.rowId) }
        graph.scope.launch { library.removeFromPlaylist(id, entry.rowId) }
    }

    fun restore(entry: PlaylistEntry) {
        graph.scope.launch { library.addToPlaylistAt(id, entry.track.id, entry.position) }
    }

    fun rename(name: String, description: String?) {
        viewModelScope.launch { library.renamePlaylist(id, name, description) }
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { library.setPlaylistPinned(id, pinned) }
    }

    /** Copies the chosen cover so it survives deleting the song it came from; null = mosaic. */
    fun setCover(source: String?) {
        graph.scope.launch(Dispatchers.IO) {
            val old = library.getPlaylist(id)?.coverPath
            if (source == old) return@launch
            val newPath = source?.let { src ->
                if (PlaylistCovers.isOwned(src, id)) {
                    src
                } else {
                    val dest = graph.storage.coverFile(PlaylistCovers.fileName(id, System.currentTimeMillis()))
                    runCatching { File(src).copyTo(dest, overwrite = true) }.map { dest.absolutePath }.getOrDefault(src)
                }
            }
            library.setPlaylistCover(id, newPath)
            if (old != null && old != newPath && PlaylistCovers.isOwned(old, id)) runCatching { File(old).delete() }
        }
    }

    fun delete() {
        graph.scope.launch(Dispatchers.IO) {
            val cover = library.getPlaylist(id)?.coverPath
            library.deletePlaylist(id)
            if (PlaylistCovers.isOwned(cover, id)) runCatching { File(cover!!).delete() }
        }
    }
}

private fun entryKey(rowId: Long) = "e-$rowId"
private fun rowIdOf(key: Any): Long? = (key as? String)?.removePrefix("e-")?.toLongOrNull()

@Composable
fun PlaylistScreen(route: PlaylistRoute) {
    val vm = viewModel { PlaylistViewModel(route.id) }
    val load by vm.playlist.collectAsStateWithLifecycle()
    PopWhenGone(load)
    when (val l = load) {
        Load.Loading -> DetailLoading()
        Load.Missing -> NotFound(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.detail_playlist_missing), stringResource(R.string.detail_missing_body))
        is Load.Ready -> PlaylistContent(l.value, vm)
    }
}

@Composable
private fun PlaylistContent(detail: PlaylistDetail, vm: PlaylistViewModel) {
    val summary = detail.summary
    val playlist = summary.playlist
    val entries = detail.entries
    val tracks = remember(entries) { entries.map { it.track } }
    val context = LocalContext.current
    val res = context.resources
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val player = graph.player
    val playback by rememberPlaybackState()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    val listState = rememberLazyListState()
    var shuffleLocal by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var choosingCover by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }

    BackHandler(enabled = editing) { editing = false }
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) editing = false }

    val source = Sources.playlist(summary.id, summary.name)
    val status = playback.statusOf(source)
    val shuffleOn = if (status.isSource) playback.shuffle else shuffleLocal
    val countText = songCount(summary.trackCount)
    val wash = rememberWash(summary.coverPath ?: summary.mosaic.firstOrNull(), null, seed = summary.name)
    val art: @Composable (Modifier) -> Unit = { m ->
        val custom = summary.coverPath
        if (custom != null) Cover(custom, m, seed = summary.name) else MosaicCover(summary.mosaic, m, seed = summary.name)
    }

    val topEdgePx = with(density) { collectionTopInset().toPx() }
    val bottomInsetPx = with(density) { LocalBottomInset.current.toPx() }
    val reorder = rememberReorderState(
        listState = listState,
        topEdgePx = topEdgePx,
        bottomInsetPx = bottomInsetPx,
        // Straight from the view model: it changes synchronously while dragging, before recomposition.
        keysInOrder = { (vm.pendingOrder() ?: entries.map { it.rowId }).map(::entryKey) },
        onMove = { from, to ->
            val a = rowIdOf(from)
            val b = rowIdOf(to)
            if (a != null && b != null && vm.moveLocal(a, b)) {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                true
            } else {
                false
            }
        },
        onDragStart = { key ->
            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            val order = vm.pendingOrder() ?: entries.map { it.rowId }
            vm.beginReorder(order)
            dragStartIndex = order.indexOf(rowIdOf(key))
        },
        onDragEnd = { key -> rowIdOf(key)?.let { vm.commitReorder(it, dragStartIndex) } },
    )

    fun removeWithUndo(entry: PlaylistEntry) {
        vm.remove(entry)
        snackbar.feedback.show(res.getString(R.string.detail_removed_from_playlist, entry.track.title), res.getString(R.string.detail_undo)) {
            vm.restore(entry)
        }
    }

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_playlist),
            title = summary.name,
            description = playlist.description,
            meta = if (entries.isEmpty()) countText else MetaFormat.countAndDuration(countText, summary.durationMs),
            wash = wash,
            badge = if (playlist.sourceUrl != null) stringResource(R.string.detail_imported_youtube) else null,
            badgeIcon = Icons.Rounded.Download,
        ),
        art = art,
        listState = listState,
        snackbar = snackbar,
        playing = status.isPlaying,
        onPlay = if (tracks.isEmpty()) null else ({ playOrToggle(tracks, source, status, shuffleOn) }),
        shuffle = if (tracks.isEmpty()) null else shuffleOn,
        onShuffle = { toggleShuffle(status, shuffleOn) { shuffleLocal = it } },
        showActionRow = !editing,
        actions = {
            HeaderIconButton(
                icon = if (summary.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(if (summary.pinned) R.string.detail_unpin else R.string.detail_pin),
                tint = if (summary.pinned) SurcoColors.Accent else SurcoColors.TextMuted,
                active = summary.pinned,
                onClick = {
                    val pin = !summary.pinned
                    haptic.performHapticFeedback(if (pin) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    vm.setPinned(pin)
                    snackbar.feedback.show(res.getString(if (pin) R.string.detail_pinned else R.string.detail_unpinned))
                },
            )
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
        },
        barActions = {
            if (editing) {
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(R.string.detail_done), style = MaterialTheme.typography.labelLarge, color = SurcoColors.Accent)
                }
            }
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                val items = buildList {
                    add(MenuItem(Icons.Rounded.Edit, res.getString(R.string.detail_menu_edit_playlist)) { renaming = true })
                    add(MenuItem(Icons.Rounded.Image, res.getString(R.string.detail_menu_choose_cover)) { choosingCover = true })
                    if (entries.size > 1) add(MenuItem(Icons.Rounded.SwapVert, res.getString(R.string.detail_menu_reorder)) { editing = true })
                    addAll(queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it })
                    playlist.sourceUrl?.takeIf { it.startsWith("http") }?.let { url ->
                        // Imported from YouTube: share the original playlist's link.
                        add(MenuItem(Icons.Rounded.Share, res.getString(R.string.detail_menu_share)) {
                            Share.collection(context, RemoteCollection(CollectionKind.PLAYLIST, url, summary.name))
                        })
                    }
                    add(MenuItem(Icons.Rounded.DeleteOutline, res.getString(R.string.detail_menu_delete_playlist), destructive = true) { confirmDelete = true })
                }
                CollectionMenuSheet(
                    title = summary.name,
                    subtitle = countText,
                    art = art,
                    items = items,
                    onDismiss = { sheets.menuOpen = false },
                )
            }
            if (renaming) {
                RenamePlaylistDialog(
                    name = summary.name,
                    description = playlist.description.orEmpty(),
                    onDismiss = { renaming = false },
                    onSave = { n, d ->
                        renaming = false
                        vm.rename(n, d)
                    },
                )
            }
            if (choosingCover) {
                ChooseCoverSheet(
                    current = summary.coverPath,
                    mosaic = summary.mosaic,
                    trackCovers = tracks.mapNotNull { it.coverPath },
                    seed = summary.name,
                    onChoose = { path ->
                        choosingCover = false
                        vm.setCover(path)
                    },
                    onDismiss = { choosingCover = false },
                )
            }
            if (confirmDelete) {
                ConfirmDialog(
                    title = stringResource(R.string.detail_delete_playlist_title),
                    body = stringResource(R.string.detail_delete_playlist_body, summary.name),
                    confirm = stringResource(R.string.delete),
                    onConfirm = {
                        confirmDelete = false
                        vm.delete()
                        toast(context, res.getString(R.string.detail_playlist_deleted, summary.name))
                    },
                    onDismiss = { confirmDelete = false },
                )
            }
        },
    ) {
        if (entries.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    title = stringResource(R.string.detail_playlist_empty_title),
                    body = stringResource(R.string.detail_playlist_empty_body),
                )
            }
        }
        if (editing) {
            item(key = "reorder-hint", contentType = "hint") {
                SmallCaption(stringResource(R.string.detail_reorder_hint), Modifier.padding(horizontal = Dimens.Gutter).padding(top = 4.dp, bottom = 8.dp))
            }
        }
        itemsIndexed(entries, key = { _, e -> entryKey(e.rowId) }, contentType = { _, _ -> if (editing) "edit" else "track" }) { i, e ->
            val key = entryKey(e.rowId)
            if (editing) {
                val dragging = reorder.isDragging(key)
                EditableRow(
                    entry = e,
                    dragging = dragging,
                    onRemove = { removeWithUndo(e) },
                    handle = Modifier.reorderHandle(reorder, key),
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) reorder.draggingOffset else 0f }
                        .animateItem(
                            placementSpec = if (dragging) null else spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                                visibilityThreshold = IntOffset(1, 1),
                            ),
                        ),
                )
            } else {
                SongRow(
                    track = e.track,
                    playback = playback,
                    onClick = { player.play(tracks, i, source, shuffleOn) },
                    sheet = sheet,
                    context = PlaylistContext(summary.id, e.rowId),
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Row in reorder mode: remove, art, text, drag handle. Lifts (raised surface + shadow) while dragged. */
@Composable
private fun EditableRow(
    entry: PlaylistEntry,
    dragging: Boolean,
    onRemove: () -> Unit,
    handle: Modifier,
    modifier: Modifier = Modifier,
) {
    val t = entry.track
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        color = if (dragging) SurcoColors.Surface3 else SurcoColors.Ink,
        shape = RowShape,
        shadowElevation = if (dragging) 10.dp else 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.RowHeight).padding(start = 2.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.detail_remove), tint = SurcoColors.Danger)
            }
            Cover(t.coverPath, Modifier.size(44.dp), seed = t.album ?: t.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, color = SurcoColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(defaultSubtitle(t), style = MaterialTheme.typography.bodyMedium, color = SurcoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(handle.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.DragHandle,
                    stringResource(R.string.detail_drag_to_reorder),
                    tint = if (dragging) SurcoColors.Accent else SurcoColors.TextMuted,
                )
            }
        }
    }
}

/** "Editar playlist": name + description. */
@Composable
private fun RenamePlaylistDialog(name: String, description: String, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var n by rememberSaveable { mutableStateOf(name) }
    var d by rememberSaveable { mutableStateOf(description) }
    val valid = n.isNotBlank()
    val save = { if (valid) onSave(n.trim(), d.trim().ifEmpty { null }) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = SurcoColors.Surface2,
            contentColor = SurcoColors.Text,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.detail_edit_playlist_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SurcoTextField(n, { n = it.take(80) }, stringResource(R.string.detail_field_name))
                    SurcoTextField(
                        d, { d = it.take(300) }, stringResource(R.string.detail_field_description),
                        singleLine = false, imeAction = ImeAction.Done, onDone = save,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = SurcoColors.TextMuted)
                    }
                    TextButton(onClick = save, enabled = valid) {
                        Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = if (valid) SurcoColors.Accent else SurcoColors.TextFaint)
                    }
                }
            }
        }
    }
}

/** Grid of the playlist's covers (+ the mosaic) to pick the playlist art. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseCoverSheet(
    current: String?,
    mosaic: List<String>,
    trackCovers: List<String>,
    seed: String,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val choices = remember(current, trackCovers) { PlaylistCovers.choices(current, trackCovers) }
    fun pick(path: String?) {
        scope.launch { state.hide() }.invokeOnCompletion { onChoose(path) }
    }
    SurcoSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetTitle(stringResource(R.string.detail_choose_cover_title), stringResource(R.string.detail_choose_cover_subtitle))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "mosaic") {
                CoverChoice(selected = current == null, label = stringResource(R.string.detail_cover_mosaic), onClick = { pick(null) }) { m ->
                    MosaicCover(mosaic, m, seed = seed)
                }
            }
            items(choices, key = { it }) { path ->
                CoverChoice(selected = path == current, label = null, onClick = { pick(path) }) { m ->
                    Cover(path, m, seed = path)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CoverChoice(selected: Boolean, label: String?, onClick: () -> Unit, art: @Composable (Modifier) -> Unit) {
    val shape = RoundedCornerShape(Dimens.CoverCorner + 2.dp)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(if (selected) Modifier.border(2.dp, SurcoColors.Accent, shape) else Modifier)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(if (selected) 5.dp else 0.dp),
        ) {
            art(Modifier.fillMaxSize())
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(SurcoColors.Ink),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.CheckCircle, null, tint = SurcoColors.Accent, modifier = Modifier.size(22.dp)) }
            }
        }
        if (label != null) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) SurcoColors.Accent else SurcoColors.TextMuted)
        }
    }
}
