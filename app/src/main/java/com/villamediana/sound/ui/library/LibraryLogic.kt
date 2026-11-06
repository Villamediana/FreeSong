package com.villamediana.sound.ui.library

import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.data.model.ArtistSummary
import com.villamediana.sound.data.model.GenreSummary
import com.villamediana.sound.data.model.PinnedItem
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.data.sortKey

/** Library filter pills. `null` (no pill selected) = playlists, albums and artists together. */
enum class LibFilter { PLAYLISTS, ALBUMS, ARTISTS, SONGS, GENRES }

enum class LibSort { RECENT, ADDED, ALPHA, ARTIST, PLAYS }

enum class LibViewMode { LIST, GRID }

/** Sort options that make sense for a filter ("Artista" only where items have one). */
fun sortsFor(filter: LibFilter?): List<LibSort> = when (filter) {
    LibFilter.ALBUMS, LibFilter.SONGS -> listOf(LibSort.RECENT, LibSort.ADDED, LibSort.ALPHA, LibSort.ARTIST, LibSort.PLAYS)
    else -> listOf(LibSort.RECENT, LibSort.ADDED, LibSort.ALPHA, LibSort.PLAYS)
}

/** The remembered sort, or A–Z when the remembered one doesn't apply to this filter. */
fun effectiveSort(sort: LibSort, filter: LibFilter?): LibSort = if (sort in sortsFor(filter)) sort else LibSort.ALPHA

/** One entry of the library list/grid. */
sealed interface LibItem {
    val key: String
    val title: String
    /** Used by the "Artista" sort; empty for things without an artist. */
    val artist: String
    val activity: CollectionActivity
    val pinned: Boolean

    data class PlaylistItem(val summary: PlaylistSummary, override val activity: CollectionActivity) : LibItem {
        override val key get() = playlistKey(summary.id)
        override val title get() = summary.name
        override val artist get() = ""
        override val pinned get() = summary.pinned
    }

    data class AlbumItem(val summary: AlbumSummary, override val activity: CollectionActivity) : LibItem {
        override val key get() = albumKey(summary.key.normalized)
        override val title get() = summary.title
        override val artist get() = summary.artist
        override val pinned get() = summary.pinned
    }

    data class ArtistItem(val summary: ArtistSummary, override val activity: CollectionActivity) : LibItem {
        override val key get() = "r:" + summary.name.trim().lowercase()
        override val title get() = summary.name
        override val artist get() = summary.name
        override val pinned get() = false
    }

    data class GenreItem(val summary: GenreSummary, override val activity: CollectionActivity) : LibItem {
        override val key get() = "g:" + summary.name.trim().lowercase()
        override val title get() = summary.name
        override val artist get() = ""
        override val pinned get() = false
    }

    data class SongItem(val track: Track) : LibItem {
        override val key get() = "t:${track.id}"
        override val title get() = track.title
        override val artist get() = track.artist
        override val activity get() = CollectionActivity(track.lastPlayedAt, track.playCount, track.addedAt)
        override val pinned get() = false
    }

    companion object {
        fun playlistKey(id: Long) = "p:$id"
        fun albumKey(normalized: String) = "a:$normalized"
    }
}

fun sortItems(items: List<LibItem>, sort: LibSort): List<LibItem> {
    val byTitle = compareBy<LibItem> { it.title.sortKey() }
    val cmp = when (sort) {
        LibSort.RECENT -> compareByDescending<LibItem> { it.activity.recency }.then(byTitle)
        LibSort.ADDED -> compareByDescending<LibItem> { it.activity.addedAt }.then(byTitle)
        LibSort.ALPHA -> byTitle
        LibSort.ARTIST -> compareBy<LibItem> { it.artist.sortKey() }.then(byTitle)
        LibSort.PLAYS -> compareByDescending<LibItem> { it.activity.plays }
            .thenByDescending { it.activity.recency }
            .then(byTitle)
    }
    return items.sortedWith(cmp)
}

/** Everything the library list is built from. */
data class LibrarySource(
    val tracks: List<Track>,
    val albums: List<AlbumSummary>,
    val artists: List<ArtistSummary>,
    val genres: List<GenreSummary>,
    val playlists: List<PlaylistSummary>,
    val pinned: List<PinnedItem>,
    val index: LibraryIndex,
)

/** Pinned first (in pin order, only the kinds the filter shows), then the rest sorted. */
data class LibrarySections(val pinned: List<LibItem>, val items: List<LibItem>)

fun buildSections(src: LibrarySource, filter: LibFilter?, sort: LibSort): LibrarySections {
    fun playlist(p: PlaylistSummary) = LibItem.PlaylistItem(p, src.index.playlist(p.id, p.playlist.createdAt, p.playlist.updatedAt))
    fun album(a: AlbumSummary) = LibItem.AlbumItem(a, src.index.album(a.key.normalized))

    val showsPlaylists = filter == null || filter == LibFilter.PLAYLISTS
    val showsAlbums = filter == null || filter == LibFilter.ALBUMS

    val pinned: List<LibItem> = src.pinned.mapNotNull { p ->
        when (p) {
            is PinnedItem.PinnedPlaylist -> if (showsPlaylists) playlist(p.summary) else null
            is PinnedItem.PinnedAlbum -> if (showsAlbums) album(p.summary) else null
        }
    }
    val pinnedKeys = pinned.mapTo(HashSet()) { it.key }

    val all: List<LibItem> = buildList {
        if (showsPlaylists) src.playlists.forEach { add(playlist(it)) }
        if (showsAlbums) src.albums.forEach { add(album(it)) }
        if (filter == null || filter == LibFilter.ARTISTS) src.artists.forEach { add(LibItem.ArtistItem(it, src.index.artist(it.name))) }
        if (filter == LibFilter.GENRES) src.genres.forEach { add(LibItem.GenreItem(it, src.index.genre(it.name))) }
        if (filter == LibFilter.SONGS) src.tracks.forEach { add(LibItem.SongItem(it)) }
    }
    return LibrarySections(pinned, sortItems(all.filter { it.key !in pinnedKeys }, effectiveSort(sort, filter)))
}

/** The "Downloads" row at the top of the library. */
data class DownloadsRowState(val active: Int, val failed: Int, val done: Int, val progress: Float?)

/** null when there is nothing in the downloads list. */
fun downloadsRow(tasks: List<DownloadTask>): DownloadsRowState? {
    if (tasks.isEmpty()) return null
    val active = tasks.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
    return DownloadsRowState(
        active = active.size,
        failed = tasks.count { it.state == DownloadState.FAILED },
        done = tasks.count { it.state == DownloadState.DONE },
        progress = if (active.isEmpty()) null else active.sumOf { it.progress.toDouble() }.toFloat() / active.size,
    )
}

/** Curtidas and the Downloads row live at the top of these views. */
fun showsLiked(filter: LibFilter?): Boolean = filter == null || filter == LibFilter.PLAYLISTS
fun showsDownloads(filter: LibFilter?): Boolean = filter == null || filter == LibFilter.PLAYLISTS || filter == LibFilter.SONGS
