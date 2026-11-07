package com.villamediana.sound.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.components.EmptyState
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.PagePlayButton
import com.villamediana.sound.ui.components.PageShuffleButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.Share
import com.villamediana.sound.ui.components.formatDuration
import com.villamediana.sound.ui.components.formatLongDuration
import com.villamediana.sound.ui.components.pressScale
import com.villamediana.sound.ui.detail.CollectionMenuSheet
import com.villamediana.sound.ui.detail.MenuItem
import com.villamediana.sound.ui.nav.RemoteCollectionRoute
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.launch

/** Online album/playlist/artist before downloading: header washed in the cover color, songs, "Baixar tudo". */
@Composable
fun RemoteCollectionScreen(route: RemoteCollectionRoute) {
    val vm: RemoteCollectionViewModel = viewModel(key = "remote:${route.kind}:${route.url}") { RemoteCollectionViewModel.create(route) }
    val navigator = navigator()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bottomInset = LocalBottomInset.current

    val load by vm.load.collectAsStateWithLifecycle()
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val likeDownloads by vm.likeDownloads.collectAsStateWithLifecycle()
    val downloadWait by vm.downloadWait.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val playOnline by vm.playOnline.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val detail = (load as? RemoteLoad.Loaded)?.detail
    val kind = vm.kind
    val title = detail?.collection?.title?.takeIf { it.isNotBlank() } ?: route.title
    val author = (detail?.collection?.author ?: route.author)?.takeIf { it.isNotBlank() && kind != CollectionKind.ARTIST }
    val thumbnail = detail?.collection?.thumbnailUrl ?: route.thumbnailUrl
    val wash = rememberRemoteCoverColor(thumbnail)

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is RemoteEvent.Open -> when (val d = event.destination) {
                    is LocalDestination.Playlist -> navigator.openPlaylist(d.id)
                    is LocalDestination.Album -> navigator.openAlbum(d.key)
                    is LocalDestination.Artist -> navigator.openArtist(d.name)
                }
                is RemoteEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch { snackbar.showSnackbar(context.getString(event.text)) }
                }
            }
        }
    }

    val listState = rememberLazyListState()
    var headerHeight by remember { mutableIntStateOf(0) }
    val barAlpha by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                headerHeight == 0 -> 0f
                else -> ((listState.firstVisibleItemScrollOffset - headerHeight * 0.5f) / (headerHeight * 0.22f)).coerceIn(0f, 1f)
            }
        }
    }

    val action = PrimaryAction.of(kind == CollectionKind.PLAYLIST, progress, vm.localPlaylistId != null, vm.enqueuing)
    // Streaming needs the network; without it the songs already on the phone still play.
    val canPlay = online || progress.done > 0

    fun message(text: String) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(text) }
    }

    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomInset),
        ) {
            item(key = "header", contentType = "header") {
                RemoteHeader(
                    kind = kind,
                    title = title,
                    author = author,
                    thumbnail = thumbnail,
                    wash = wash,
                    year = detail?.collection?.year,
                    tracks = detail?.tracks,
                    loading = load is RemoteLoad.Loading,
                    action = action,
                    progress = progress,
                    hasLocalPlaylist = vm.localPlaylistId != null,
                    onDownloadAll = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.downloadAll()
                    },
                    onOpenLibrary = vm::openInLibrary,
                    onOpenDownloads = navigator::openDownloads,
                    onRetryFailed = vm::retryFailed,
                    here = here,
                    canPlay = canPlay,
                    onPlay = { vm.playAll(shuffle = false) },
                    onShuffle = { vm.playAll(shuffle = true) },
                    modifier = Modifier.onSizeChanged { headerHeight = it.height },
                )
            }
            when (val l = load) {
                RemoteLoad.Loading -> items(8, key = { "skeleton-$it" }) { i ->
                    SkeletonRow(widthFraction = skeletonWidth(i), coverSize = if (kind == CollectionKind.ALBUM) 24.dp else Dimens.RowCover)
                }
                is RemoteLoad.Failed -> item(key = "error") {
                    RemoteErrorState(l.error, onRetry = vm::load)
                }
                is RemoteLoad.Loaded -> {
                    val tracks = l.detail.tracks
                    if (tracks.isEmpty() && l.detail.related.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(Icons.Rounded.MusicOff, stringResource(R.string.search_remote_empty_title), stringResource(R.string.search_remote_empty_body))
                        }
                    }
                    if (kind == CollectionKind.ARTIST && tracks.isNotEmpty()) {
                        item(key = "popular") { SectionHeader(stringResource(R.string.search_remote_popular), Modifier.padding(top = 8.dp)) }
                    }
                    itemsIndexed(tracks, key = { i, t -> "$i:${t.videoId}" }, contentType = { _, _ -> "song" }) { index, track ->
                        val state = lookup.stateOf(track.videoId)
                        val current = nowPlaying.of(track.videoId)
                        RemoteSongRow(
                            track = track,
                            state = state,
                            onClick = {
                                if (SongTapAction.of(state, playOnline, online) == SongTapAction.Download) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                vm.onSongTap(track)
                            },
                            onIndicatorClick = {
                                if (state == ItemDownload.None) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                vm.onIndicatorTap(track)
                            },
                            number = if (kind == CollectionKind.ALBUM) index + 1 else null,
                            subtitle = rowSubtitle(kind, track),
                            isCurrent = current != null,
                            isPlaying = current == true,
                            liked = track.videoId in likedIds,
                            onLike = { vm.like(track, it) },
                            showDownload = !likeDownloads,
                            wait = downloadWait,
                        )
                    }
                    if (kind == CollectionKind.ARTIST && l.detail.related.isNotEmpty()) {
                        item(key = "related-header") {
                            SectionHeader(stringResource(R.string.search_remote_albums), Modifier.padding(top = Dimens.SectionGap - 8.dp))
                        }
                        item(key = "related") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Dimens.Gutter),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(l.detail.related, key = { it.kind.name + it.url }) { c ->
                                    RelatedCard(c, onClick = { navigator.openRemote(c) })
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top bar: back always; the title and a tinted background appear once the header scrolls away.
        val barColor = lerp(wash, SoundColors.Ink, 0.62f)
        Row(
            Modifier
                .fillMaxWidth()
                .background(barColor.copy(alpha = barAlpha))
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onClick = navigator::back, backdrop = 0.45f * (1f - barAlpha))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = barAlpha },
            )
            if (detail != null) {
                Spacer(Modifier.width(8.dp))
                BarButton(Icons.Rounded.MoreVert, stringResource(R.string.more_options), onClick = { menuOpen = true }, backdrop = 0.45f * (1f - barAlpha))
            }
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

    val loaded = detail
    if (menuOpen && loaded != null) {
        val res = context.resources
        val count = loaded.tracks.distinctBy { it.videoId }.size
        val items = buildList {
            if (count > 0) {
                add(
                    MenuItem(Icons.Rounded.SubdirectoryArrowRight, res.getString(R.string.detail_menu_play_next)) {
                        val n = vm.queueAll(next = true)
                        if (n > 0) message(res.getQuantityString(R.plurals.detail_play_next_done, n, n))
                    },
                )
                add(
                    MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, res.getString(R.string.detail_menu_enqueue)) {
                        val n = vm.queueAll(next = false)
                        if (n > 0) message(res.getQuantityString(R.plurals.detail_enqueue_done, n, n))
                    },
                )
            }
            add(MenuItem(Icons.Rounded.Share, res.getString(R.string.search_remote_share)) { Share.collection(context, loaded.collection) })
            // Imported from Spotify: it can leave the library.
            if (com.villamediana.sound.online.SpotifyLinks.playlistId(vm.route.url) != null) {
                add(
                    MenuItem(Icons.Rounded.DeleteOutline, res.getString(R.string.spotify_import_remove), destructive = true) {
                        com.villamediana.sound.ui.library.CollectionCommands.removeImported(vm.route.url)
                        navigator.back()
                    },
                )
            }
        }
        CollectionMenuSheet(
            title = title,
            subtitle = listOfNotNull(collectionLabel(kind), author).joinToString(" · "),
            art = { m -> Cover(thumbnail, m, seed = title, shape = if (kind == CollectionKind.ARTIST) CircleShape else RoundedCornerShape(Dimens.CoverCorner)) },
            items = items,
            onDismiss = { menuOpen = false },
        )
    }
}

