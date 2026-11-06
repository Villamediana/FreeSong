package com.villamediana.sound.ui.home

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Playlist
import com.villamediana.sound.data.db.PlaylistTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.ArtistSummary
import com.villamediana.sound.data.model.PinnedItem
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.ui.library.LibraryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private fun track(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist",
        album: String? = null,
        addedAt: Long = id,
        lastPlayedAt: Long? = null,
        playCount: Int = 0,
        liked: Boolean = false,
        cover: String? = null,
    ) = Track(
        id = id, title = title, artist = artist, album = album, filePath = "/x/$id.m4a",
        addedAt = addedAt, lastPlayedAt = lastPlayedAt, playCount = playCount, liked = liked, coverPath = cover,
    )

    private fun playlist(id: Long, name: String, created: Long, updated: Long, pinned: Boolean = false) =
        PlaylistSummary(Playlist(id = id, name = name, pinned = pinned, createdAt = created, updatedAt = updated), 0, 0, emptyList())

    // ------------------------------------------------------------------ greeting

    @Test
    fun greetingFollowsTheClock() {
        assertEquals(Greeting.NIGHT, greetingFor(0))
        assertEquals(Greeting.NIGHT, greetingFor(4))
        assertEquals(Greeting.MORNING, greetingFor(5))
        assertEquals(Greeting.MORNING, greetingFor(11))
        assertEquals(Greeting.AFTERNOON, greetingFor(12))
        assertEquals(Greeting.AFTERNOON, greetingFor(17))
        assertEquals(Greeting.NIGHT, greetingFor(18))
        assertEquals(Greeting.NIGHT, greetingFor(23))
    }

    // ------------------------------------------------------------------ quick access

    @Test
    fun quickAccessPutsLikedFirstThenPinnedThenMostRecent() {
        val tracks = listOf(
            track(1, album = "Old", addedAt = 100),
            track(2, album = "Played", addedAt = 50, lastPlayedAt = 900),
            track(3, album = "Pinned", addedAt = 10),
            track(4, album = "Fresh", addedAt = 500),
        )
        val albums = LibraryRepository.groupAlbums(tracks)
        val pinnedAlbum = albums.first { it.title == "Pinned" }
        val pinnedList = playlist(7, "Pinned list", created = 1, updated = 1, pinned = true)
        val recentList = playlist(8, "Recent list", created = 600, updated = 700)
        val pinned = listOf(PinnedItem.PinnedPlaylist(pinnedList, 2000), PinnedItem.PinnedAlbum(pinnedAlbum, 1000))

        val tiles = buildQuickAccess(
            likedCount = 3,
            pinned = pinned,
            albums = albums,
            playlists = listOf(pinnedList, recentList),
            index = LibraryIndex.build(tracks, emptyList()),
        )

        assertEquals(
            listOf("liked", "p:7", "a:" + pinnedAlbum.key.normalized, "a:artist\u0001played", "p:8", "a:artist\u0001fresh"),
            tiles.map { it.key },
        )
        assertEquals(3, (tiles.first() as QuickTile.Liked).count)
    }

    @Test
    fun quickAccessIsCappedAndNeverRepeats() {
        val tracks = (1L..10L).map { track(it, album = "Album $it", addedAt = it * 10) }
        val albums = LibraryRepository.groupAlbums(tracks)
        val pinned = listOf(PinnedItem.PinnedAlbum(albums.first { it.title == "Album 10" }, 5))
        val tiles = buildQuickAccess(0, pinned, albums, emptyList(), LibraryIndex.build(tracks, emptyList()))
        assertEquals(6, tiles.size)
        assertEquals(tiles.size, tiles.map { it.key }.distinct().size)
        assertEquals("a:artist\u0001album 10", tiles[1].key)
        assertEquals("a:artist\u0001album 9", tiles[2].key)
    }

    @Test
    fun playlistRecencyCountsItsSongsBeingPlayed() {
        val tracks = listOf(track(1, addedAt = 1, lastPlayedAt = 5_000))
        val old = playlist(1, "Old but played", created = 10, updated = 10)
        val newer = playlist(2, "Newer", created = 3_000, updated = 3_000)
        val index = LibraryIndex.build(tracks, listOf(PlaylistTrack(playlistId = 1, trackId = 1, position = 0)))
        val tiles = buildQuickAccess(0, emptyList(), emptyList(), listOf(newer, old), index)
        assertEquals(listOf("liked", "p:1", "p:2"), tiles.map { it.key })
    }

    // ------------------------------------------------------------------ carousels

    @Test
    fun recentlyPlayedCollapsesSongsIntoTheirAlbums() {
        val tracks = listOf(
            track(1, album = "A", lastPlayedAt = 300),
            track(2, album = "A", lastPlayedAt = 250),
            track(3, album = null, lastPlayedAt = 200),
            track(4, album = "B", lastPlayedAt = 100),
            track(5, album = "C", lastPlayedAt = null),
        )
        val byKey = LibraryRepository.groupAlbums(tracks).associateBy { it.key.normalized }
        val items = recentlyPlayedItems(tracks, byKey)
        assertEquals(listOf("a:artist\u0001a", "t:3", "a:artist\u0001b"), items.map { it.key })
        assertTrue(items[1] is HomeItem.SongItem)
    }

    @Test
    fun recentlyAddedIsNewestFirstAndLimited() {
        val tracks = (1L..30L).map { track(it, addedAt = it) }
        val items = recentlyAddedItems(tracks.shuffled(java.util.Random(4)), emptyMap(), limit = 5)
        assertEquals(listOf("t:30", "t:29", "t:28", "t:27", "t:26"), items.map { it.key })
    }

    @Test
    fun topArtistsPreferPlaysThenSize() {
        val tracks = listOf(
            track(1, artist = "Big", playCount = 0),
            track(2, artist = "Big", playCount = 0),
            track(3, artist = "Big", playCount = 0),
            track(4, artist = "Loved", playCount = 9),
            track(5, artist = "Small", playCount = 0),
        )
        val artists = listOf("Big", "Loved", "Small").map { name ->
            ArtistSummary(name, null, tracks.count { it.artist == name }, 0, null)
        }
        val top = topArtists(artists, LibraryIndex.build(tracks, emptyList()), limit = 2)
        assertEquals(listOf("Loved", "Big"), top.map { it.name })
    }

    @Test
    fun heroCoversPreferFavourites() {
        val tracks = listOf(
            track(1, cover = "/c/plain.jpg", addedAt = 999),
            track(2, cover = "/c/liked.jpg", liked = true),
            track(3, cover = "/c/played.jpg", playCount = 20),
            track(4, cover = "/c/liked.jpg", liked = true),
            track(5, cover = null, liked = true, playCount = 99),
        )
        assertEquals(listOf("/c/liked.jpg", "/c/played.jpg", "/c/plain.jpg"), heroCovers(tracks))
    }

    // ------------------------------------------------------------------ taste & labels

    @Test
    fun tasteKeepsOnlyTheLastHoursAsJustPlayed() {
        val now = 10_000_000_000L
        val events = listOf(
            PlayEvent(trackId = 1, playedAt = now - 60_000, listenedMs = 1, completed = true, skipped = false),
            PlayEvent(trackId = 2, playedAt = now - JUST_PLAYED_WINDOW_MS - 1, listenedMs = 1, completed = true, skipped = false),
            PlayEvent(trackId = 3, playedAt = now - JUST_PLAYED_WINDOW_MS, listenedMs = 1, completed = false, skipped = true),
        )
        val taste = buildTaste(mapOf(1L to 4), events, now)
        assertEquals(setOf(1L, 3L), taste.justPlayed)
        assertEquals(mapOf(1L to 4), taste.recentPlays)
        assertEquals(now, taste.now)
    }

    @Test
    fun decadesBeforeTwoThousandAreShortened() {
        assertEquals(90, decadeShort(1990))
        assertEquals(60, decadeShort(1960))
        assertNull(decadeShort(2000))
        assertNull(decadeShort(2010))
    }

    // ------------------------------------------------------------------ live markers

    @Test
    fun playbackSourceMatching() {
        val t = track(1, album = "A")
        val fromList = PlaybackState(current = t, source = PlaySource(PlaySource.Type.PLAYLIST, "L", "12"))
        assertTrue(fromList.isFromPlaylist(12))
        assertFalse(fromList.isFromPlaylist(13))
        assertTrue(fromList.isFromAlbum(t.albumKey!!.normalized))
        assertFalse(fromList.isFromLiked())
        val mix = PlaybackState(current = t, source = PlaySource(PlaySource.Type.MIX, "Mix", "genre:pop"))
        assertTrue(mix.isFromMix("genre:pop"))
        assertFalse(mix.isFromMix("liked"))
        assertTrue(PlaybackState(source = PlaySource(PlaySource.Type.SMART_SHUFFLE, "x")).isSmartLibrary())
    }
}
