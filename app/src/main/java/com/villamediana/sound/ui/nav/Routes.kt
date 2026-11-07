package com.villamediana.sound.ui.nav

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data object LibraryRoute

@Serializable data class AlbumRoute(val artist: String, val title: String)
@Serializable data class ArtistRoute(val name: String)
@Serializable data class PlaylistRoute(val id: Long)
@Serializable data class GenreRoute(val name: String)
@Serializable data class MixRoute(val id: String)
@Serializable data object LikedRoute
@Serializable data object SongsRoute

/** Online album/playlist/artist page before downloading. kind = CollectionKind.name */
@Serializable data class RemoteCollectionRoute(val url: String, val kind: String, val title: String, val author: String? = null, val thumbnailUrl: String? = null)

@Serializable data object DownloadsRoute
@Serializable data object SettingsRoute

/** Full-screen music video (online). */
@Serializable data class VideoRoute(val videoId: String, val title: String? = null)
@Serializable data object EqualizerRoute
@Serializable data object ThemeRoute

/** Pages that take the whole screen: no mini player over them, the player layer is hidden. */
fun isFullPage(route: Any): Boolean = route is VideoRoute || route == SettingsRoute || route == EqualizerRoute || route == ThemeRoute

fun NavDestination.isFullPage(): Boolean =
    hasRoute(VideoRoute::class) || hasRoute(SettingsRoute::class) || hasRoute(EqualizerRoute::class) || hasRoute(ThemeRoute::class)
