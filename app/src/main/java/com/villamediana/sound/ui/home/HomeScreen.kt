package com.villamediana.sound.ui.home

import com.villamediana.sound.ui.nav.Chrome
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.ui.components.LikedArt
import com.villamediana.sound.ui.components.PillButton
import com.villamediana.sound.ui.components.PlayPauseButton
import com.villamediana.sound.ui.components.SectionHeader
import com.villamediana.sound.ui.components.SoundMark
import com.villamediana.sound.ui.components.SoundWordmark
import com.villamediana.sound.ui.library.CollectionMenuSheet
import com.villamediana.sound.ui.library.CollectionTarget
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.nav.SearchRoute
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.sheets.TrackActionsSheet
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.delay
import java.time.LocalTime

private enum class HomePhase { LOADING, EMPTY, PICKER, DISCOVER, FEED }

@Composable
fun HomeScreen() {
    val vm: HomeViewModel = viewModel()
    val discoverVm: DiscoverViewModel = viewModel()
    val discover by discoverVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { discoverVm.onShown() }
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val nav = navigator()

    // Staggered entrance only on the first load; items composed later (scrolling, returning) just appear.
    var intro by remember { mutableStateOf(!vm.introPlayed) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && intro) {
            delay(1_200)
            vm.introPlayed = true
            intro = false
        }
    }

    var songSheet by remember { mutableStateOf<Track?>(null) }
    var menuTarget by remember { mutableStateOf<CollectionTarget?>(null) }

    val phase = when {
        !state.loaded || !discover.ready -> HomePhase.LOADING
        discover.showPicker && (state.empty || discover.editing) -> HomePhase.PICKER
        state.empty && discover.favorites.isNotEmpty() -> HomePhase.DISCOVER
        state.empty -> HomePhase.EMPTY
        else -> HomePhase.FEED
    }
    val playDiscover: (List<com.villamediana.sound.online.RemoteTrack>, Int, String, String) -> Unit = { tracks, i, title, id -> discoverVm.play(tracks, i, title, id) }
    val openArtist: (String) -> Unit = { name -> discoverVm.openArtist(name) { nav.openRemote(it) } }
    val openPlaylist: (com.villamediana.sound.online.RemoteCollection) -> Unit = { nav.openRemote(it) }
    // The genre picker is an intro: no mini player over it. Back to normal when it goes (or Home does).
    val picking = phase == HomePhase.PICKER
    DisposableEffect(picking) {
        Chrome.hideMiniPlayer = picking
        onDispose { Chrome.hideMiniPlayer = false }
    }

    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(180)) },
            label = "homePhase",
        ) { p ->
            when (p) {
                HomePhase.LOADING -> Box(Modifier.fillMaxSize())
                HomePhase.EMPTY -> EmptyHome(downloads, nav, intro)
                HomePhase.PICKER -> GenrePicker(
                    state = discover,
                    downloads = downloads,
                    nav = nav,
                    onToggle = discoverVm::toggle,
                    onContinue = discoverVm::confirm,
                    onSkip = discoverVm::skip,
                )
                HomePhase.DISCOVER -> DiscoverHome(discover, playback, downloads, nav, onPlay = playDiscover, onArtist = openArtist, onPlaylist = openPlaylist, onEdit = discoverVm::edit)
                HomePhase.FEED -> HomeFeed(
                    state = state,
                    playback = playback,
                    downloads = downloads,
                    vm = vm,
                    nav = nav,
                    intro = intro,
                    onSong = { songSheet = it },
                    onMenu = { menuTarget = it },
                    extra = { discoverSections(discover, playback, playDiscover, openArtist, openPlaylist, discoverVm::edit) },
                )
            }
        }
    }

    songSheet?.let { TrackActionsSheet(track = it, onDismiss = { songSheet = null }) }
    menuTarget?.let { CollectionMenuSheet(target = it, onDismiss = { menuTarget = null }) }
}

/** Current hour, refreshed when the clock turns. */
@Composable
private fun rememberHour(): Int {
    val hour by produceState(initialValue = LocalTime.now().hour) {
        while (true) {
            val now = LocalTime.now()
            val toNextHour = ((59 - now.minute) * 60L + (60 - now.second)) * 1_000L
            delay(toNextHour.coerceAtLeast(1_000L))
            value = LocalTime.now().hour
        }
    }
    return hour
}

@Composable
internal fun greetingText(): String = stringResource(
    when (greetingFor(rememberHour())) {
        Greeting.MORNING -> R.string.home_greeting_morning
        Greeting.AFTERNOON -> R.string.home_greeting_afternoon
        Greeting.NIGHT -> R.string.home_greeting_night
    },
)

