package com.villamediana.surco.ui.library

import com.villamediana.surco.data.db.PlaylistTrack
import com.villamediana.surco.data.db.Track

/**
 * Listening activity of a collection (album, artist, genre or playlist), aggregated from its songs:
 * when one of them was last played, how many times they were played and when the collection
 * arrived ([addedAt]) or last changed ([changedAt], playlists only).
 */
data class CollectionActivity(
    val lastPlayedAt: Long? = null,
    val plays: Int = 0,
    val addedAt: Long = 0,
    val changedAt: Long = addedAt,
) {
    /** "Recentes": the latest of played / added / changed. */
    val recency: Long get() = maxOf(lastPlayedAt ?: 0L, changedAt, addedAt)
}

/**
 * Per-collection activity computed in one pass over the library. Keys follow the repository's
 * own grouping: albums by [com.villamediana.surco.data.db.AlbumKey.normalized], artists and genres
 * by trimmed lowercase name, playlists by id.
 */
class LibraryIndex(
    val albums: Map<String, CollectionActivity>,
    val artists: Map<String, CollectionActivity>,
    val genres: Map<String, CollectionActivity>,
    /** Built from the playlist rows; [CollectionActivity.addedAt]/[CollectionActivity.changedAt] are filled by the caller. */
    val playlistSongs: Map<Long, CollectionActivity>,
) {
    fun album(normalizedKey: String): CollectionActivity = albums[normalizedKey] ?: CollectionActivity()
    fun artist(name: String): CollectionActivity = artists[name.trim().lowercase()] ?: CollectionActivity()
    fun genre(name: String): CollectionActivity = genres[name.trim().lowercase()] ?: CollectionActivity()
    fun playlist(id: Long, createdAt: Long, updatedAt: Long): CollectionActivity =
        (playlistSongs[id] ?: CollectionActivity()).copy(addedAt = createdAt, changedAt = updatedAt)

    companion object {
        val EMPTY = LibraryIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun build(tracks: List<Track>, playlistRows: List<PlaylistTrack>): LibraryIndex {
            val albums = HashMap<String, Acc>()
            val artists = HashMap<String, Acc>()
            val genres = HashMap<String, Acc>()
            val byId = HashMap<Long, Track>(tracks.size * 2)
            for (t in tracks) {
                byId[t.id] = t
                t.albumKey?.let { albums.getOrPut(it.normalized) { Acc() }.add(t) }
                artists.getOrPut(t.artist.trim().lowercase()) { Acc() }.add(t)
                t.genre?.takeIf { it.isNotBlank() }?.let { genres.getOrPut(it.trim().lowercase()) { Acc() }.add(t) }
            }
            val playlists = HashMap<Long, Acc>()
            for (row in playlistRows) {
                val t = byId[row.trackId] ?: continue
                playlists.getOrPut(row.playlistId) { Acc() }.add(t)
            }
            return LibraryIndex(
                albums = albums.mapValues { it.value.toActivity() },
                artists = artists.mapValues { it.value.toActivity() },
                genres = genres.mapValues { it.value.toActivity() },
                playlistSongs = playlists.mapValues { it.value.toActivity() },
            )
        }
    }

    private class Acc {
        var lastPlayed: Long? = null
        var plays = 0
        var added = 0L
        fun add(t: Track) {
            t.lastPlayedAt?.let { lp -> lastPlayed = maxOf(lastPlayed ?: lp, lp) }
            plays += t.playCount
            added = maxOf(added, t.addedAt)
        }
        fun toActivity() = CollectionActivity(lastPlayedAt = lastPlayed, plays = plays, addedAt = added)
    }
}
