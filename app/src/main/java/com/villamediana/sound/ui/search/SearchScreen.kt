package com.villamediana.sound.ui.search

import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.LocalSearchResult
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.FilterPill
import com.villamediana.sound.ui.components.MosaicCover
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.TrackRow
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.downloads.DownloadOverview
import com.villamediana.sound.ui.nav.Inbound
import com.villamediana.sound.ui.nav.LinkInbox
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

private val Filters = listOf(
    SearchFilter.SONGS to R.string.search_filter_songs,
    SearchFilter.ALBUMS to R.string.search_filter_albums,
    SearchFilter.PLAYLISTS to R.string.search_filter_playlists,
    SearchFilter.ARTISTS to R.string.search_filter_artists,
    SearchFilter.VIDEOS to R.string.search_filter_videos,
)

/** Online + local search, pasted/shared YouTube links and the multi-select download bar. */
@Composable
fun SearchScreen() {
    val vm: SearchViewModel = viewModel { SearchViewModel.create() }
    val navigator = navigator()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomInset = LocalBottomInset.current

    val mode = vm.mode
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val clipLink = rememberClipboardYouTubeLink()
    val pasteLink = clipLink?.takeIf { it != vm.handledClip && vm.link == null }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Links shared to Sound (or opened from the browser) and voice requests the library can't play land here.
    val pending by LinkInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        when (val item = if (pending != null) LinkInbox.consume() else null) {
            is Inbound.Link -> vm.resolveLink(item.text)
            is Inbound.Query -> vm.submit(item.text)
            null -> Unit
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is SearchEvent.OpenRemote -> navigator.openRemote(event.collection)
                is SearchEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch { snackbar.showSnackbar(context.getString(event.text)) }
                }
            }
        }
    }

    BackHandler(enabled = vm.selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = vm.selection.isEmpty() && mode != SearchMode.Idle) {
        vm.clearField()
        dismissKeyboard()
    }

    val actions = remember(vm, navigator) {
        LinkActions(
            download = { t -> vm.download(listOf(t)) },
            play = { t -> vm.playDownloaded(t.videoId, listOf(t.videoId), t.title) },
            listen = vm::listenNow,
            retry = { t, taskId -> vm.retry(taskId, t.videoId) },
            dismiss = vm::dismissLink,
        )
    }

    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            AnimatedVisibility(
                visible = mode == SearchMode.Idle,
                enter = expandVertically(tween(260)) + fadeIn(tween(260)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = Dimens.Gutter - 8.dp, top = 18.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val back = navigator()
                    if (back.controller.previousBackStackEntry != null) {
                        IconButton(onClick = { back.back() }, modifier = Modifier.padding(end = 4.dp).offset(x = (-12).dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SoundColors.Text)
                        }
                    }
                    Text(stringResource(R.string.search_title), style = MaterialTheme.typography.displaySmall, color = SoundColors.Text, modifier = Modifier.weight(1f))
                }
            }
            if (mode != SearchMode.Idle) Spacer(Modifier.height(10.dp))
            SearchField(
                value = vm.query,
                onChange = vm::onFieldChange,
                onSearch = {
                    vm.submit()
                    dismissKeyboard()
                },
                onClear = vm::clearField,
            )
            AnimatedVisibility(
                visible = pasteLink != null,
                enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
            ) {
                val link = pasteLink ?: vm.handledClip.orEmpty()
                PasteLinkChip(
                    onPaste = {
                        dismissKeyboard()
                        vm.resolveLink(link)
                    },
                    onDismiss = { vm.dismissClip(link) },
                    modifier = Modifier.padding(start = Dimens.Gutter, top = 12.dp),
                )
            }
            AnimatedVisibility(
                visible = mode == SearchMode.Results,
                enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Dimens.Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                ) {
                    items(Filters, key = { it.first.name }) { (f, label) ->
                        FilterPill(stringResource(label), selected = vm.filter == f, onClick = { vm.selectFilter(f) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                    label = "searchMode",
                ) { m ->
                    when (m) {
                        SearchMode.Idle -> IdleContent(
                            vm = vm,
                            lookup = lookup,
                            actions = actions,
                            onRecent = {
                                vm.submit(it)
                                dismissKeyboard()
                            },
                            onGenre = navigator::openGenre,
                        )
                        SearchMode.Typing -> TypingContent(
                            vm = vm,
                            lookup = lookup,
                            actions = actions,
                            online = online,
                            onSubmit = {
                                vm.submit(it)
                                dismissKeyboard()
                            },
                            onTrackMore = { sheetTrack = it },
                            onScroll = ::dismissKeyboard,
                        )
                        SearchMode.Results -> ResultsContent(
                            vm = vm,
                            lookup = lookup,
                            actions = actions,
                            onTrackMore = { sheetTrack = it },
                            onScroll = ::dismissKeyboard,
                        )
                    }
                }
            }
        }

        val selected = vm.selection.size
        AnimatedVisibility(
            visible = selected > 0,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset - 8.dp).coerceAtLeast(12.dp)),
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            SelectionBar(count = selected.coerceAtLeast(1), onDownload = vm::downloadSelection, onCancel = vm::clearSelection)
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset - 8.dp).coerceAtLeast(12.dp)),
        ) { data ->
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = SoundColors.Text,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoundColors.Surface3)
                    .padding(horizontal = 18.dp, vertical = 15.dp),
            )
        }
    }

    sheetTrack?.let { TrackActionsSheet(it, onDismiss = { sheetTrack = null }) }
}

