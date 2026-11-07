package com.villamediana.sound.discover

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.CoverMatch
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack

/** Which online album is the one asked for (pure: tested without the network). */
object AlbumPick {
    /**
     * Among album search results: same name and credited to [artist] first, then a name that
     * starts the same (deluxe, remaster…) by that artist; without [sameArtist], then the same name
     * by anyone.
     */
    fun best(found: List<RemoteCollection>, album: String, artist: String, sameArtist: Boolean = false): RemoteCollection? {
        val want = CoverMatch.key(album)
        if (want.isEmpty()) return null
        val albums = found.filter { it.kind == CollectionKind.ALBUM }
        val by = CoverMatch.key(artist)
        fun credited(c: RemoteCollection): Boolean {
            if (by.isEmpty()) return true
            val a = CoverMatch.key(c.author.orEmpty())
            return a.isNotEmpty() && (a.contains(by) || by.contains(a))
        }
        fun exact(c: RemoteCollection) = CoverMatch.key(c.title) == want
        fun close(c: RemoteCollection): Boolean {
            val k = CoverMatch.key(c.title)
            return k.isNotEmpty() && (k.startsWith(want) || want.startsWith(k))
        }
        return albums.firstOrNull { exact(it) && credited(it) }
            ?: albums.firstOrNull { close(it) && credited(it) }
            ?: if (sameArtist) null else albums.firstOrNull(::exact) ?: albums.firstOrNull(::close)
    }
}

/**
 * A library album with the rest of its songs from the online album: the album's order, your copy
 * where you have the song (same video, or the same title), the others streamed. Songs of yours the
 * online album doesn't list stay, after it.
 */
object AlbumMerge {
    fun merge(local: List<Track>, remote: List<RemoteTrack>): List<Track> {
        if (remote.isEmpty()) return local
        val left = local.toMutableList()
        val out = ArrayList<Track>(remote.size + local.size)
        remote.distinctBy { it.videoId }.forEach { r ->
            val mine = left.firstOrNull { it.videoId != null && it.videoId == r.videoId }
                ?: left.firstOrNull { sameSong(it.title, r.title) }
            if (mine != null) {
                left.remove(mine)
                out += mine
            } else {
                out += r.asTransientTrack()
            }
        }
        return out + left
    }

    /** True when at least one of your songs is on the online album (it is the same album). */
    fun overlaps(local: List<Track>, remote: List<RemoteTrack>): Boolean =
        local.any { t -> remote.any { r -> (t.videoId != null && t.videoId == r.videoId) || sameSong(t.title, r.title) } }

    private fun sameSong(a: String, b: String): Boolean {
        val x = CoverMatch.key(a)
        return x.isNotEmpty() && x == CoverMatch.key(b)
    }
}
