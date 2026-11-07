package com.villamediana.sound.discover

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPickTest {
    private fun album(title: String, author: String?, url: String = title) = RemoteCollection(CollectionKind.ALBUM, url, title, author)

    @Test fun theAlbumByTheArtistWinsOverOneThatOnlySharesTheName() {
        val found = listOf(album("Greatest Hits", "Queen", "q"), album("Greatest Hits", "Maná", "m"), album("Amar Es Combatir (Deluxe)", "Maná", "d"))
        assertEquals("m", AlbumPick.best(found, "Greatest Hits", "Maná")!!.url)
        assertEquals("d", AlbumPick.best(found, "Amar es combatir", "Maná")!!.url) // same start (deluxe) by the artist
        assertEquals("q", AlbumPick.best(found, "Greatest Hits", "Soda Stereo")!!.url) // nobody's: the name alone
        assertNull(AlbumPick.best(found, "Greatest Hits", "Soda Stereo", sameArtist = true))
        assertNull(AlbumPick.best(found, "", "Maná"))
    }
}

class AlbumMergeTest {
    private fun local(id: Long, title: String, videoId: String?) = Track(id = id, title = title, artist = "Maná", album = "Amar Es Combatir", filePath = "/$id", videoId = videoId)
    private fun remote(id: String, title: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, "Maná", "Amar Es Combatir")

    @Test fun yourSongsTakeTheirPlaceInTheAlbumAndTheRestStream() {
        val mine = listOf(local(1, "Bendita Tu Luz", "v3"), local(2, "Labios Compartidos (Live)", null), local(3, "Bonus de outro lugar", null))
        val online = listOf(remote("v1", "Ojo Por Ojo"), remote("v2", "Labios Compartidos (Live)"), remote("v3", "Bendita Tu Luz"), remote("v3", "Bendita Tu Luz"))
        val merged = AlbumMerge.merge(mine, online)
        assertEquals(listOf("Ojo Por Ojo", "Labios Compartidos (Live)", "Bendita Tu Luz", "Bonus de outro lugar"), merged.map { it.title })
        assertTrue(merged[0].isOnline)
        assertEquals(2L, merged[1].id) // same title: your copy
        assertEquals(1L, merged[2].id) // same video: your copy
        assertEquals(3L, merged[3].id) // not on the online album: stays, after it
    }

    @Test fun anOnlineAlbumWithNoneOfYourSongsIsAnotherAlbum() {
        val mine = listOf(local(1, "Bendita Tu Luz", "v3"))
        assertTrue(AlbumMerge.overlaps(mine, listOf(remote("v3", "x"))))
        assertTrue(AlbumMerge.overlaps(mine, listOf(remote("zz", "Bendita tu luz"))))
        assertFalse(AlbumMerge.overlaps(mine, listOf(remote("v9", "Otra"))))
        assertEquals(mine, AlbumMerge.merge(mine, emptyList()))
    }
}