/** What the link card can do — kept stable so list items don't recompose for nothing. */
private class LinkActions(
    val download: (com.villamediana.sound.online.RemoteTrack) -> Unit,
    val play: (com.villamediana.sound.online.RemoteTrack) -> Unit,
    val listen: (com.villamediana.sound.online.RemoteTrack) -> Unit,
    val retry: (com.villamediana.sound.online.RemoteTrack, Long) -> Unit,
    val dismiss: () -> Unit,
)

private fun LazyListScope.linkCard(card: LinkCard?, lookup: DownloadLookup, actions: LinkActions) {
    if (card == null) return
    item(key = "link", contentType = "link") {
        val track = (card as? LinkCard.Found)?.track
        val state = track?.let { lookup.stateOf(it.videoId) } ?: ItemDownload.None
        LinkCardView(
            card = card,
            state = state,
            onDownload = { track?.let(actions.download) },
            onPlay = { track?.let(actions.play) },
            onListen = { track?.let(actions.listen) },
            onRetry = { if (track != null && state is ItemDownload.Failed) actions.retry(track, state.taskId) },
            onDismiss = actions.dismiss,
            modifier = Modifier.animateItem().padding(top = 4.dp, bottom = 22.dp),
        )
    }
}

// ---------------------------------------------------------------------------- header pieces

@Composable
private fun SearchField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(if (focused) SoundColors.TextFaint else Color.Transparent, tween(180), label = "fieldOutline")
    val iconTint by animateColorAsState(if (focused) SoundColors.Text else SoundColors.TextMuted, tween(180), label = "fieldIcon")
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.padding(horizontal = Dimens.Gutter).fillMaxWidth().height(52.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = SoundColors.Text),
        cursorBrush = SolidColor(SoundColors.Accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoundColors.Surface3)
                    .border(1.dp, outline, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = iconTint, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(stringResource(R.string.search_field_hint), style = MaterialTheme.typography.bodyLarge, color = SoundColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                AnimatedVisibility(value.text.isNotEmpty(), enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.6f), exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.6f)) {
                    IconButton(onClick = onClear, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.search_clear_field), tint = SoundColors.TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
    )
}

/** Opens the downloads screen; a ring shows progress while something is downloading. */

