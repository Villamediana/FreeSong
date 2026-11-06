package com.villamediana.surco.data.model

import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.data.db.Playlist
import com.villamediana.surco.data.db.PlaylistEntry
import com.villamediana.surco.data.db.Track

data class AlbumSummary(
    val key: AlbumKey,
    val coverPath: String?,
    val trackCount: Int,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val pinned: Boolean,
    val accentColor: Int?,
) {
    val title: String get() = key.title
    val artist: String get() = key.artist
}

data class AlbumDetail(val summary: AlbumSummary, val tracks: List<Track>)

data class ArtistSummary(
    val name: String,
    val coverPath: String?,
    val trackCount: Int,
    val albumCount: Int,
    val lastPlayedAt: Long?,
)

data class ArtistDetail(
    val summary: ArtistSummary,
    /** Most played first. */
    val topTracks: List<Track>,
    val albums: List<AlbumSummary>,
    /** Songs without album. */
    val singles: List<Track>,
    val allTracks: List<Track>,
)

data class GenreSummary(
    val name: String,
    val trackCount: Int,
    val durationMs: Long,
    /** Up to 4 distinct covers, most played first. */
    val covers: List<String>,
    val accentColor: Int?,
)

data class PlaylistSummary(
    val playlist: Playlist,
    val trackCount: Int,
    val durationMs: Long,
    /** Up to 4 distinct track covers in playlist order (for the mosaic). */
    val mosaic: List<String>,
) {
    val id: Long get() = playlist.id
    val name: String get() = playlist.name
    val coverPath: String? get() = playlist.coverPath
    val pinned: Boolean get() = playlist.pinned
}

data class PlaylistDetail(val summary: PlaylistSummary, val entries: List<PlaylistEntry>) {
    val tracks: List<Track> get() = entries.map { it.track }
}

data class LocalSearchResult(
    val query: String,
    val tracks: List<Track>,
    val albums: List<AlbumSummary>,
    val artists: List<ArtistSummary>,
    val playlists: List<PlaylistSummary>,
    val genres: List<GenreSummary>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && genres.isEmpty()
}

data class LibraryStats(val trackCount: Int, val durationMs: Long, val bytes: Long, val albumCount: Int, val artistCount: Int)

enum class TrackSort { RECENT, TITLE, ARTIST, ALBUM, MOST_PLAYED, DURATION }

/** Pinned things shown first in the library ("Fixados"). */
sealed interface PinnedItem {
    val pinnedAt: Long
    data class PinnedPlaylist(val summary: PlaylistSummary, override val pinnedAt: Long) : PinnedItem
    data class PinnedAlbum(val summary: AlbumSummary, override val pinnedAt: Long) : PinnedItem
}