@Composable
private fun collectionLabel(kind: CollectionKind): String = stringResource(
    when (kind) {
        CollectionKind.ALBUM -> R.string.search_kind_album
        CollectionKind.PLAYLIST -> R.string.search_kind_playlist
        CollectionKind.ARTIST -> R.string.search_kind_artist
    },
)

private fun rowSubtitle(kind: CollectionKind, track: RemoteTrack): String {
    val duration = track.durationMs?.takeIf { it > 0 }?.let(::formatDuration)
    return when (kind) {
        // Album pages: the album is the page itself.
        CollectionKind.ALBUM -> listOfNotNull(track.artist.takeIf { it.isNotBlank() }, duration).joinToString(" · ")
        // Artist pages: the artist is the page itself.
        CollectionKind.ARTIST -> listOfNotNull(track.album?.takeIf { it.isNotBlank() } ?: track.artist.takeIf { it.isNotBlank() }, duration).joinToString(" · ")
        CollectionKind.PLAYLIST -> remoteSubtitle(track)
    }
}

/** Round button of the top bar, on a dark disc while it floats over the cover's color. */
@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit, backdrop: Float) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .background(SoundColors.Ink.copy(alpha = backdrop))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = SoundColors.Text)
    }
}

@Composable
private fun RemoteHeader(
    kind: CollectionKind,
    title: String,
    author: String?,
    thumbnail: String?,
    wash: Color,
    year: Int?,
    tracks: List<RemoteTrack>?,
    loading: Boolean,
    action: PrimaryAction,
    progress: BatchProgress,
    hasLocalPlaylist: Boolean,
    onDownloadAll: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
    onRetryFailed: () -> Unit,
    here: HerePlaying?,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val coverSize = (screenWidth * 0.6f).coerceIn(180.dp, 260.dp)
    val coverShape = if (kind == CollectionKind.ARTIST) CircleShape else RoundedCornerShape(Dimens.CoverCorner)
    Box(modifier.fillMaxWidth()) {
        // The cover color as a soft veil that fades into the background — the only gradient in Sound.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to wash.copy(alpha = 0.9f), 0.5f to wash.copy(alpha = 0.32f), 1f to SoundColors.Ink)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 60.dp, start = Dimens.Gutter, end = Dimens.Gutter, bottom = 10.dp),
        ) {
            Cover(
                thumbnail,
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(coverSize)
                    .shadow(22.dp, coverShape, clip = false),
                seed = title,
                shape = coverShape,
            )
            Spacer(Modifier.height(26.dp))
            Text(
                listOfNotNull(collectionOverline(kind), year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = SoundColors.Text.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = if (title.length > 26) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                color = SoundColors.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (author != null) {
                Spacer(Modifier.height(6.dp))
                Text(author, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            if (tracks != null) {
                val count = tracks.size
                val total = tracks.sumOf { it.durationMs ?: 0L }
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.n_songs, count, count),
                        total.takeIf { it > 0 }?.let(::formatLongDuration),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = SoundColors.TextMuted,
                )
            } else if (loading) {
                Box(Modifier.padding(top = 4.dp).width(120.dp).height(11.dp).shimmer())
            }
            Spacer(Modifier.height(22.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().height(52.dp).shimmer(CircleShape))
            } else {
                val playable = !tracks.isNullOrEmpty()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // No "Baixar tudo": songs are kept one by one (heart / arrow on each row).
                    Spacer(Modifier.weight(1f))
                    if (playable) {
                        val playing = here?.playing == true
                        PageShuffleButton(
                            active = here?.shuffle == true,
                            onClick = onShuffle,
                            enabled = canPlay,
                            label = stringResource(R.string.search_remote_shuffle),
                        )
                        Spacer(Modifier.width(10.dp))
                        PagePlayButton(
                            playing = playing,
                            onClick = onPlay,
                            enabled = canPlay,
                            loading = here?.loading == true,
                            label = stringResource(if (playing) R.string.pause else R.string.search_remote_play),
                        )
                    }
                }
            }

            if (progress.failed > 0 && progress.active == 0 && action !is PrimaryAction.Enqueuing) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = SoundColors.Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(pluralStringResource(R.plurals.search_remote_failed, progress.failed, progress.failed), style = MaterialTheme.typography.bodySmall, color = SoundColors.Danger)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.search_remote_retry_failed),
                        style = MaterialTheme.typography.labelLarge,
                        color = SoundColors.Text,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onRetryFailed).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun RelatedCard(collection: RemoteCollection, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(148.dp)
            .pressScale(interaction, 0.97f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Cover(collection.thumbnailUrl, Modifier.size(148.dp), seed = collection.title)
        Spacer(Modifier.height(8.dp))
        Text(collection.title, style = MaterialTheme.typography.titleSmall, color = SoundColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                stringResource(if (collection.kind == CollectionKind.PLAYLIST) R.string.search_kind_playlist else R.string.search_kind_album),
                collection.year?.toString(),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = SoundColors.TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun RemoteErrorState(error: RemoteError, onRetry: () -> Unit) {
    val (icon, title, body) = when (error) {
        RemoteError.Offline -> Triple(Icons.Rounded.CloudOff, R.string.search_offline_title, R.string.search_remote_offline_body)
        RemoteError.NotFound -> Triple(Icons.Rounded.ErrorOutline, R.string.search_remote_error_title, R.string.search_remote_not_found_body)
        RemoteError.RateLimited -> Triple(Icons.Rounded.ErrorOutline, R.string.search_remote_error_title, R.string.search_error_rate_limited)
        RemoteError.Generic -> Triple(Icons.Rounded.ErrorOutline, R.string.search_remote_error_title, R.string.search_remote_error_body)
    }
    EmptyState(
        icon = icon,
        title = stringResource(title),
        body = stringResource(body),
        action = if (error == RemoteError.NotFound) null else {
            { PillButton(stringResource(R.string.search_retry), onRetry, icon = Icons.Rounded.Refresh, filled = true) }
        },
    )
}
