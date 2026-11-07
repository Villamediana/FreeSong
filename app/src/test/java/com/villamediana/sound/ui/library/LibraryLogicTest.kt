package com.villamediana.sound.ui.library

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.Playlist
import com.villamediana.sound.data.db.PlaylistTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.ArtistSummary
import com.villamediana.sound.data.model.GenreSummary
import com.villamediana.sound.data.model.PinnedItem
import com.villamediana.sound.data.model.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    private fun track(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String? = null,
        genre: String? = null,
        addedAt: Long = id,
        lastPlayedAt: Long? = null,
        playCount: Int = 0,
    ) = Track(
        id = id, title = title, artist = artist, album = album, genre = genre, filePath = "/x/$id",
        addedAt = addedAt, lastPlayedAt = lastPlayedAt, playCount = playCount,
    )

    private fun playlist(id: Long, name: String, created: Long = 0, updated: Long = created, pinned: Boolean = false) =
        PlaylistSummary(Playlist(id = id, name = name, pinned = pinned, createdAt = created, updatedAt = updated), 0, 0, emptyList())

    private val songs = listOf(
        track(1, "Zebra", artist = "Beta", addedAt = 10, lastPlayedAt = 50, playCount = 3),
        track(2, "Água", artist = "alfa", addedAt = 30, playCount = 7),
        track(3, "banana", artist = "Ômega", addedAt = 20, lastPlayedAt = 100, playCount = 3),
    )

    private fun titles(items: List<LibItem>) = items.map { it.title }

    @Test
    fun sortsSongsEveryWay() {
        val items = songs.map { LibItem.SongItem(it) }
        assertEquals(listOf("banana", "Zebra", "Água"), titles(sortItems(items, LibSort.RECENT)))
        assertEquals(listOf("Água", "banana", "Zebra"), titles(sortItems(items, LibSort.ADDED)))
        assertEquals(listOf("Água", "banana", "Zebra"), titles(sortItems(items, LibSort.ALPHA)))
        assertEquals(listOf("Água", "Zebra", "banana"), titles(sortItems(items, LibSort.ARTIST)))
        // 7 plays first; tie at 3 broken by recency (banana played later).
        assertEquals(listOf("Água", "banana", "Zebra"), titles(sortItems(items, LibSort.PLAYS)))
    }

    @Test
    fun artistSortOnlyWhereItMakesSense() {
        assertTrue(LibSort.ARTIST in sortsFor(LibFilter.ALBUMS))
        assertTrue(LibSort.ARTIST in sortsFor(LibFilter.SONGS))
        assertFalse(LibSort.ARTIST in sortsFor(LibFilter.PLAYLISTS))
        assertFalse(LibSort.ARTIST in sortsFor(null))
        assertEquals(LibSort.ALPHA, effectiveSort(LibSort.ARTIST, LibFilter.GENRES))
        assertEquals(LibSort.PLAYS, effectiveSort(LibSort.PLAYS, LibFilter.GENRES))
    }

    @Test
    fun indexAggregatesPerCollection() {
        val tracks = listOf(
            track(1, "a", artist = "X", album = "One", genre = "Rock", addedAt = 5, lastPlayedAt = 40, playCount = 2),
            track(2, "b", artist = " x ", album = "one", genre = "rock ", addedAt = 9, lastPlayedAt = 20, playCount = 1),
            track(3, "c", artist = "Y", genre = "Pop", addedAt = 7),
        )
        val rows = listOf(
            PlaylistTrack(playlistId = 9, trackId = 3, position = 0),
            PlaylistTrack(playlistId = 9, trackId = 1, position = 1),
            PlaylistTrack(playlistId = 9, trackId = 404, position = 2),
        )
        val index = LibraryIndex.build(tracks, rows)

        val album = index.album(tracks[0].albumKey!!.normalized)
        assertEquals(40L, album.lastPlayedAt)
        assertEquals(3, album.plays)
        assertEquals(9L, album.addedAt)

        assertEquals(3, index.artist("X").plays)
        assertEquals(3, index.genre("ROCK").plays)
        assertNull(index.genre("Pop").lastPlayedAt)

        val p = index.playlist(9, createdAt = 1, updatedAt = 60)
        assertEquals(40L, p.lastPlayedAt)
        assertEquals(2, p.plays)
        assertEquals(1L, p.addedAt)
        assertEquals(60L, p.recency)
        assertEquals(CollectionActivity(addedAt = 3, changedAt = 4), index.playlist(77, 3, 4))
    }

    @Test
    fun pinnedComeFirstInPinOrderAndOnlyForTheirKind() {
        val tracks = listOf(
            track(1, "a", album = "Alpha", addedAt = 1),
            track(2, "b", album = "Beta", addedAt = 2),
            track(3, "c", artist = "Solo", addedAt = 3),
        )
        val albums = LibraryRepository.groupAlbums(tracks)
        val alpha = albums.first { it.title == "Alpha" }.copy(pinned = true)
        val beta = albums.first { it.title == "Beta" }
        val pinnedList = playlist(5, "Mine", created = 1, pinned = true)
        val otherList = playlist(6, "Other", created = 2)
        val src = LibrarySource(
            tracks = tracks,
            albums = listOf(alpha, beta),
            artists = listOf(ArtistSummary("Artist", null, 2, 2, null), ArtistSummary("Solo", null, 1, 0, null)),
            genres = listOf(GenreSummary("Rock", 1, 0, emptyList(), null)),
            playlists = listOf(pinnedList, otherList),
            pinned = listOf(PinnedItem.PinnedAlbum(alpha, 20), PinnedItem.PinnedPlaylist(pinnedList, 10)),
            index = LibraryIndex.build(tracks, emptyList()),
            // Músicas: only what was liked — a streamed like (never played here) counts from its like.
            liked = listOf(LikedSong(tracks[0], likedAt = 50), LikedSong(track(-9, "streamed", addedAt = 0), likedAt = 60)),
        )

        val all = buildSections(src, null, LibSort.ALPHA)
        assertEquals(listOf("Alpha", "Mine"), titles(all.pinned))
        assertEquals(listOf("Artist", "Beta", "Other", "Solo"), titles(all.items))

        val lists = buildSections(src, LibFilter.PLAYLISTS, LibSort.ALPHA)
        assertEquals(listOf("Mine"), titles(lists.pinned))
        assertEquals(listOf("Other"), titles(lists.items))

        val albumsOnly = buildSections(src, LibFilter.ALBUMS, LibSort.ADDED)
        assertEquals(listOf("Alpha"), titles(albumsOnly.pinned))
        assertEquals(listOf("Beta"), titles(albumsOnly.items))

        val genres = buildSections(src, LibFilter.GENRES, LibSort.RECENT)
        assertTrue(genres.pinned.isEmpty())
        assertEquals(listOf("Rock"), titles(genres.items))

        val songsView = buildSections(src, LibFilter.SONGS, LibSort.RECENT)
        assertEquals(listOf("streamed", "a"), titles(songsView.items))
        assertTrue(songsView.items.all { it is LibItem.SongItem })
        assertEquals(listOf("a", "streamed"), titles(buildSections(src, LibFilter.SONGS, LibSort.ALPHA).items))
    }

    @Test
    fun downloadsRowSummarizesTheQueue() {
        fun task(id: Long, state: DownloadState, progress: Float = 0f) =
            DownloadTask(id = id, videoId = "v$id", url = "u", title = "t", artist = "a", state = state, progress = progress)

        assertNull(downloadsRow(emptyList()))
        val row = downloadsRow(
            listOf(
                task(1, DownloadState.RUNNING, 0.5f),
                task(2, DownloadState.QUEUED, 0f),
                task(3, DownloadState.FAILED),
                task(4, DownloadState.DONE),
                task(5, DownloadState.DONE),
                task(6, DownloadState.CANCELED),
            ),
        )!!
        assertEquals(2, row.active)
        assertEquals(1, row.failed)
        assertEquals(2, row.done)
        assertEquals(0.25f, row.progress!!, 1e-6f)
        assertNull(downloadsRow(listOf(task(1, DownloadState.DONE)))!!.progress)
    }

    @Test
    fun likedAndDownloadsOnlyInTheWholeLibrary() {
        assertTrue(showsLiked(null))
        assertTrue(showsDownloads(null))
        LibFilter.entries.forEach { f ->
            assertFalse(showsLiked(f))
            assertFalse(showsDownloads(f))
        }
    }

    @Test
    fun emptyViewDependsOnTheFilter() {
        assertTrue(LibraryUiState(filter = null, libraryEmpty = true).isEmptyView)
        assertFalse(LibraryUiState(filter = null, libraryEmpty = false).isEmptyView)
        assertTrue(LibraryUiState(filter = LibFilter.GENRES, libraryEmpty = false).isEmptyView)
    }
}
