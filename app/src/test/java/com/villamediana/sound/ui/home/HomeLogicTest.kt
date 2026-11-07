package com.villamediana.sound.ui.home

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.PlaylistTrack
import com.villamediana.sound.data.db.Track
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

    // ------------------------------------------------------------------ library index

    @Test
    fun playlistRecencyCountsItsSongsBeingPlayed() {
        val tracks = listOf(track(1, addedAt = 1, lastPlayedAt = 5_000))
        val index = LibraryIndex.build(tracks, listOf(PlaylistTrack(playlistId = 1, trackId = 1, position = 0)))
        assertEquals(5_000L, index.playlist(1, 10, 10).recency)
        assertEquals(3_000L, index.playlist(2, 3_000, 3_000).recency)
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
