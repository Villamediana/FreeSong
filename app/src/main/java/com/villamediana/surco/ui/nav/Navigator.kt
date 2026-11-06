package com.villamediana.surco.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.online.RemoteCollection

/** Thin wrapper over NavController so screens don't depend on navigation details. */
@Stable
class Navigator(val controller: NavHostController) {
    /** Set by the player layer; lets any screen collapse the full-screen player before navigating. */
    var collapsePlayer: () -> Unit = {}

    /**
     * Opens [route] unless that very page (same arguments) is already on top. Not launchSingleTop:
     * it only compares destinations, so album B opened from album A would reuse A's entry (and its
     * ViewModel) and keep showing A.
     */
    private fun go(route: Any) {
        collapsePlayer()
        if (!isOnTop(route)) controller.navigate(route)
    }

    private fun isOnTop(route: Any): Boolean {
        val top = controller.currentBackStackEntry ?: return false
        return top.destination.hasRoute(route::class) && runCatching { top.toRoute<Any>(route::class) == route }.getOrDefault(false)
    }

    fun openAlbum(key: AlbumKey) = go(AlbumRoute(key.artist, key.title))
    fun openArtist(name: String) = go(ArtistRoute(name))
    fun openPlaylist(id: Long) = go(PlaylistRoute(id))
    fun openGenre(name: String) = go(GenreRoute(name))
    fun openMix(id: String) = go(MixRoute(id))
    fun openLiked() = go(LikedRoute)
    fun openSongs() = go(SongsRoute)
    fun openRemote(c: RemoteCollection) = go(RemoteCollectionRoute(c.url, c.kind.name, c.title, c.author, c.thumbnailUrl))
    fun openDownloads() = go(DownloadsRoute)
    fun openSettings() = go(SettingsRoute)
    fun openEqualizer() = go(EqualizerRoute)
    fun openVideo(videoId: String, title: String?) = go(VideoRoute(videoId, title))

    /** Bottom-bar tab switch: single top, state saved/restored per tab. */
    fun switchTab(route: Any) {
        collapsePlayer()
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * The bottom-bar tab whose stack is on screen. switchTab keeps the stack as
     * [Home, <tab root>, pages…], so the tab root under the current page tells (Home when none).
     */
    fun currentTab(): Any = when {
        isOnStack<SearchRoute>() -> SearchRoute
        isOnStack<LibraryRoute>() -> LibraryRoute
        else -> HomeRoute
    }

    private inline fun <reified T : Any> isOnStack(): Boolean = runCatching { controller.getBackStackEntry<T>() }.isSuccess

    fun back() {
        controller.popBackStack()
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

@Composable
fun navigator(): Navigator = LocalNavigator.current
