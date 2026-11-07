package com.villamediana.sound.ui.nav

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.navigation.NavDestination
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
import com.villamediana.sound.R
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.ui.detail.AlbumScreen
import com.villamediana.sound.ui.detail.ArtistScreen
import com.villamediana.sound.ui.detail.GenreScreen
import com.villamediana.sound.ui.detail.LikedScreen
import com.villamediana.sound.ui.detail.MixScreen
import com.villamediana.sound.ui.detail.PlaylistScreen
import com.villamediana.sound.ui.detail.SongsScreen
import com.villamediana.sound.ui.downloads.DownloadsScreen
import com.villamediana.sound.ui.equalizer.EqualizerScreen
import com.villamediana.sound.ui.video.VideoScreen
import com.villamediana.sound.ui.home.HomeScreen
import com.villamediana.sound.ui.library.LibraryScreen
import com.villamediana.sound.ui.player.PlayerLayer
import com.villamediana.sound.ui.search.RemoteCollectionScreen
import com.villamediana.sound.ui.search.SearchScreen
import com.villamediana.sound.ui.settings.SettingsScreen
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.LocalBottomInset
import com.villamediana.sound.ui.theme.Motion
import com.villamediana.sound.ui.theme.SoundColors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App shell: NavHost, the floating bottom bar and the player layer (mini player that expands
 * into the full-screen player) on top of everything.
 */
@Composable
fun SoundRoot(incoming: MutableStateFlow<Inbound?>) {
    val controller = rememberNavController()
    val navigator = remember(controller) { Navigator(controller) }
    val player = SoundApplication.graph.player
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
    // The mini player sits right on top of the system navigation and its color runs to the edge.
    val bottomBarTotal = navBarInset

    // Full pages (video, settings, equalizer) and the genre intro: no bottom bar, no mini player.
    val entry by controller.currentBackStackEntryAsState()
    val dest = entry?.destination
    val fullPage = dest != null && (dest.hasRoute(VideoRoute::class) || dest.hasRoute(SettingsRoute::class) || dest.hasRoute(EqualizerRoute::class) || dest.hasRoute(ThemeRoute::class))
    val chromeHidden = fullPage || Chrome.hideMiniPlayer
    val miniPlayerSpace by animateDpAsState(if (playback.hasMedia && !chromeHidden) Dimens.MiniPlayerHeight + 8.dp else 0.dp, label = "mini")
    // No bottom bar: the mini player sits at the bottom, just above the system navigation.
    val barSpace = navBarInset

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalBottomInset provides barSpace + miniPlayerSpace + 16.dp,
    ) {
        Box(Modifier.fillMaxSize().background(SoundColors.Ink)) {
            NavHost(
                navController = controller,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize(),
                // Pages slide in full width and slide out on back (arrow or gesture: predictive back is
                // off in the manifest, so both play this same animation), the page below moving a
                // little behind; tab switches just cross-fade.
                enterTransition = {
                    if (initialState.destination.isTab() && targetState.destination.isTab()) fadeIn(tween(220))
                    else slideInHorizontally(tween(PAGE_MS, easing = Motion.Emphasized)) { it }
                },
                exitTransition = {
                    if (initialState.destination.isTab() && targetState.destination.isTab()) fadeOut(tween(160))
                    else slideOutHorizontally(tween(PAGE_MS, easing = Motion.Emphasized)) { -it / 4 }
                },
                popEnterTransition = {
                    if (initialState.destination.isTab() && targetState.destination.isTab()) fadeIn(tween(220))
                    else slideInHorizontally(tween(PAGE_MS, easing = Motion.Emphasized)) { -it / 4 }
                },
                popExitTransition = {
                    if (initialState.destination.isTab() && targetState.destination.isTab()) fadeOut(tween(160))
                    else slideOutHorizontally(tween(PAGE_MS, easing = Motion.Emphasized)) { it }
                },
            ) {
                composable<HomeRoute> { Page { com.villamediana.sound.ui.library.MainScreen() } }
                composable<SearchRoute> { Page { SearchScreen() } }
                composable<LibraryRoute> { Page { LibraryScreen() } }
                composable<AlbumRoute> { Page { AlbumScreen(it.toRoute()) } }
                composable<ArtistRoute> { Page { ArtistScreen(it.toRoute()) } }
                composable<PlaylistRoute> { Page { PlaylistScreen(it.toRoute()) } }
                composable<GenreRoute> { Page { GenreScreen(it.toRoute()) } }
                composable<MixRoute> { Page { MixScreen(it.toRoute()) } }
                composable<LikedRoute> { Page { LikedScreen() } }
                composable<SongsRoute> { Page { SongsScreen() } }
                composable<RemoteCollectionRoute> { Page { RemoteCollectionScreen(it.toRoute()) } }
                composable<DownloadsRoute> { Page { DownloadsScreen() } }
                composable<SettingsRoute> { Page { SettingsScreen() } }
                composable<EqualizerRoute> { Page { EqualizerScreen() } }
                composable<ThemeRoute> { Page { com.villamediana.sound.ui.settings.ThemeScreen() } }
                composable<VideoRoute> { Page { VideoScreen(it.toRoute()) } }
            }

            AnimatedVisibility(
                visible = !chromeHidden,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(160)),
            ) {
                PlayerLayer(
                    bottomBarHeight = bottomBarTotal,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val PAGE_MS = 340

private fun NavDestination.isTab(): Boolean =
    hasRoute(HomeRoute::class) || hasRoute(LibraryRoute::class)

/** Every page is opaque, so a page sliding over another never shows both at once. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(SoundColors.Ink)) { content() }
}
