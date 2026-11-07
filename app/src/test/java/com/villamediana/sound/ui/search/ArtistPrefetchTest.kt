package com.villamediana.sound.ui.search

import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.ui.nav.RemoteFind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/** Only the artist of the song in the open player is loaded ahead; the next one replaces it. */
class ArtistPrefetchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val gates = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val prefetch = ArtistPrefetch(
        scope = scope,
        find = { kind, title, _ -> RemoteCollection(kind, "url:$title", title, thumbnailUrl = "pic:$title") },
        load = { url, kind ->
            loads += url
            gates[url]?.await()
            CollectionDetail(RemoteCollection(kind, url, url.removePrefix("url:")), emptyList())
        },
    )

    private fun key(name: String) = RemoteFind.url(CollectionKind.ARTIST, name, null)

    private fun awaitLoads(n: Int) = runBlocking { withTimeout(5_000) { while (loads.size < n) kotlinx.coroutines.delay(5) } }

    @Test fun theTapGetsThePageLoadedAheadWithoutLoadingAgain() = runBlocking {
        prefetch.prefetch("Paramore")
        prefetch.prefetch("Paramore") // the same artist: nothing new
        awaitLoads(1)
        val page = prefetch.open(key("Paramore"), CollectionKind.ARTIST, "Paramore", null)
        assertEquals("url:Paramore", page.collection.url)
        assertEquals(listOf("url:Paramore"), loads.toList())
        assertEquals(key("Paramore") to "pic:Paramore", prefetch.found.value?.let { it.first to it.second.thumbnailUrl })
    }

    @Test fun anotherArtistReplacesTheOneBefore() = runBlocking {
        gates["url:Paramore"] = CompletableDeferred()
        prefetch.prefetch("Paramore")
        awaitLoads(1)
        prefetch.prefetch("Coldplay, Rihanna") // the next song: its main artist
        awaitLoads(2)
        // Paramore is no longer held: opening it loads it now.
        gates.remove("url:Paramore")!!.complete(Unit)
        val tap = async { prefetch.open(key("Paramore"), CollectionKind.ARTIST, "Paramore", null) }
        assertEquals("url:Paramore", tap.await().collection.url)
        assertEquals(listOf("url:Paramore", "url:Coldplay", "url:Paramore"), loads.toList())
        // Coldplay is the one held.
        prefetch.open(key("Coldplay"), CollectionKind.ARTIST, "Coldplay", null)
        assertEquals(3, loads.size)
    }

    @Test fun aTapWhileItLoadsJoinsTheLoad() = runBlocking {
        gates["url:Maná"] = CompletableDeferred()
        prefetch.prefetch("Maná")
        awaitLoads(1)
        val tap = async { prefetch.open(key("Maná"), CollectionKind.ARTIST, "Maná", null) }
        gates.getValue("url:Maná").complete(Unit)
        assertEquals("url:Maná", tap.await().collection.url)
        assertEquals(1, loads.size)
    }
}
