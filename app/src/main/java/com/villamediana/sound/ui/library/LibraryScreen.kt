package com.villamediana.sound.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MicExternalOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.ui.search.DownloadIndicator
import com.villamediana.sound.ui.search.DownloadLookup
import com.villamediana.sound.ui.search.ItemDownload
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.FilterPill
import com.villamediana.sound.ui.components.LikedArt
import com.villamediana.sound.ui.components.MosaicCover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.PlayingBars
import com.villamediana.sound.ui.components.TrackRow
import com.villamediana.sound.ui.components.songCount
import com.villamediana.sound.ui.home.GenreArt
import com.villamediana.sound.ui.home.LiveBadge
import com.villamediana.sound.ui.home.isFromAlbum
import com.villamediana.sound.ui.home.isFromLiked
import com.villamediana.sound.ui.home.isFromPlaylist
import com.villamediana.sound.ui.home.pressable
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.nav.SearchRoute
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors

@Composable
fun LibraryScreen(embeddedFilter: LibFilter? = null) {
    // Inside the main screen: one view model per pill, showing that kind only (its header replaces ours).
    val vm: LibraryViewModel = viewModel(key = embeddedFilter?.let { "library:" + it.name })
    if (embeddedFilter != null) remember(vm, embeddedFilter) { vm.showOnly(embeddedFilter) }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by vm.downloadLookup.collectAsStateWithLifecycle()
    val selected by vm.filter.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val nav = navigator()

    var creating by rememberSaveable { mutableStateOf(false) }
    var songSheet by remember { mutableStateOf<Track?>(null) }
    var menuTarget by remember { mutableStateOf<CollectionTarget?>(null) }

    Column(Modifier.fillMaxSize().background(SoundColors.Ink)) {
      if (embeddedFilter == null) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_lib_title),
                style = MaterialTheme.typography.displaySmall,
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, stringResource(R.string.home_lib_new_playlist), tint = SoundColors.Text, modifier = Modifier.size(28.dp))
            }
        }
        FilterBar(selected, onSelect = vm::selectFilter)
        Spacer(Modifier.height(4.dp))
      }

        if (state.loaded) {
            AnimatedContent(
                targetState = state,
                contentKey = { it.filter to (it.viewMode == LibViewMode.GRID && it.filter != LibFilter.SONGS) },
                transitionSpec = {
                    (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 24 }) togetherWith fadeOut(Motion.exit())
                },
                label = "libraryContent",
                modifier = Modifier.fillMaxSize(),
            ) { s ->
                LibraryContent(
                    s = s,
                    downloads = downloads,
                    playback = playback,
                    nav = nav,
                    vm = vm,
                    onSong = { songSheet = it },
                    onMenu = { menuTarget = it },
                    onCreate = { creating = true },
                )
            }
        }
    }

    if (creating) {
        CreatePlaylistDialog(
            onCreate = { name, description ->
                creating = false
                vm.createPlaylist(name, description) { id -> nav.openPlaylist(id) }
            },
            onDismiss = { creating = false },
        )
    }
    songSheet?.let { TrackActionsSheet(track = it, onDismiss = { songSheet = null }) }
    menuTarget?.let { CollectionMenuSheet(target = it, onDismiss = { menuTarget = null }) }
}

@Composable
private fun FilterBar(selected: LibFilter?, onSelect: (LibFilter?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selected != null,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(SoundColors.Surface2)
                    .clickable { onSelect(null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.home_lib_clear_filter), tint = SoundColors.Text, modifier = Modifier.size(18.dp))
            }
        }
        LibFilter.entries.forEach { f ->
            FilterPill(text = filterLabel(f), selected = selected == f, onClick = { onSelect(f) })
        }
    }
}

@Composable
private fun filterLabel(f: LibFilter): String = stringResource(
    when (f) {
        LibFilter.PLAYLISTS -> R.string.home_lib_filter_playlists
        LibFilter.ALBUMS -> R.string.home_lib_filter_albums
        LibFilter.ARTISTS -> R.string.home_lib_filter_artists
        LibFilter.SONGS -> R.string.home_lib_filter_songs
        LibFilter.GENRES -> R.string.home_lib_filter_genres
    },
)

