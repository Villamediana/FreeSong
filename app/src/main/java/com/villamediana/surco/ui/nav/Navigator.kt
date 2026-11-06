package com.villamediana.surco.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.online.RemoteCollection

/** Thin wrapper over NavController so screens don't depend on navigation details. */
@Stable
class Navigator(val controller: NavHostController) {
    /** Set by the player layer; lets any screen collapse the full-screen player before navigating. */
    var collapsePlayer: () -> Unit = {}

    private fun go(route: Any) {
        collapsePlayer()
        controller.navigate(route) { launchSingleTop = true }
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

    fun back() {
        controller.popBackStack()
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

@Composable
fun navigator(): Navigator = LocalNavigator.current
