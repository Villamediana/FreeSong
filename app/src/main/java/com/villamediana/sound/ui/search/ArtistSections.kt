package com.villamediana.sound.ui.search

import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack

/**
 * An artist's page laid out to find songs: a few popular ones (all of them on demand), then the
 * albums from the newest, the singles and EPs, and their playlists. Play still plays everything.
 */
data class ArtistSections(
    val popular: List<RemoteTrack>,
    /** More songs than [popular] shows (the "Ver todas" button). */
    val moreSongs: Int,
    val albums: List<RemoteCollection>,
    val singles: List<RemoteCollection>,
    val playlists: List<RemoteCollection>,
) {
    companion object {
        const val POPULAR = 5

        fun of(detail: CollectionDetail, allSongs: Boolean): ArtistSections {
            val tracks = detail.tracks
            val releases = detail.related.filter { it.kind == CollectionKind.ALBUM }.distinctBy { it.url }
            fun List<RemoteCollection>.newestFirst() = withIndex()
                .sortedWith(compareByDescending<IndexedValue<RemoteCollection>> { it.value.year ?: 0 }.thenBy { it.index })
                .map { it.value }
            return ArtistSections(
                popular = if (allSongs) tracks else tracks.take(POPULAR),
                moreSongs = (tracks.size - POPULAR).coerceAtLeast(0),
                albums = releases.filterNot { it.single }.newestFirst(),
                singles = releases.filter { it.single }.newestFirst(),
                playlists = detail.related.filter { it.kind == CollectionKind.PLAYLIST }.distinctBy { it.url },
            )
        }
    }
}
