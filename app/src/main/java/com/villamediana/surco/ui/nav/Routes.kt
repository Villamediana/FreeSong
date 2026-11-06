package com.villamediana.surco.ui.nav

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
