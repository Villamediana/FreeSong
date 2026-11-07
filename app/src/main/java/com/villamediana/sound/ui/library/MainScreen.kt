package com.villamediana.sound.ui.library

import kotlinx.coroutines.launch
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.villamediana.sound.ui.nav.searchRevealOrigin
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.download.DownloadSummary
import com.villamediana.sound.ui.components.FilterPill
import com.villamediana.sound.ui.components.SoundWordmark
import com.villamediana.sound.ui.home.HeaderActions
import com.villamediana.sound.ui.home.HomeScreen
import com.villamediana.sound.ui.home.LocalHomeEmbedded
import com.villamediana.sound.ui.nav.Chrome
import com.villamediana.sound.ui.nav.navigator
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.sheets.SheetAction
import com.villamediana.sound.ui.sheets.SoundSheet
import androidx.compose.material.icons.rounded.Download

/** The pills of the main screen: the Home, then the library by kind. */
enum class MainTab(@StringRes val label: Int, val filter: LibFilter?) {
    HOME(R.string.main_tab_home, null),
    SONGS(R.string.main_tab_songs, LibFilter.SONGS),
    PLAYLISTS(R.string.main_tab_playlists, LibFilter.PLAYLISTS),
    ALBUMS(R.string.main_tab_albums, LibFilter.ALBUMS),
    ARTISTS(R.string.main_tab_artists, LibFilter.ARTISTS),
    GENRES(R.string.main_tab_genres, LibFilter.GENRES),
    ;

    companion object {
        /** The pills on screen: all, or all but Início when it is turned off. */
        fun visible(showHome: Boolean): List<MainTab> = if (showHome) entries else entries - HOME

        /** Where the app opens. */
        fun start(showHome: Boolean): MainTab = if (showHome) HOME else SONGS
    }
}

/**
 * The one main screen (no bottom bar): brand + search + settings on top, pills right below, and
 * the content switching in place — Início (suggestions) or the library by kind.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val nav = navigator()
    // "Aba Início" off (Settings): no Início pill, the app opens on the songs.
    val settingsStore = com.villamediana.sound.SoundApplication.graph.settings
    val firstShowHome = remember { kotlinx.coroutines.runBlocking { settingsStore.current().showHome } }
    val showHome by remember { settingsStore.settings.map { it.showHome }.distinctUntilChanged() }.collectAsState(initial = firstShowHome)
    val tabs = MainTab.visible(showHome)
    var tab by rememberSaveable { mutableStateOf(MainTab.start(firstShowHome)) }
    LaunchedEffect(showHome) { if (tab !in tabs) tab = MainTab.start(showHome) }
    // The tabs are pages side by side: a sideways swipe (on the content or on the pills) moves to the next one.
    val pager = rememberPagerState(initialPage = tabs.indexOf(tab).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    // The page in view names the tab (already halfway through a swipe, so the pill follows the finger).
    LaunchedEffect(pager, tabs) { snapshotFlow { pager.currentPage }.collect { p -> tabs.getOrNull(p)?.let { tab = it } } }
    // A tab chosen elsewhere (a pill, a shared Spotify link, Início turned off) brings its page.
    LaunchedEffect(tab, tabs) {
        val i = tabs.indexOf(tab)
        if (i >= 0 && i != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(i)
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    // The Playlists +: a new one, or one imported from Spotify.
    var choosing by rememberSaveable { mutableStateOf(false) }
    // A Spotify link shared to FreeSong (or the import asked from elsewhere) lands on Playlists.
    LaunchedEffect(SpotifyImportRequest.open) { if (SpotifyImportRequest.open) tab = MainTab.PLAYLISTS }
    SpotifyImportFinished()
    val downloads by SoundApplication.graph.downloads.summary.collectAsStateWithLifecycle(DownloadSummary())
    // The genre intro takes the whole screen: no header, no pills.
    val intro = Chrome.hideMiniPlayer && tab == MainTab.HOME

    Column(Modifier.fillMaxSize().background(SoundColors.Ink)) {
        AnimatedVisibility(visible = !intro, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
            Column {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Row(
                    Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = 6.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SoundWordmark(Modifier.weight(1f))
                    if (tab == MainTab.PLAYLISTS) {
                        IconButton(onClick = { choosing = true }) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.home_lib_new_playlist), tint = SoundColors.Text, modifier = Modifier.size(28.dp))
                        }
                    }
                    IconButton(onClick = { nav.openSearchScreen() }, modifier = Modifier.searchRevealOrigin()) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.tab_search), tint = SoundColors.Text, modifier = Modifier.size(27.dp))
                    }
                    HeaderActions(downloads, nav)
                }
                TabPills(
                    tabs = tabs,
                    selected = tab,
                    pager = pager,
                    onSelect = { t ->
                        val i = tabs.indexOf(t)
                        if (i >= 0) scope.launch { pager.animateScrollToPage(i) }
                    },
                )
                Spacer(Modifier.height(2.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                // The genre intro is a step of its own: no swiping away from it.
                userScrollEnabled = !intro,
                beyondViewportPageCount = 1,
                key = { tabs.getOrNull(it)?.name ?: it },
            ) { page ->
                val t = tabs.getOrNull(page) ?: return@HorizontalPager
                CompositionLocalProvider(LocalHomeEmbedded provides true) {
                    if (t == MainTab.HOME) HomeScreen() else LibraryScreen(embeddedFilter = t.filter)
                }
            }
        }
    }

    if (choosing) {
        SoundSheet(onDismissRequest = { choosing = false }) {
            SheetAction(Icons.Rounded.Add, stringResource(R.string.home_lib_new_playlist), onClick = { choosing = false; creating = true })
            SheetAction(Icons.Rounded.Download, stringResource(R.string.spotify_import_title), onClick = { choosing = false; SpotifyImportRequest.show() })
        }
    }
    if (SpotifyImportRequest.open) {
        SpotifyImportSheet(
            onDismiss = { SpotifyImportRequest.open = false },
            onOpen = { nav.openRemote(it) },
        )
    }

    if (creating) {
        val vm: LibraryViewModel = viewModel()
        CreatePlaylistDialog(
            onCreate = { name, description ->
                creating = false
                vm.createPlaylist(name, description) { id -> nav.openPlaylist(id) }
            },
            onDismiss = { creating = false },
        )
    }
}

/** An import that ends with its sheet closed says so (and the sheet starts fresh next time). */
@Composable
private fun SpotifyImportFinished() {
    val importer = SoundApplication.graph.spotifyImport
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by importer.state.collectAsState()
    LaunchedEffect(state, SpotifyImportRequest.open) {
        val done = state as? com.villamediana.sound.online.SpotifyImporter.State.Done ?: return@LaunchedEffect
        if (SpotifyImportRequest.open) return@LaunchedEffect
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.spotify_import_finished, done.playlist.name),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        importer.reset()
    }
}

