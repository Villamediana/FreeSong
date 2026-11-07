package com.villamediana.sound.ui.search

import com.villamediana.sound.discover.DiscoverRepository
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.ui.nav.RemoteCollectionRoute
import com.villamediana.sound.ui.nav.RemoteFind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** The player's artist and album open their online pages by name. */
class RemoteFindTest {
    @get:Rule val tmp = TemporaryFolder()

    private val youTube = FakeYouTube()
    private val discover by lazy { DiscoverRepository(youTube, tmp.root.resolve("d.json")) }

    private fun artistRoute(name: String) = RemoteCollectionRoute(RemoteFind.url(CollectionKind.ARTIST, name, null), CollectionKind.ARTIST.name, name)
    private fun albumRoute(album: String, artist: String) = RemoteCollectionRoute(RemoteFind.url(CollectionKind.ALBUM, album, artist), CollectionKind.ALBUM.name, album, artist)

    @Test fun theArtistPageIsFoundOnceAndKept() = runBlocking {
        youTube.onSearch = { q, f ->
            SearchPage(q, f, if (f == SearchFilter.ARTISTS) listOf(SearchItem.Collection(RemoteCollection(CollectionKind.ARTIST, "https://music.youtube.com/channel/UCmana", "Maná"))) else emptyList())
        }
        val route = artistRoute("Maná")
        assertTrue(RemoteFind.isFind(route.url))
        assertEquals("https://music.youtube.com/channel/UCmana", findPage(route, CollectionKind.ARTIST, discover).url)
        findPage(route, CollectionKind.ARTIST, discover)
        assertEquals(1, youTube.searches.size)
    }

    @Test fun theAlbumIsTheOneByTheArtist() = runBlocking {
        youTube.onSearch = { q, f ->
            SearchPage(
                q, f,
                listOf(
                    SearchItem.Collection(RemoteCollection(CollectionKind.ALBUM, "other", "Amar Es Combatir", "Tributo")),
                    SearchItem.Collection(RemoteCollection(CollectionKind.ALBUM, "mana", "Amar Es Combatir", "Maná")),
                ),
            )
        }
        assertEquals("mana", findPage(albumRoute("Amar Es Combatir", "Maná"), CollectionKind.ALBUM, discover).url)
        assertEquals("Amar Es Combatir Maná" to SearchFilter.ALBUMS, youTube.searches.single())
    }

    @Test fun nothingFoundSaysNotFoundButAFailedSearchDoesNot() = runBlocking {
        youTube.onSearch = { q, f -> SearchPage(q, f, emptyList()) }
        try {
            findPage(artistRoute("Ninguém"), CollectionKind.ARTIST, discover)
            fail("expected not found")
        } catch (e: OnlineException) {
            assertEquals(OnlineException.Kind.NOT_FOUND, e.kind)
        }
        youTube.onSearch = { _, _ -> throw IOException("offline") }
        try {
            findPage(albumRoute("Disco", "Banda"), CollectionKind.ALBUM, discover)
            fail("expected the network error")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }
}
