package com.villamediana.sound.discover

import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.online.AudioStream
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.YouTubeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DiscoverRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSource : YouTubeSource {
        val queries = mutableListOf<String>()
        var offline = false
        override suspend fun search(query: String, filter: SearchFilter): SearchPage {
            if (offline) throw IOException("offline")
            queries += query
            val items = (1..20).map { i ->
                SearchItem.Song(RemoteTrack("${query.hashCode()}_$i", "https://music.youtube.com/watch?v=$i", "$query $i", "Artist $i", thumbnailUrl = "https://img/$query/$i"))
            }
            return SearchPage(query, filter, items)
        }
        override suspend fun more(page: SearchPage) = page
        override suspend fun suggestions(query: String) = emptyList<String>()
        override suspend fun resolve(text: String) = ResolvedLink.Unsupported
        override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = throw UnsupportedOperationException()
        override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream = throw UnsupportedOperationException()
        override suspend fun track(videoId: String): RemoteTrack = throw UnsupportedOperationException()
    }

    private val pop = GenreCatalog.byId("pop")!!
    private val rock = GenreCatalog.byId("rock")!!

    @Test fun coversAreFourPerGenreAndCachedOnDisk() = runBlocking {
        val src = FakeSource()
        val file = tmp.root.resolve("d.json")
        val covers = DiscoverRepository(src, file).covers(listOf(pop, rock))
        assertEquals(4, covers.getValue("pop").size)
        assertEquals(2, src.queries.size)
        // A new instance (app restart) reads them from disk without the network.
        src.offline = true
        assertEquals(covers, DiscoverRepository(src, file).covers(listOf(pop, rock)))
    }

    @Test fun aShelfLastsItsSlotAndChangesWithTheNext() = runBlocking {
        val src = FakeSource()
        var now = 1_000_000_000L
        val repo = DiscoverRepository(src, tmp.root.resolve("d.json")) { now }
        val first = repo.shelf(pop)
        assertEquals(16, first.size)
        repo.shelf(pop)
        assertEquals(1, src.queries.size) // same slot: cached
        now += 6L * 3_600_000L
        val next = repo.shelf(pop)
        assertEquals(2, src.queries.size)
        assertNotEquals(src.queries[0], src.queries[1]) // the wording rotates: the shelf is alive
        assertNotEquals(first.first().videoId, next.first().videoId)
    }

    @Test fun offlineFallsBackToTheLastShelf() = runBlocking {
        val src = FakeSource()
        var now = 1_000_000_000L
        val repo = DiscoverRepository(src, tmp.root.resolve("d.json")) { now }
        val cached = repo.shelf(rock)
        now += 6L * 3_600_000L
        src.offline = true
        assertEquals(cached, repo.shelf(rock))
        assertTrue(repo.cachedShelf(rock).isNotEmpty())
    }
}
