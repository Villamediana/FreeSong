package com.villamediana.surco.ui.home

import com.villamediana.surco.data.db.PlayEvent
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.AlbumSummary
import com.villamediana.surco.data.model.ArtistSummary
import com.villamediana.surco.data.model.PinnedItem
import com.villamediana.surco.data.model.PlaylistSummary
import com.villamediana.surco.data.sortKey
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.playback.PlaybackState
import com.villamediana.surco.smart.Taste
import com.villamediana.surco.ui.library.LibItem
import com.villamediana.surco.ui.library.LibraryIndex

enum class Greeting { MORNING, AFTERNOON, NIGHT }

/** Bom dia 5:00–11:59 · Boa tarde 12:00–17:59 · Boa noite 18:00–4:59. */
fun greetingFor(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting.MORNING
    in 12..17 -> Greeting.AFTERNOON
    else -> Greeting.NIGHT
}

/** A compact tile of the quick access grid at the top of Home. */
sealed interface QuickTile {
    val key: String

    data class Liked(val count: Int) : QuickTile {
        override val key get() = "liked"
    }

    data class PlaylistTile(val summary: PlaylistSummary) : QuickTile {
        override val key get() = LibItem.playlistKey(summary.id)
    }

    data class AlbumTile(val summary: AlbumSummary) : QuickTile {
        override val key get() = LibItem.albumKey(summary.key.normalized)
    }
}

/**
 * Curtidas first, then pinned playlists/albums (pin order), then the most recent playlists/albums
 * (last played, added or changed — whichever is latest). Up to [limit] tiles, no repeats.
 */
fun buildQuickAccess(
    likedCount: Int,
    pinned: List<PinnedItem>,
    albums: List<AlbumSummary>,
    playlists: List<PlaylistSummary>,
    index: LibraryIndex,
    limit: Int = 6,
): List<QuickTile> {
    val out = ArrayList<QuickTile>(limit)
    val seen = HashSet<String>()
    fun offer(tile: QuickTile) {
        if (out.size < limit && seen.add(tile.key)) out += tile
    }
    offer(QuickTile.Liked(likedCount))
    pinned.forEach {
        when (it) {
            is PinnedItem.PinnedPlaylist -> offer(QuickTile.PlaylistTile(it.summary))
            is PinnedItem.PinnedAlbum -> offer(QuickTile.AlbumTile(it.summary))
        }
    }
    if (out.size < limit) {
        val recents = playlists.map { p -> QuickTile.PlaylistTile(p) to index.playlist(p.id, p.playlist.createdAt, p.playlist.updatedAt).recency } +
            albums.map { a -> QuickTile.AlbumTile(a) to maxOf(a.lastPlayedAt ?: 0L, a.addedAt) }
        recents.sortedByDescending { it.second }.forEach { offer(it.first) }
    }
    return out
}

/** An entry of the "Tocadas recentemente" / "Adicionadas recentemente" carousels. */
sealed interface HomeItem {
    val key: String

    data class AlbumItem(val summary: AlbumSummary) : HomeItem {
        override val key get() = LibItem.albumKey(summary.key.normalized)
    }

    data class SongItem(val track: Track) : HomeItem {
        override val key get() = "t:${track.id}"
    }
}

/** Songs in order, each one shown as its album when it has one (once per album), else as itself. */
fun collapseToAlbums(tracks: List<Track>, albumsByKey: Map<String, AlbumSummary>, limit: Int): List<HomeItem> {
    val out = ArrayList<HomeItem>(limit)
    val seen = HashSet<String>()
    for (t in tracks) {
        if (out.size >= limit) break
        val album = t.albumKey?.let { albumsByKey[it.normalized] }
        val item = if (album != null) HomeItem.AlbumItem(album) else HomeItem.SongItem(t)
        if (seen.add(item.key)) out += item
    }
    return out
}

fun recentlyPlayedItems(tracks: List<Track>, albumsByKey: Map<String, AlbumSummary>, limit: Int = 12): List<HomeItem> =
    collapseToAlbums(tracks.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }, albumsByKey, limit)

fun recentlyAddedItems(tracks: List<Track>, albumsByKey: Map<String, AlbumSummary>, limit: Int = 12): List<HomeItem> =
    collapseToAlbums(tracks.sortedByDescending { it.addedAt }, albumsByKey, limit)

/** "Seus artistas": the ones you play the most, then the ones with more songs. */
fun topArtists(artists: List<ArtistSummary>, index: LibraryIndex, limit: Int = 12): List<ArtistSummary> =
    artists.sortedWith(
        compareByDescending<ArtistSummary> { index.artist(it.name).plays }
            .thenByDescending { it.trackCount }
            .thenBy { it.name.sortKey() },
    ).take(limit)

/** Covers for the smart shuffle card: favourites first. */
fun heroCovers(tracks: List<Track>, count: Int = 3): List<String> =
    tracks.sortedWith(compareByDescending<Track> { it.liked }.thenByDescending { it.playCount }.thenByDescending { it.addedAt })
        .mapNotNull { it.coverPath }
        .distinct()
        .take(count)

const val TASTE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
const val JUST_PLAYED_WINDOW_MS = 3L * 60 * 60 * 1000

/** Taste for the mixes: plays of the last 30 days + what was played in the last few hours. */
fun buildTaste(recentPlays: Map<Long, Int>, events: List<PlayEvent>, now: Long): Taste =
    Taste(
        recentPlays = recentPlays,
        justPlayed = events.filter { now - it.playedAt in 0..JUST_PLAYED_WINDOW_MS }.mapTo(HashSet()) { it.trackId },
        now = now,
    )

/** 1990 → 90 ("Anos 90"); 2000 and later stay whole ("Anos 2010"). */
fun decadeShort(decade: Int): Int? = if (decade in 1900..1999) decade % 100 else null

/** Is the player currently playing from this collection? (drives the little live bars on tiles) */
fun PlaybackState.isFromPlaylist(id: Long): Boolean = source?.type == PlaySource.Type.PLAYLIST && source.id == id.toString()
fun PlaybackState.isFromAlbum(normalizedKey: String): Boolean = current?.albumKey?.normalized == normalizedKey
fun PlaybackState.isFromLiked(): Boolean = source?.type == PlaySource.Type.LIKED
fun PlaybackState.isFromMix(id: String): Boolean = source?.type == PlaySource.Type.MIX && source.id == id
fun PlaybackState.isSmartLibrary(): Boolean = source?.type == PlaySource.Type.SMART_SHUFFLE