@Composable
internal fun HeaderActions(downloads: DownloadSummary, nav: Navigator) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DownloadsIndicator(downloads, onClick = { nav.openDownloads() })
        IconButton(onClick = { nav.openSettings() }) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.home_settings), tint = SoundColors.Text, modifier = Modifier.size(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------- feed

@Composable
private fun HomeFeed(
    state: HomeUiState,
    playback: PlaybackState,
    downloads: DownloadSummary,
    vm: HomeViewModel,
    nav: Navigator,
    intro: Boolean,
    onSong: (Track) -> Unit,
    onMenu: (CollectionTarget) -> Unit,
    extra: LazyListScope.() -> Unit = {},
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()
    val playedTitle = stringResource(R.string.home_recently_played)
    val addedTitle = stringResource(R.string.home_recently_added)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top + 10.dp, bottom = LocalBottomInset.current),
    ) {
        var order = 0

        val headerIndex = order++
        item(key = "header", contentType = "header") {
          Column(Modifier.animateItem().staggerIn(headerIndex, intro).fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.Gutter, end = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SoundWordmark(Modifier.weight(1f))
                HeaderActions(downloads, nav)
            }
            Text(
                greetingText(),
                style = MaterialTheme.typography.headlineMedium,
                color = SoundColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = Dimens.Gutter, bottom = 14.dp),
            )
          }
        }

        if (state.quick.isNotEmpty()) {
            val idx = order++
            item(key = "quick", contentType = "quick") {
                QuickAccess(state.quick, playback, nav, onMenu, Modifier.animateItem().staggerIn(idx, intro))
            }
        }

        val heroIdx = order++
        item(key = "hero", contentType = "hero") {
            val smartActive = playback.hasMedia && playback.isSmartLibrary()
            SmartShuffleHero(
                cover = state.heroCovers.firstOrNull(),
                playing = smartActive && playback.isPlaying,
                onClick = { if (smartActive) vm.togglePlayPause() else vm.playSmartLibrary() },
                modifier = Modifier.animateItem().staggerIn(heroIdx, intro).padding(top = if (state.quick.isEmpty()) 0.dp else Dimens.SectionGap - 6.dp),
            )
        }

        if (state.mixes.isNotEmpty()) {
            val idx = order++
            item(key = "mixes", contentType = "carousel") {
                Carousel(stringResource(R.string.home_made_for_you), Modifier.animateItem().staggerIn(idx, intro)) {
                    items(state.mixes, key = { it.id }) { mix ->
                        val active = playback.hasMedia && playback.isFromMix(mix.id)
                        MixCard(
                            mix = mix,
                            playing = active && playback.isPlaying,
                            onOpen = { nav.openMix(mix.id) },
                            onPlay = { if (active) vm.togglePlayPause() else vm.playMix(mix.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        if (state.recentlyPlayed.isNotEmpty()) {
            val idx = order++
            item(key = "played", contentType = "carousel") {
                Carousel(playedTitle, Modifier.animateItem().staggerIn(idx, intro)) {
                    homeItems(state.recentlyPlayed, playback, nav, playedTitle, vm, onSong, onMenu)
                }
            }
        }

        if (state.recentlyAdded.isNotEmpty()) {
            val idx = order++
            item(key = "added", contentType = "carousel") {
                Carousel(addedTitle, Modifier.animateItem().staggerIn(idx, intro)) {
                    homeItems(state.recentlyAdded, playback, nav, addedTitle, vm, onSong, onMenu)
                }
            }
        }

        if (state.genres.isNotEmpty()) {
            val idx = order++
            item(key = "genres", contentType = "carousel") {
                Carousel(stringResource(R.string.home_by_genre), Modifier.animateItem().staggerIn(idx, intro)) {
                    items(state.genres, key = { "g:" + it.name.lowercase() }) { genre ->
                        GenreCard(genre, onClick = { nav.openGenre(genre.name) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }

        if (state.artists.isNotEmpty()) {
            val idx = order++
            item(key = "artists", contentType = "carousel") {
                Carousel(stringResource(R.string.home_your_artists), Modifier.animateItem().staggerIn(idx, intro)) {
                    items(state.artists, key = { "r:" + it.name.lowercase() }) { artist ->
                        ArtistAvatar(artist.name, artist.coverPath, onClick = { nav.openArtist(artist.name) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }

        extra()
    }
}

@Composable
private fun Carousel(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = Dimens.SectionGap - 8.dp)) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.Gutter),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Albums and loose songs; tapping a song plays the songs of that carousel from it. */
private fun LazyListScope.homeItems(
    list: List<HomeItem>,
    playback: PlaybackState,
    nav: Navigator,
    sourceTitle: String,
    vm: HomeViewModel,
    onSong: (Track) -> Unit,
    onMenu: (CollectionTarget) -> Unit,
) {
    val songs = list.filterIsInstance<HomeItem.SongItem>().map { it.track }
    itemsIndexed(list, key = { _, it -> it.key }) { _, item ->
        when (item) {
            is HomeItem.AlbumItem -> {
                val a = item.summary
                CoverCard(
                    cover = a.coverPath,
                    seed = a.title,
                    title = a.title,
                    subtitle = a.artist,
                    onClick = { nav.openAlbum(a.key) },
                    onLongClick = { onMenu(CollectionTarget.Album(a)) },
                    playing = if (playback.isFromAlbum(a.key.normalized)) playback.isPlaying else null,
                    modifier = Modifier.animateItem(),
                )
            }
            is HomeItem.SongItem -> {
                val t = item.track
                CoverCard(
                    cover = t.coverPath,
                    seed = t.album ?: t.title,
                    title = t.title,
                    subtitle = t.artist,
                    onClick = { vm.playSongs(songs, songs.indexOfFirst { it.id == t.id }, sourceTitle) },
                    onLongClick = { onSong(t) },
                    playing = if (playback.current?.id == t.id) playback.isPlaying else null,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun QuickAccess(
    tiles: List<QuickTile>,
    playback: PlaybackState,
    nav: Navigator,
    onMenu: (CollectionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val likedTitle = stringResource(R.string.home_liked)
    QuickGrid(
        modifier = modifier,
        content = tiles.map { tile ->
            @Composable { m: Modifier ->
                when (tile) {
                    is QuickTile.Liked -> QuickTileView(
                        title = likedTitle,
                        art = { LikedArt(it, shape = RectangleShape) },
                        onClick = { nav.openLiked() },
                        current = playback.hasMedia && playback.isFromLiked(),
                        playing = playback.isPlaying,
                        modifier = m,
                    )
                    is QuickTile.PlaylistTile -> {
                        val p = tile.summary
                        QuickTileView(
                            title = p.name,
                            art = { if (p.coverPath != null) TileCover(p.coverPath, p.name, it) else TileMosaic(p.mosaic, p.name, it) },
                            onClick = { nav.openPlaylist(p.id) },
                            onLongClick = { onMenu(CollectionTarget.Playlist(p)) },
                            current = playback.hasMedia && playback.isFromPlaylist(p.id),
                            playing = playback.isPlaying,
                            modifier = m,
                        )
                    }
                    is QuickTile.AlbumTile -> {
                        val a = tile.summary
                        QuickTileView(
                            title = a.title,
                            art = { TileCover(a.coverPath, a.title, it) },
                            onClick = { nav.openAlbum(a.key) },
                            onLongClick = { onMenu(CollectionTarget.Album(a)) },
                            current = playback.isFromAlbum(a.key.normalized),
                            playing = playback.isPlaying,
                            modifier = m,
                        )
                    }
                }
            }
        },
    )
}

/** The smart shuffle hero: one tap plays the whole library, weighted by taste. */
@Composable
private fun SmartShuffleHero(cover: String?, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(SoundColors.SoftTop, SoundColors.SoftBottom)))
                .pressable(onClick, pressed = 0.98f)
                .padding(start = 22.dp, top = 22.dp, bottom = 22.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_smart_title), style = MaterialTheme.typography.headlineMedium, color = SoundColors.Text)
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.home_smart_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoundColors.Text.copy(alpha = 0.75f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                PlayPauseButton(
                    playing = playing,
                    onClick = onClick,
                    size = 52.dp,
                    container = SoundColors.Mint,
                    content = SoundColors.OnMint,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                )
            }
            Spacer(Modifier.size(12.dp))
            com.villamediana.sound.ui.components.SoundBars(Modifier.size(96.dp), color = SoundColors.Mint)
        }
    }
}

// ---------------------------------------------------------------------------- empty

@Composable
private fun EmptyHome(downloads: DownloadSummary, nav: Navigator, intro: Boolean) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().padding(bottom = LocalBottomInset.current)) {
        Row(Modifier.align(Alignment.TopEnd).padding(top = top + 6.dp, end = 8.dp)) {
            HeaderActions(downloads, nav)
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SoundMark(
                size = 88.dp,
                modifier = Modifier.staggerIn(0, intro),
            )
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.home_empty_line),
                style = MaterialTheme.typography.headlineLarge,
                color = SoundColors.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp).staggerIn(1, intro),
            )
            Spacer(Modifier.height(28.dp))
            PillButton(
                text = stringResource(R.string.home_empty_action),
                onClick = { nav.switchTab(SearchRoute) },
                icon = Icons.Rounded.Search,
                filled = true,
                modifier = Modifier.staggerIn(2, intro),
            )
        }
    }
}
