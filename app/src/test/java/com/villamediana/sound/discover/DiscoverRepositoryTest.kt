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

    @Test fun aShelfSearchesOncePerSlotAndDrawsFromAGrowingPool() = runBlocking {
        val src = FakeSource()
        var now = 1_000_000_000L
        val repo = DiscoverRepository(src, tmp.root.resolve("d.json"), clock = { now }, session = 7)
        val first = repo.shelf(pop)
        assertEquals(16, first.size)
        assertEquals(2, src.queries.size) // two wordings a slot
        assertNotEquals(src.queries[0], src.queries[1])
        // Same slot and session: cached, the same draw.
        assertEquals(first, repo.shelf(pop))
        assertEquals(2, src.queries.size)
        // Half an hour later: another draw from the same songs, no network.
        now += 30L * 60_000L
        val later = repo.shelf(pop)
        assertEquals(2, src.queries.size)
        assertNotEquals(first, later)
        // Next slot: new wordings; the shelf mixes this slot's songs with the ones before.
        now += 6L * 3_600_000L
        val next = repo.shelf(pop)
        assertEquals(4, src.queries.size)
        assertTrue(src.queries.drop(2).none { it in src.queries.take(2) })
        val newIds = src.queries.drop(2).map { it.hashCode().toString() }
        val oldIds = src.queries.take(2).map { it.hashCode().toString() }
        assertEquals(8, next.count { t -> newIds.any { t.videoId.startsWith(it + "_") } })
        assertEquals(8, next.count { t -> oldIds.any { t.videoId.startsWith(it + "_") } })
    }

    @Test fun anotherAppStartDrawsAnotherShelf() = runBlocking {
        val src = FakeSource()
        val file = tmp.root.resolve("d.json")
        val a = DiscoverRepository(src, file, clock = { 1_000_000_000L }, session = 1).shelf(pop)
        val b = DiscoverRepository(src, file, clock = { 1_000_000_000L }, session = 2).shelf(pop)
        assertEquals(2, src.queries.size) // the second start read the slot from disk
        assertNotEquals(a, b)
        assertEquals(a.toSet().size, 16)
    }

    @Test fun offlineFallsBackToTheLastShelf() = runBlocking {
        val src = FakeSource()
        var now = 1_000_000_000L
        val repo = DiscoverRepository(src, tmp.root.resolve("d.json"), clock = { now }, session = 3)
        val cached = repo.shelf(rock)
        val known = src.queries.map { it.hashCode().toString() }
        now += 6L * 3_600_000L
        src.offline = true
        val offline = repo.shelf(rock)
        assertEquals(16, offline.size)
        assertTrue(offline.all { t -> known.any { t.videoId.startsWith(it + "_") } })
        assertTrue(cached.isNotEmpty() && repo.cachedShelf(rock).isNotEmpty())
    }
}
