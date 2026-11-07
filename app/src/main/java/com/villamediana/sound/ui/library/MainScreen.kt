package com.villamediana.sound.ui.library

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
    var creating by rememberSaveable { mutableStateOf(false) }
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
                        IconButton(onClick = { creating = true }) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.home_lib_new_playlist), tint = SoundColors.Text, modifier = Modifier.size(28.dp))
                        }
                    }
                    IconButton(onClick = { nav.openSearchScreen() }, modifier = Modifier.searchRevealOrigin()) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.tab_search), tint = SoundColors.Text, modifier = Modifier.size(27.dp))
                    }
                    HeaderActions(downloads, nav)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.Gutter, vertical = 8.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { t ->
                        FilterPill(stringResource(t.label), selected = t == tab, onClick = { tab = t })
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "mainTab",
                modifier = Modifier.fillMaxSize(),
            ) { t ->
                CompositionLocalProvider(LocalHomeEmbedded provides true) {
                    if (t == MainTab.HOME) HomeScreen() else LibraryScreen(embeddedFilter = t.filter)
                }
            }
        }
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