@Composable
private fun sortLabel(s: LibSort): String = stringResource(
    when (s) {
        LibSort.RECENT -> R.string.home_lib_sort_recent
        LibSort.ADDED -> R.string.home_lib_sort_added
        LibSort.ALPHA -> R.string.home_lib_sort_alpha
        LibSort.ARTIST -> R.string.home_lib_sort_artist
        LibSort.PLAYS -> R.string.home_lib_sort_plays
    },
)

// ---------------------------------------------------------------------------- content

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryContent(
    s: LibraryUiState,
    downloads: DownloadLookup,
    playback: PlaybackState,
    nav: Navigator,
    vm: LibraryViewModel,
    onSong: (Track) -> Unit,
    onMenu: (CollectionTarget) -> Unit,
    onCreate: () -> Unit,
) {
    val grid = s.viewMode == LibViewMode.GRID && s.filter != LibFilter.SONGS
    val rowPad = if (grid) 0.dp else Dimens.Gutter
    val gridState = rememberLazyGridState()
    val songs = remember(s.items) { s.items.mapNotNull { (it as? LibItem.SongItem)?.track } }
    val songsTitle = stringResource(R.string.main_tab_songs)
    val songsSource = remember(songsTitle) { PlaySource(PlaySource.Type.LIKED, songsTitle, "liked") }
    val entries = remember(s.pinned, s.items) { s.pinned + s.items }

    LazyVerticalGrid(
        columns = if (grid) GridCells.Adaptive(minSize = 104.dp) else GridCells.Fixed(1),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = if (grid) Dimens.Gutter else 0.dp,
            end = if (grid) Dimens.Gutter else 0.dp,
            bottom = LocalBottomInset.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(if (grid) 18.dp else 0.dp),
    ) {
        item(key = "sortbar", span = { GridItemSpan(maxLineSpan) }, contentType = "sortbar") {
            SortBar(
                filter = s.filter,
                sort = s.sort,
                grid = grid,
                onSort = vm::setSort,
                onToggleMode = vm::toggleViewMode,
                modifier = Modifier.padding(horizontal = rowPad),
            )
        }

        s.downloads?.let { dl ->
            item(key = "downloads", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                DownloadsEntry(dl, onClick = { nav.openDownloads() }, horizontalPadding = rowPad, modifier = Modifier.animateItem())
            }
        }

        if (s.showLiked) {
            item(key = "liked", contentType = if (grid) "cell" else "row") {
                val title = stringResource(R.string.home_liked)
                val subtitle = if (s.filter == null) {
                    stringResource(R.string.home_lib_playlist_sub, songCount(s.likedCount))
                } else {
                    songCount(s.likedCount)
                }
                val current = playback.hasMedia && playback.isFromLiked()
                if (grid) {
                    LibraryCell({ LikedArt(it) }, title, subtitle, pinned = false, current = current, playing = playback.isPlaying, onClick = { nav.openLiked() }, modifier = Modifier.animateItem())
                } else {
                    LibraryRow({ LikedArt(it) }, title, subtitle, pinned = false, current = current, playing = playback.isPlaying, onClick = { nav.openLiked() }, modifier = Modifier.animateItem())
                }
            }
        }

        items(
            entries,
            key = { it.key },
            contentType = { if (it is LibItem.SongItem) "song" else if (grid) "cell" else "row" },
        ) { item ->
            when (item) {
                is LibItem.SongItem -> {
                    // Músicas = the liked songs: no heart (all of them are), and "Baixar" only for
                    // one that is not on the phone nor on its way — it goes once tapped.
                    val t = item.track
                    val offer = t.isOnline && t.videoId?.let { canDownload(downloads.stateOf(it)) } == true
                    TrackRow(
                        track = t,
                        onClick = { vm.playSongs(songs, songs.indexOfFirst { it.id == t.id }, songsSource) },
                        onLongClick = { onSong(t) },
                        onMore = { onSong(t) },
                        isCurrent = playback.current?.id == t.id,
                        isPlaying = playback.isPlaying,
                        likedMark = false,
                        trailing = { DownloadOffer(offer, onClick = { vm.download(t) }) },
                        modifier = Modifier.animateItem(),
                    )
                }
                else -> CollectionEntry(item, s.filter, grid, playback, nav, onMenu, Modifier.animateItem())
            }
        }

        if (s.isEmptyView) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                LibraryEmpty(s.filter, nav, onCreate, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun CollectionEntry(
    item: LibItem,
    filter: LibFilter?,
    grid: Boolean,
    playback: PlaybackState,
    nav: Navigator,
    onMenu: (CollectionTarget) -> Unit,
    modifier: Modifier,
) {
    val all = filter == null
    var circle = false
    var current = false
    var onLongClick: (() -> Unit)? = null
    val art: @Composable (Modifier) -> Unit
    val subtitle: String
    val onClick: () -> Unit

    when (item) {
        is LibItem.PlaylistItem -> {
            val p = item.summary
            art = { m -> if (p.coverPath != null) Cover(p.coverPath, m, seed = p.name) else MosaicCover(p.mosaic, m, seed = p.name) }
            subtitle = if (all) stringResource(R.string.home_lib_playlist_sub, songCount(p.trackCount)) else songCount(p.trackCount)
            current = playback.hasMedia && playback.isFromPlaylist(p.id)
            onClick = { nav.openPlaylist(p.id) }
            onLongClick = { onMenu(CollectionTarget.Playlist(p)) }
        }
        is LibItem.AlbumItem -> {
            val a = item.summary
            art = { m -> Cover(a.coverPath, m, seed = a.title) }
            val artistYear = listOfNotNull(a.artist, a.year?.toString()).joinToString(" · ")
            subtitle = if (all) stringResource(R.string.home_lib_album_sub, a.artist) else artistYear
            current = playback.isFromAlbum(a.key.normalized)
            onClick = { nav.openAlbum(a.key) }
            onLongClick = { onMenu(CollectionTarget.Album(a)) }
        }
        is LibItem.ArtistItem -> {
            val r = item.summary
            circle = true
            art = { m -> Cover(r.coverPath, m, seed = r.name, shape = CircleShape) }
            subtitle = if (all) stringResource(R.string.home_lib_artist_sub) else songCount(r.trackCount)
            onClick = { nav.openArtist(r.name) }
        }
        is LibItem.GenreItem -> {
            val g = item.summary
            art = { m -> GenreArt(g, m, corner = Dimens.CoverCorner) }
            subtitle = songCount(g.trackCount)
            onClick = { nav.openGenre(g.name) }
        }
        is LibItem.SongItem -> return
    }

    if (grid) {
        LibraryCell(art, item.title, subtitle, item.pinned, current, playback.isPlaying, onClick, modifier, onLongClick, circle)
    } else {
        LibraryRow(art, item.title, subtitle, item.pinned, current, playback.isPlaying, onClick, modifier, onLongClick)
    }
}

// ---------------------------------------------------------------------------- rows & cells

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    art: @Composable (Modifier) -> Unit,
    title: String,
    subtitle: String,
    pinned: Boolean,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = Dimens.Gutter,
    artSize: Dp = 56.dp,
    height: Dp = 72.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(Modifier.size(artSize))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) SoundColors.Accent else SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Subtitle(subtitle, pinned)
        }
        if (current) {
            Spacer(Modifier.width(10.dp))
            PlayingBars(playing, Modifier.size(14.dp))
        }
        trailing?.invoke()
    }
}