@Composable
private fun SelectionBar(count: Int, onDownload: () -> Unit, onCancel: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(31.dp))
            .background(SoundColors.Surface2)
            .border(1.dp, SoundColors.Hairline, RoundedCornerShape(31.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, stringResource(R.string.search_select_cancel), tint = SoundColors.TextMuted)
        }
        AnimatedContent(count, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) }, label = "selCount", modifier = Modifier.weight(1f)) { n ->
            Text(pluralStringResource(R.plurals.search_selected, n, n), style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1)
        }
        Row(
            Modifier
                .height(48.dp)
                .pressScale(interaction, 0.95f)
                .clip(CircleShape)
                .background(SoundColors.Accent)
                .clickable(interactionSource = interaction, indication = null, onClick = onDownload)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Download, null, tint = SoundColors.OnAccent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                pluralStringResource(R.plurals.search_download_n, count, count),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = SoundColors.OnAccent,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------- idle

@Composable
private fun IdleContent(
    vm: SearchViewModel,
    lookup: DownloadLookup,
    actions: LinkActions,
    onRecent: (String) -> Unit,
    onGenre: (String) -> Unit,
) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val card = vm.link
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = LocalBottomInset.current)) {
        linkCard(card, lookup, actions)
        if (recents.isNotEmpty()) {
            item(key = "recent-header") {
                SectionHeader(
                    title = stringResource(R.string.search_recent_title),
                    action = stringResource(R.string.search_recent_clear),
                    onAction = vm::clearRecents,
                    modifier = Modifier.animateItem(),
                )
            }
            items(recents, key = { "recent:$it" }) { q ->
                RecentRow(q, onClick = { onRecent(q) }, onRemove = { vm.removeRecent(q) }, modifier = Modifier.animateItem())
            }
            item(key = "recent-gap") { Spacer(Modifier.height(Dimens.SectionGap - 8.dp).animateItem()) }
        }
        if (genres.isNotEmpty()) {
            if (recents.isEmpty()) item(key = "genres-gap") { Spacer(Modifier.height(8.dp)) }
            item(key = "genres-header") {
                SectionHeader(title = stringResource(R.string.search_genres_title), subtitle = stringResource(R.string.search_genres_overline), modifier = Modifier.animateItem())
            }
            items(genres.take(16).chunked(2), key = { "genre:" + it.first().name }) { pair ->
                Row(
                    Modifier.animateItem().padding(horizontal = Dimens.Gutter).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { g -> GenreTile(g, onClick = { onGenre(g.name) }, modifier = Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- typing

@Composable
private fun TypingContent(
    vm: SearchViewModel,
    lookup: DownloadLookup,
    actions: LinkActions,
    online: Boolean,
    onSubmit: (String) -> Unit,
    onTrackMore: (Track) -> Unit,
    onScroll: () -> Unit,
) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val typed = vm.query.text.trim()
    val isLink = remember(typed) { YouTubeLinks.contains(typed) }
    val navigator = navigator()
    // Saveable: coming back from an album or artist opened here keeps the scroll.
    val listState = rememberLazyListState()
    HideKeyboardOnScroll(listState, onScroll)
    val card = vm.link
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = LocalBottomInset.current)) {
        linkCard(card, lookup, actions)
        if (isLink) {
            item(key = "open-link") { OpenLinkRow(onClick = { onSubmit(typed) }, modifier = Modifier.animateItem()) }
        } else {
            items(suggestions, key = { "sug:$it" }) { s ->
                SuggestionRow(s, typed, onClick = { onSubmit(s) }, onFill = { vm.fillWith(s) }, modifier = Modifier.animateItem())
            }
        }
        if (!online && !isLink) {
            item(key = "offline-note") { OfflineNote(Modifier.animateItem()) }
        }
        localSection(
            local = local,
            sourceTitle = typed,
            onPlay = vm::playLocal,
            onTrackMore = onTrackMore,
            onAlbum = navigator::openAlbum,
            onArtist = navigator::openArtist,
            onPlaylist = navigator::openPlaylist,
            topGap = suggestions.isNotEmpty() || isLink,
            nowPlaying = nowPlaying,
        )
    }
}

@Composable
private fun OfflineNote(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = SoundColors.TextFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.search_typing_offline), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted)
    }
}