/**
 * The tab pills. They follow the pages: the selected one is scrolled into view, and a sideways
 * drag on the row moves the pages with the finger (the "menu" swipes too); a tap jumps there.
 */
@Composable
private fun TabPills(tabs: List<MainTab>, selected: MainTab, pager: PagerState, onSelect: (MainTab) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gutter = with(density) { Dimens.Gutter.toPx() }
    val spans = remember { mutableStateMapOf<MainTab, Pair<Float, Float>>() }
    var viewport by remember { mutableStateOf(0) }
    LaunchedEffect(selected, viewport, spans[selected]) {
        val (x, w) = spans[selected] ?: return@LaunchedEffect
        if (viewport <= 0) return@LaunchedEffect
        // Keep the selected pill in view, a little room on its sides.
        val target = when {
            x - gutter < scroll.value -> x - gutter
            x + w + gutter > scroll.value + viewport -> x + w + gutter - viewport
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
    }
    val drag = rememberDraggableState { delta -> pager.dispatchRawDelta(-delta) }
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val here = pager.currentPage + pager.currentPageOffsetFraction
                    val target = when {
                        velocity < -FLING_VELOCITY -> kotlin.math.floor(here).toInt() + 1
                        velocity > FLING_VELOCITY -> kotlin.math.ceil(here).toInt() - 1
                        else -> kotlin.math.round(here).toInt()
                    }.coerceIn(0, tabs.lastIndex)
                    scope.launch { pager.animateScrollToPage(target) }
                },
            )
            .horizontalScroll(scroll, enabled = false)
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            FilterPill(
                stringResource(t.label),
                selected = t == selected,
                onClick = { onSelect(t) },
                modifier = Modifier.onGloballyPositioned { c ->
                    spans[t] = (c.positionInParent().x) to c.size.width.toFloat()
                },
            )
        }
    }
}

private const val FLING_VELOCITY = 600f
