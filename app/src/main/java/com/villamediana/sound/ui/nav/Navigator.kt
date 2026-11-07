package com.villamediana.sound.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.online.RemoteCollection

/** Thin wrapper over NavController so screens don't depend on navigation details. */
@Stable
class Navigator(val controller: NavHostController) {
    /** Set by the player layer while it is on screen. */
    var player: PlayerHooks = PlayerHooks()

    /**
     * Entries that were under the open full player when a page was opened from it: back to one of
     * them opens the player again, so back returns to where the user was.
     */
    private val reopenPlayerOn = mutableSetOf<String>()

    /**
     * Opens [route] unless that very page (same arguments) is already on top. Not launchSingleTop:
     * it only compares destinations, so album B opened from album A would reuse A's entry (and its
     * ViewModel) and keep showing A.
     *
     * From the open player: a full page (equalizer, video…) goes over it and the player is still
     * open when the page is left; any other page collapses it and back opens it again.
     */
    private fun go(route: Any) {
        if (isOnTop(route)) {
            player.collapse()
            return
        }
        val fromPlayer = player.isOpen()
        if (fromPlayer && isFullPage(route)) {
            player.closeSheets()
        } else {
            if (fromPlayer) controller.currentBackStackEntry?.let { reopenPlayerOn += it.id }
            player.collapse()
        }
        controller.navigate(route)
    }

    /** [entryId] is on top again (back): reopens the player if a page was opened from it there. */
    fun onEntryShown(entryId: String) {
        if (reopenPlayerOn.remove(entryId)) player.expand()
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
    fun openTheme() = go(ThemeRoute)
    fun openRecap() = go(RecapRoute)
    fun openVideo(videoId: String, title: String?) = go(VideoRoute(videoId, title))

    /** Bottom-bar tab switch: single top, state saved/restored per tab. */
    fun switchTab(route: Any) {
        reopenPlayerOn.clear()
        player.collapse()
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * The Search screen itself, on top of its tab: what [LinkInbox] holds (a shared link, a voice
     * query) is taken there. Restoring the tab alone could bring back a page opened from Search
     * earlier (an album, Downloads) with the Search screen hidden under it, and the link would
     * wait unseen.
     */
    fun openSearchScreen() {
        reopenPlayerOn.clear()
        player.collapse()
        when {
            controller.currentBackStackEntry?.destination?.hasRoute(SearchRoute::class) == true -> Unit
            isOnStack<SearchRoute>() -> controller.popBackStack<SearchRoute>(inclusive = false)
            else -> {
                SearchReveal.armed = true
                controller.navigate(SearchRoute) { launchSingleTop = true }
            }
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

/** What the player layer lets navigation do with it. */
class PlayerHooks(
    /** The full player is open (or opening). */
    val isOpen: () -> Boolean = { false },
    /** Back to the mini player, sheets and lyrics closed. */
    val collapse: () -> Unit = {},
    /** Closes the player's sheets and leaves it open. */
    val closeSheets: () -> Unit = {},
    val expand: () -> Unit = {},
)

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

@Composable
fun navigator(): Navigator = LocalNavigator.current

/** Tells [Navigator] which entry is on top (the shell calls it once, next to its NavHost). */
@Composable
fun Navigator.FollowBackStack() {
    val entry by controller.currentBackStackEntryAsState()
    LaunchedEffect(entry?.id) { entry?.let { onEntryShown(it.id) } }
}