/** "Na sua biblioteca": songs play right away; albums, artists and playlists open. */
private fun LazyListScope.localSection(
    local: LocalSearchResult?,
    sourceTitle: String,
    onPlay: (List<Track>, Int, String) -> Unit,
    onTrackMore: (Track) -> Unit,
    onAlbum: (com.villamediana.sound.data.db.AlbumKey) -> Unit,
    onArtist: (String) -> Unit,
    onPlaylist: (Long) -> Unit,
    topGap: Boolean,
    nowPlaying: NowPlaying?,
) {
    if (local == null || local.isEmpty) return
    if (topGap) item(key = "local-gap") { Spacer(Modifier.height(18.dp)) }
    item(key = "local-header") {
        SectionHeader(title = stringResource(R.string.search_in_library), modifier = Modifier.animateItem())
    }
    itemsIndexed(local.tracks.take(4), key = { _, t -> "lt:${t.id}" }) { index, track ->
        val current = track.videoId?.let { nowPlaying.of(it) }
        TrackRow(
            track = track,
            onClick = { onPlay(local.tracks, index, sourceTitle) },
            onMore = { onTrackMore(track) },
            showDuration = track.durationMs > 0,
            isCurrent = current != null,
            isPlaying = current == true,
            modifier = Modifier.animateItem(),
        )
    }
    items(local.albums.take(3), key = { "la:${it.key.normalized}" }) { album ->
        LocalRow(
            cover = { Cover(album.coverPath, Modifier.size(Dimens.RowCover), seed = album.title) },
            title = album.title,
            subtitle = stringResource(R.string.search_kind_album) + " · " + album.artist,
            onClick = { onAlbum(album.key) },
            modifier = Modifier.animateItem(),
        )
    }
    items(local.artists.take(3), key = { "lr:${it.name}" }) { artist ->
        LocalRow(
            cover = { Cover(artist.coverPath, Modifier.size(Dimens.RowCover), seed = artist.name, shape = CircleShape) },
            title = artist.name,
            subtitle = stringResource(R.string.search_kind_artist),
            onClick = { onArtist(artist.name) },
            modifier = Modifier.animateItem(),
        )
    }
    items(local.playlists.take(2), key = { "lp:${it.id}" }) { playlist ->
        LocalRow(
            cover = {
                if (playlist.coverPath != null) Cover(playlist.coverPath, Modifier.size(Dimens.RowCover), seed = playlist.name)
                else MosaicCover(playlist.mosaic, Modifier.size(Dimens.RowCover), seed = playlist.name)
            },
            title = playlist.name,
            subtitle = stringResource(R.string.search_kind_playlist) + " · " + pluralStringResource(R.plurals.n_songs, playlist.trackCount, playlist.trackCount),
            onClick = { onPlaylist(playlist.id) },
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun LocalRow(cover: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(Dimens.RowHeight).clickable(onClick = onClick).padding(horizontal = Dimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------- results

private enum class Phase { Loading, Error, Empty, List }

@Composable
private fun ResultsContent(
    vm: SearchViewModel,
    lookup: DownloadLookup,
    actions: LinkActions,
    onTrackMore: (Track) -> Unit,
    onScroll: () -> Unit,
) {
    val results by vm.results.collectAsStateWithLifecycle()
    val r = results
    val phase = when {
        r == null || r.loading -> Phase.Loading
        r.error != null -> Phase.Error
        r.items.isEmpty() -> Phase.Empty
        else -> Phase.List
    }
    AnimatedContent(
        targetState = (r?.filter ?: vm.filter) to phase,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(110)) },
        label = "results",
    ) { (filter, p) ->
        when (p) {
            Phase.Loading -> ResultsSkeleton(filter, vm.link, lookup, actions)
            Phase.Error -> if (r?.error == SearchError.Offline) {
                OfflineResults(vm, lookup, actions, onTrackMore)
            } else {
                ErrorState(r?.error ?: SearchError.Generic, r?.errorDetail, onRetry = vm::retry, card = vm.link, lookup = lookup, actions = actions)
            }
            Phase.Empty -> CenteredList(vm.link, lookup, actions) {
                EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body))
            }
            Phase.List -> if (r != null) ResultsList(vm, r, lookup, actions, onScroll)
        }
    }
}

@Composable
private fun ResultsSkeleton(filter: SearchFilter, card: LinkCard?, lookup: DownloadLookup, actions: LinkActions) {
    LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false, contentPadding = PaddingValues(top = 4.dp)) {
        linkCard(card, lookup, actions)
        items(10) { i ->
            SkeletonRow(round = filter == SearchFilter.ARTISTS, widthFraction = skeletonWidth(i))
        }
    }
}

@Composable
private fun CenteredList(card: LinkCard?, lookup: DownloadLookup, actions: LinkActions, content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomInset.current)) {
        linkCard(card, lookup, actions)
        item(key = "state") { Box(Modifier.fillMaxWidth().padding(top = 24.dp)) { content() } }
    }
}

