package com.villamediana.sound.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.villamediana.sound.ui.search.SkeletonRow
import com.villamediana.sound.ui.search.skeletonWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.model.AlbumDetail
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.discover.AlbumMerge
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.stats.Recaps
import com.villamediana.sound.ui.search.Connectivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.nav.AlbumRoute
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.sheets.ConfirmDialog
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class AlbumViewModel(artist: String, title: String) : ViewModel() {
    private val key = AlbumKey(artist, title)
    private val library = graph.library

    val album: StateFlow<Load<AlbumDetail>> = library.album(key).asLoad().stateIn(this)

    /** The album on YouTube Music (empty offline, until found, or when it isn't the same album). */
    private val remote = MutableStateFlow(memo[key.normalized].orEmpty())

    /** Looking for the rest of the album right now: the page shows placeholders for it meanwhile. */
    private val lookingUp = MutableStateFlow(remote.value.isEmpty() && Connectivity.isOnline(graph.appContext))

    /**
     * The album's songs: yours, and the rest of the album streamed, in the album's order. Only
     * when the online album has one of your songs (otherwise it is another album of that name).
     */
    val songs: StateFlow<AlbumSongs?> = combine(library.album(key), remote, library.likedVideoIds, lookingUp) { detail, online, likedIds, looking ->
        detail ?: return@combine null
        if (online.isEmpty() || !AlbumMerge.overlaps(detail.tracks, online)) return@combine AlbumSongs(detail.tracks, complete = false, pending = looking)
        val merged = AlbumMerge.merge(detail.tracks, online).map { t -> if (t.isOnline && t.videoId in likedIds) t.copy(liked = true) else t }
        AlbumSongs(merged, complete = true)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        if (remote.value.isEmpty()) {
            viewModelScope.launch {
                // Waits for the network when the page opens without it.
                Connectivity.online(graph.appContext).first { it }
                lookingUp.value = true
                try {
                    val page = runCatching {
                        graph.discover.findAlbum(key.title, Recaps.mainArtist(key.artist), sameArtist = true)
                    }.getOrNull() ?: return@launch
                    val tracks = runCatching { graph.youTube.collection(page.url, CollectionKind.ALBUM).tracks }.getOrNull().orEmpty()
                    if (tracks.isNotEmpty()) {
                        memo[key.normalized] = tracks
                        remote.value = tracks
                    }
                } finally {
                    lookingUp.value = false
                }
            }
        }
    }

    private companion object {
        /** Albums already looked up while the app is open: reopening one is instant. */
        val memo = java.util.concurrent.ConcurrentHashMap<String, List<RemoteTrack>>()
    }

    /** Other albums credited to the same album artist, newest first. */
    val moreByArtist: StateFlow<List<AlbumSummary>> = library.albums.map { all ->
        val artistKey = key.artist.trim().lowercase()
        all.filter { it.key.artist.trim().lowercase() == artistKey && it.key.normalized != key.normalized }
            .sortedByDescending { it.year ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPinned(key: AlbumKey, pinned: Boolean) {
        viewModelScope.launch { library.setAlbumPinned(key, pinned) }
    }

    fun delete(ids: List<Long>) {
        graph.scope.launch { library.deleteTracks(ids) }
    }
}

/**
 * [tracks] of the album page; [complete] when the online album filled in the songs you don't
 * have; [pending] while it is being looked up (placeholders stand for the rest).
 */
internal data class AlbumSongs(val tracks: List<Track>, val complete: Boolean, val pending: Boolean = false)

@Composable
fun AlbumScreen(route: AlbumRoute) {
    val vm = viewModel { AlbumViewModel(route.artist, route.title) }
    val load by vm.album.collectAsStateWithLifecycle()
    PopWhenGone(load)
    when (val l = load) {
        Load.Loading -> DetailLoading()
        Load.Missing -> NotFound(Icons.Rounded.Album, stringResource(R.string.detail_album_missing), stringResource(R.string.detail_missing_body))
        is Load.Ready -> AlbumContent(l.value, vm)
    }
}

@Composable
private fun AlbumContent(detail: AlbumDetail, vm: AlbumViewModel) {
    val s = detail.summary
    val songs by vm.songs.collectAsStateWithLifecycle()
    // Your songs at once; the rest of the album joins when it is found.
    val tracks = songs?.tracks ?: detail.tracks
    val complete = songs?.complete == true
    // Until the rest of the album arrives: placeholders, and no song count yet (it would say "1 música").
    val pending = songs?.pending ?: false
    val durationMs = if (complete) tracks.sumOf { it.durationMs } else s.durationMs
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val res = context.resources
    val haptic = LocalHapticFeedback.current
    val player = graph.player
    val playback by rememberPlaybackState()
    val more by vm.moreByArtist.collectAsStateWithLifecycle()
    val snackbar = rememberDetailSnackbar()
    val sheet = rememberTrackSheetState()
    val sheets = rememberCollectionSheets()
    var confirmDelete by remember { mutableStateOf(false) }
    var shuffleLocal by rememberSaveable { mutableStateOf(false) }

    val source = Sources.album(s.key.artist, s.key.title, s.title)
    val status = playback.statusOf(source)
    val shuffleOn = if (status.isSource) playback.shuffle else shuffleLocal
    val countText = songCount(tracks.size)
    val wash = rememberWash(s.coverPath, s.accentColor, seed = s.title)

    CollectionScaffold(
        header = HeaderSpec(
            overline = stringResource(R.string.detail_overline_album),
            title = s.title,
            owner = s.artist,
            onOwnerClick = { navigator.openArtist(s.artist) },
            meta = MetaFormat.join(s.year?.toString(), if (pending) null else MetaFormat.countAndDuration(countText, durationMs)),
            wash = wash,
        ),
        art = { m -> Cover(s.coverPath, m, seed = s.title) },
        snackbar = snackbar,
        playing = status.isPlaying,
        loading = status.isLoading,
        onPlay = { playOrToggle(tracks, source, status, shuffleOn) },
        shuffle = shuffleOn,
        onShuffle = { toggleShuffle(status, shuffleOn) { shuffleLocal = it } },
        actions = {
            HeaderIconButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { sheets.menuOpen = true })
        },
        sheets = {
            TrackSheetHost(sheet)
            AddAllHost(sheets)
            if (sheets.menuOpen) {
                CollectionMenuSheet(
                    title = s.title,
                    subtitle = s.artist,
                    art = { m -> Cover(s.coverPath, m, seed = s.title) },
                    items = queueMenuItems(tracks, res, snackbar.feedback) { sheets.addToPlaylist = it } + listOf(
                        MenuItem(Icons.Rounded.Person, res.getString(R.string.detail_menu_go_artist)) { navigator.openArtist(s.artist) },
                        MenuItem(Icons.Rounded.DeleteOutline, res.getString(R.string.detail_menu_delete_album), destructive = true) { confirmDelete = true },
                    ),
                    onDismiss = { sheets.menuOpen = false },
                )
            }
            if (confirmDelete) {
                ConfirmDialog(
                    title = stringResource(R.string.detail_delete_album_title),
                    body = pluralStringResource(R.plurals.detail_delete_album_body, detail.tracks.size, detail.tracks.size, s.title),
                    confirm = stringResource(R.string.delete),
                    onConfirm = {
                        confirmDelete = false
                        vm.delete(detail.tracks.map { it.id })
                        toast(context, res.getString(R.string.detail_album_deleted, s.title))
                    },
                    onDismiss = { confirmDelete = false },
                )
            }
        },
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
            SongRow(
                track = t,
                playback = playback,
                onClick = { player.play(tracks, i, source, shuffleOn) },
                sheet = sheet,
                number = if (complete) i + 1 else t.trackNumber ?: (i + 1),
                subtitle = t.artist,
                showDuration = true,
                modifier = Modifier.animateItem(),
            )
        }
        if (pending) {
            items(PLACEHOLDERS, key = { "pending-$it" }, contentType = { "pending" }) { i ->
                SkeletonRow(widthFraction = skeletonWidth(i), coverSize = 24.dp, modifier = Modifier.animateItem())
            }
        }
        if (!pending) item(key = "footer", contentType = "footer") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter).padding(top = 18.dp, bottom = 4.dp)) {
                s.year?.let {
                    Text(it.toString(), style = MaterialTheme.typography.titleSmall, color = SoundColors.Text)
                    Spacer(Modifier.height(2.dp))
                }
                SmallCaption(MetaFormat.countAndDuration(countText, durationMs))
            }
        }
        if (more.isNotEmpty()) {
            item(key = "more-title", contentType = "section") {
                DetailSection(
                    title = stringResource(R.string.detail_more_by, s.artist),
                    action = stringResource(R.string.detail_see_artist),
                    onAction = { navigator.openArtist(s.artist) },
                )
            }
            item(key = "more", contentType = "carousel") {
                AlbumCarousel(
                    albums = more,
                    caption = { a -> a.year?.toString() ?: songCount(res, a.trackCount) },
                    onOpen = { a -> navigator.openAlbum(a.key) },
                )
            }
        }
    }
}

/** Placeholder rows while the rest of the album is on its way. */
private const val PLACEHOLDERS = 8