@Composable
private fun LibraryCell(
    art: @Composable (Modifier) -> Unit,
    title: String,
    subtitle: String,
    pinned: Boolean,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    circle: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().pressable(onClick, onLongClick),
        horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            art(Modifier.fillMaxSize())
            if (current) LiveBadge(playing, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (current) SoundColors.Accent else SoundColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circle) TextAlign.Center else TextAlign.Start,
        )
        Spacer(Modifier.height(2.dp))
        Subtitle(subtitle, pinned, small = true)
    }
}

@Composable
private fun Subtitle(text: String, pinned: Boolean, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (pinned) {
            Icon(
                Icons.Rounded.PushPin,
                stringResource(R.string.home_lib_pinned),
                tint = SoundColors.Accent,
                modifier = Modifier.size(if (small) 12.dp else 13.dp).rotate(35f),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = SoundColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadOffer(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
    ) {
        DownloadIndicator(ItemDownload.None, onClick = onClick)
    }
}

@Composable
private fun DownloadsEntry(dl: DownloadsRowState, onClick: () -> Unit, horizontalPadding: Dp, modifier: Modifier = Modifier) {
    val subtitle = when {
        dl.active > 0 && dl.failed > 0 -> stringResource(
            R.string.home_lib_dl_pair,
            pluralStringResource(R.plurals.home_lib_dl_active, dl.active, dl.active),
            pluralStringResource(R.plurals.home_lib_dl_failed, dl.failed, dl.failed),
        )
        dl.active > 0 -> pluralStringResource(R.plurals.home_lib_dl_active, dl.active, dl.active)
        dl.failed > 0 -> pluralStringResource(R.plurals.home_lib_dl_failed, dl.failed, dl.failed)
        else -> stringResource(R.string.home_lib_dl_done)
    }
    LibraryRow(
        art = { m ->
            Box(m.clip(RoundedCornerShape(Dimens.CoverCorner)).background(SoundColors.Surface2), contentAlignment = Alignment.Center) {
                if (dl.active > 0) {
                    val p = dl.progress ?: 0f
                    val animated by animateFloatAsState(p, tween(400), label = "dlRow")
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(34.dp),
                        color = SoundColors.Accent,
                        trackColor = SoundColors.AccentSoft,
                        strokeWidth = 2.5.dp,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                }
                Icon(
                    Icons.Rounded.ArrowDownward,
                    null,
                    tint = if (dl.failed > 0 && dl.active == 0) SoundColors.Danger else SoundColors.Text,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        title = stringResource(R.string.home_lib_downloads),
        subtitle = subtitle,
        pinned = false,
        current = false,
        playing = false,
        onClick = onClick,
        horizontalPadding = horizontalPadding,
        trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = SoundColors.TextFaint, modifier = Modifier.size(22.dp)) },
        modifier = modifier,
    )
}

@Composable
private fun SortBar(
    filter: LibFilter?,
    sort: LibSort,
    grid: Boolean,
    onSort: (LibSort) -> Unit,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier
                    .clip(CircleShape)
                    .clickable { open = true }
                    .padding(start = 2.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SwapVert, null, tint = SoundColors.TextMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(sortLabel(sort), style = MaterialTheme.typography.labelLarge, color = SoundColors.Text)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = SoundColors.Surface2,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    stringResource(R.string.home_lib_sort_by).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SoundColors.TextFaint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                sortsFor(filter).forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                sortLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option == sort) SoundColors.Accent else SoundColors.Text,
                            )
                        },
                        trailingIcon = {
                            if (option == sort) Icon(Icons.Rounded.Check, null, tint = SoundColors.Accent, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            open = false
                            onSort(option)
                        },
                        modifier = Modifier.semantics { selected = option == sort },
                        colors = MenuDefaults.itemColors(textColor = SoundColors.Text),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (filter != LibFilter.SONGS) {
            IconButton(onClick = onToggleMode) {
                Icon(
                    if (grid) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                    stringResource(if (grid) R.string.home_lib_view_list else R.string.home_lib_view_grid),
                    tint = SoundColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryEmpty(filter: LibFilter?, nav: Navigator, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val search: @Composable () -> Unit = {
        PillButton(
            text = stringResource(R.string.home_empty_action),
            onClick = { nav.openSearchScreen() },
            icon = Icons.Rounded.Search,
            filled = true,
        )
    }
    when (filter) {
        null -> EmptyState(Icons.Outlined.LibraryMusic, stringResource(R.string.home_lib_empty_title), stringResource(R.string.home_lib_empty_body), modifier, search)
        LibFilter.PLAYLISTS -> EmptyState(
            Icons.AutoMirrored.Outlined.QueueMusic,
            stringResource(R.string.home_lib_empty_playlists_title),
            stringResource(R.string.home_lib_empty_playlists_body),
            modifier,
        ) { PillButton(text = stringResource(R.string.home_lib_new_playlist), onClick = onCreate, icon = Icons.Rounded.Add, filled = true) }
        LibFilter.ALBUMS -> EmptyState(Icons.Outlined.Album, stringResource(R.string.home_lib_empty_albums_title), stringResource(R.string.home_lib_empty_albums_body), modifier, search)
        LibFilter.ARTISTS -> EmptyState(Icons.Outlined.MicExternalOn, stringResource(R.string.home_lib_empty_artists_title), stringResource(R.string.home_lib_empty_artists_body), modifier, search)
        LibFilter.SONGS -> EmptyState(Icons.Outlined.MusicNote, stringResource(R.string.home_lib_empty_songs_title), stringResource(R.string.home_lib_empty_songs_body), modifier, search)
        LibFilter.GENRES -> EmptyState(Icons.Outlined.Category, stringResource(R.string.home_lib_empty_genres_title), stringResource(R.string.home_lib_empty_genres_body), modifier)
    }
}