@Composable
private fun ErrorState(error: SearchError, detail: String?, onRetry: () -> Unit, card: LinkCard?, lookup: DownloadLookup, actions: LinkActions) {
    var showDetail by remember { mutableStateOf(false) }
    CenteredList(card, lookup, actions) {
        EmptyState(
            icon = Icons.Rounded.ErrorOutline,
            title = stringResource(R.string.search_error_title),
            body = stringResource(if (error == SearchError.RateLimited) R.string.search_error_rate_limited else R.string.search_error_body),
            action = {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PillButton(stringResource(R.string.search_retry), onRetry, icon = Icons.Rounded.Refresh, filled = true)
                    if (detail != null) {
                        Text(
                            if (showDetail) detail else stringResource(R.string.search_error_details),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoundColors.TextFaint,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 18.dp).clickable { showDetail = !showDetail }.padding(8.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun OfflineResults(vm: SearchViewModel, lookup: DownloadLookup, actions: LinkActions, onTrackMore: (Track) -> Unit) {
    val local by vm.local.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val navigator = navigator()
    val hasLocal = local?.isEmpty == false
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomInset.current)) {
        linkCard(vm.link, lookup, actions)
        item(key = "offline") {
            EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.search_offline_title),
                body = stringResource(if (hasLocal) R.string.search_offline_body else R.string.search_offline_body_empty),
                modifier = Modifier.padding(bottom = if (hasLocal) 0.dp else 24.dp),
            )
        }
        localSection(
            local = local,
            sourceTitle = vm.submitted.orEmpty(),
            onPlay = vm::playLocal,
            onTrackMore = onTrackMore,
            onAlbum = navigator::openAlbum,
            onArtist = navigator::openArtist,
            onPlaylist = navigator::openPlaylist,
            topGap = false,
            nowPlaying = nowPlaying,
        )
    }
}

@Composable
private fun ResultsList(vm: SearchViewModel, r: ResultsState, lookup: DownloadLookup, actions: LinkActions, onScroll: () -> Unit) {
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val likeDownloads by vm.likeDownloads.collectAsStateWithLifecycle()
    val downloadWait by vm.downloadWait.collectAsStateWithLifecycle()
    val navigator = navigator()
    val haptics = LocalHapticFeedback.current
    // Fresh per search/filter, but saved: Back from an album or playlist returns to the same row.
    val listState = rememberSaveable(r.query, r.filter, saver = LazyListState.Saver) { LazyListState() }
    HideKeyboardOnScroll(listState, onScroll)
    val songs = remember(r.items) { r.items.mapNotNull { (it as? SearchItem.Song)?.track } }
    val selection = vm.selection
    val selecting = selection.isNotEmpty()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val playOnline by vm.playOnline.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()

    // Infinite scroll: ask for the next page when the end gets close.
    LaunchedEffect(listState, r.query, r.filter) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= total - 6) vm.loadMore() }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomInset.current + if (selecting) 76.dp else 0.dp),
    ) {
        linkCard(vm.link, lookup, actions)
        items(r.items, key = { it.key }, contentType = { if (it is SearchItem.Song) "song" else "collection" }) { item ->
            when (item) {
                is SearchItem.Song -> {
                    val track = item.track
                    val state = lookup.stateOf(track.videoId)
                    val selectable = state == ItemDownload.None || state is ItemDownload.Failed
                    val tap = {
                        if (SongTapAction.of(state, playOnline, online) == SongTapAction.Download) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.onSongTap(track, songs, r.query)
                    }
                    val keep = {
                        if (state == ItemDownload.None) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.onIndicatorTap(track, songs, r.query)
                    }
                    val current = nowPlaying.of(track.videoId)
                    RemoteSongRow(
                        track = track,
                        state = state,
                        onClick = { if (selecting) { if (selectable) vm.toggleSelection(track) } else tap() },
                        onIndicatorClick = keep,
                        isCurrent = current != null,
                        isPlaying = current == true,
                        onLongClick = {
                            if (selectable) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.toggleSelection(track)
                            }
                        },
                        subtitle = if (r.filter == SearchFilter.VIDEOS) {
                            stringResource(R.string.search_kind_video) + " · " + remoteSubtitle(track)
                        } else {
                            remoteSubtitle(track)
                        },
                        selecting = selecting,
                        selected = track.videoId in selection,
                        liked = track.videoId in likedIds,
                        onLike = { vm.like(track, it) },
                        showDownload = !likeDownloads,
                        wait = downloadWait,
                        modifier = Modifier.animateItem(),
                    )
                }
                is SearchItem.Collection -> RemoteCollectionRow(
                    collection = item.collection,
                    onClick = { navigator.openRemote(item.collection) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        when {
            r.loadingMore -> items(2, key = { "more-skeleton-$it" }) { i ->
                SkeletonRow(round = r.filter == SearchFilter.ARTISTS, widthFraction = skeletonWidth(i + 3))
            }
            r.moreError -> item(key = "more-error") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.search_more_error), style = MaterialTheme.typography.bodyMedium, color = SoundColors.TextMuted, modifier = Modifier.weight(1f))
                    PillButton(stringResource(R.string.search_retry), vm::retry, icon = Icons.Rounded.Refresh)
                }
            }
        }
    }
}

@Composable
private fun HideKeyboardOnScroll(state: LazyListState, onScroll: () -> Unit) {
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (it) onScroll() }
    }
}

/** Kind of a collection, for the overline of online pages. */
@Composable
fun collectionOverline(kind: CollectionKind): String = stringResource(
    when (kind) {
        CollectionKind.ALBUM -> R.string.search_kind_album_upper
        CollectionKind.PLAYLIST -> R.string.search_kind_playlist_upper
        CollectionKind.ARTIST -> R.string.search_kind_artist_upper
    },
)
