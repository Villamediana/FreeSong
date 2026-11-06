package com.villamediana.surco.ui.nav

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.villamediana.surco.R
import com.villamediana.surco.SurcoApplication
import com.villamediana.surco.ui.detail.AlbumScreen
import com.villamediana.surco.ui.detail.ArtistScreen
import com.villamediana.surco.ui.detail.GenreScreen
import com.villamediana.surco.ui.detail.LikedScreen
import com.villamediana.surco.ui.detail.MixScreen
import com.villamediana.surco.ui.detail.PlaylistScreen
import com.villamediana.surco.ui.detail.SongsScreen
import com.villamediana.surco.ui.downloads.DownloadsScreen
import com.villamediana.surco.ui.equalizer.EqualizerScreen
import com.villamediana.surco.ui.video.VideoScreen
import com.villamediana.surco.ui.home.HomeScreen
import com.villamediana.surco.ui.library.LibraryScreen
import com.villamediana.surco.ui.player.PlayerLayer
import com.villamediana.surco.ui.search.RemoteCollectionScreen
import com.villamediana.surco.ui.search.SearchScreen
import com.villamediana.surco.ui.settings.SettingsScreen
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.LocalBottomInset
import com.villamediana.surco.ui.theme.Motion
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App shell: NavHost, the floating bottom bar and the player layer (mini player that expands
 * into the full-screen player) on top of everything.
 */
@Composable
fun SurcoRoot(incoming: MutableStateFlow<Inbound?>) {
    val controller = rememberNavController()
    val navigator = remember(controller) { Navigator(controller) }
    val player = SurcoApplication.graph.player
    val playback by player.state.collectAsState()

    // Android 13+: downloads show progress notifications.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Shared YouTube links and voice requests the library can't play → the Search screen takes them.
    val inbound by incoming.collectAsState()
    LaunchedEffect(inbound) {
        val item = inbound ?: return@LaunchedEffect
        LinkInbox.post(item)
        incoming.value = null
        navigator.openSearchScreen()
    }

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarTotal = Dimens.BottomBarHeight + navBarInset
    val miniPlayerSpace by animateDpAsState(if (playback.hasMedia) Dimens.MiniPlayerHeight + 8.dp else 0.dp, label = "mini")

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalBottomInset provides bottomBarTotal + miniPlayerSpace + 16.dp,
    ) {
        Box(Modifier.fillMaxSize().background(SurcoColors.Ink)) {
            NavHost(
                navController = controller,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(Motion.enter()) + slideInHorizontally(Motion.enter()) { it / 12 } },
                exitTransition = { fadeOut(Motion.exit()) },
                popEnterTransition = { fadeIn(Motion.enter()) },
                popExitTransition = { fadeOut(Motion.exit()) + slideOutHorizontally(Motion.exit()) { it / 12 } },
            ) {
                composable<HomeRoute> { HomeScreen() }
                composable<SearchRoute> { SearchScreen() }
                composable<LibraryRoute> { LibraryScreen() }
                composable<AlbumRoute> { AlbumScreen(it.toRoute()) }
                composable<ArtistRoute> { ArtistScreen(it.toRoute()) }
                composable<PlaylistRoute> { PlaylistScreen(it.toRoute()) }
                composable<GenreRoute> { GenreScreen(it.toRoute()) }
                composable<MixRoute> { MixScreen(it.toRoute()) }
                composable<LikedRoute> { LikedScreen() }
                composable<SongsRoute> { SongsScreen() }
                composable<RemoteCollectionRoute> { RemoteCollectionScreen(it.toRoute()) }
                composable<DownloadsRoute> { DownloadsScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<EqualizerRoute> { EqualizerScreen() }
                composable<VideoRoute> { VideoScreen(it.toRoute()) }
            }

            // The video screen is immersive: no bottom bar, no mini player over it.
            val entry by controller.currentBackStackEntryAsState()
            val immersive = entry?.destination?.hasRoute(VideoRoute::class) == true
            if (!immersive) {
                BottomBar(
                    navigator = navigator,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                PlayerLayer(
                    bottomBarHeight = bottomBarTotal,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private data class Tab(val route: Any, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
internal fun BottomBar(navigator: Navigator, modifier: Modifier = Modifier) {
    val tabs = listOf(
        Tab(HomeRoute, R.string.tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
        Tab(SearchRoute, R.string.tab_search, Icons.Outlined.Search, Icons.Rounded.Search),
        Tab(LibraryRoute, R.string.tab_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    )
    // The tab whose stack is showing (an album opened from Biblioteca keeps Biblioteca lit).
    val entry by navigator.controller.currentBackStackEntryAsState()
    val currentTab = remember(entry) { navigator.currentTab() }
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to SurcoColors.Ink.copy(alpha = 0f), 0.38f to SurcoColors.Ink.copy(alpha = 0.92f), 1f to SurcoColors.Ink)),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().height(Dimens.BottomBarHeight - 18.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val selected = tab.route == currentTab
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { navigator.switchTab(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (selected) tab.selectedIcon else tab.icon,
                        null,
                        tint = if (selected) SurcoColors.Text else SurcoColors.TextFaint,
                        modifier = Modifier.size(25.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) SurcoColors.Text else SurcoColors.TextFaint,
                    )
                }
            }
        }
        Spacer(Modifier.height(navBarInset))
    }
}
