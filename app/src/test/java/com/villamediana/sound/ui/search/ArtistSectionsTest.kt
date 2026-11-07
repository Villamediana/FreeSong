package com.villamediana.sound.ui.search

import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSectionsTest {
    private fun album(title: String, year: Int?, single: Boolean = false) =
        RemoteCollection(CollectionKind.ALBUM, "u:$title", title, "Paramore", year = year, single = single)

    private val detail = CollectionDetail(
        RemoteCollection(CollectionKind.ARTIST, "a", "Paramore"),
        tracks = (1..12).map { remoteTrack("v%010d".format(it), "Song $it") },
        related = listOf(
            album("Riot!", 2007), album("After Laughter", 2017), album("Brand New Eyes", 2009),
            album("Hard Times", 2017, single = true), album("Running Out of Time", 2022, single = true), album("Sem ano", null),
            RemoteCollection(CollectionKind.PLAYLIST, "p1", "This Is Paramore"),
            album("Riot!", 2007),
        ),
    )

    @Test fun albumsFromTheNewestThenSinglesAndPlaylists() {
        val s = ArtistSections.of(detail, allSongs = false)
        assertEquals(listOf("After Laughter", "Brand New Eyes", "Riot!", "Sem ano"), s.albums.map { it.title })
        assertEquals(listOf("Running Out of Time", "Hard Times"), s.singles.map { it.title })
        assertEquals(listOf("This Is Paramore"), s.playlists.map { it.title })
    }

    @Test fun aFewPopularSongsAndAllOnDemand() {
        assertEquals(ArtistSections.POPULAR, ArtistSections.of(detail, allSongs = false).popular.size)
        assertEquals(7, ArtistSections.of(detail, allSongs = false).moreSongs)
        assertEquals(12, ArtistSections.of(detail, allSongs = true).popular.size)
    }
}
