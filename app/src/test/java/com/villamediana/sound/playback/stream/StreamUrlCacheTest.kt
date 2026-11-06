package com.villamediana.sound.playback.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Url cache of online streams: reuse, expiry (ttl and the url's own), refresh, shared resolutions. */
class StreamUrlCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000_000L
    private val calls = AtomicInteger()
    private val previousSeen = mutableListOf<String?>()

    @After fun tearDown() = scope.cancel()

    private fun cache(ttl: Long = 60_000, delayMs: Long = 0, fail: Boolean = false) =
        StreamUrlCache<String, String>(scope, urlOf = { it }, ttlMs = ttl, clock = { now }) { key, previous ->
            synchronized(previousSeen) { previousSeen += previous }
            if (delayMs > 0) delay(delayMs)
            val n = calls.incrementAndGet()
            if (fail) throw IllegalStateException("offline")
            "https://rr1.googlevideo.com/videoplayback?id=$key&n=$n"
        }

    @Test fun reusesUntilTheTtlRunsOut() = runBlocking {
        val c = cache(ttl = 60_000)
        val first = c.get("a")
        assertEquals(first, c.get("a"))
        assertEquals(1, calls.get())
        now += 59_000
        assertEquals(first, c.peek("a"))
        now += 2_000
        assertNull(c.peek("a"))
        val second = c.get("a")
        assertTrue(second != first)
        assertEquals(2, calls.get())
        // Keys are independent.
        c.get("b")
        assertEquals(3, calls.get())
    }

    @Test fun theUrlsOwnExpiryWinsWhenSooner() = runBlocking {
        val expireAt = now / 1000 + 600 // url valid for 10 min
        val c = StreamUrlCache<String, String>(scope, urlOf = { it }, ttlMs = 4 * 60 * 60_000L, clock = { now }) { _, _ ->
            calls.incrementAndGet()
            "https://rr1.googlevideo.com/videoplayback?expire=$expireAt&itag=251"
        }
        c.get("v")
        // Kept until 5 min before the url's expiry (10 min − 5 min of margin), long before the 4 h ttl.
        now += 4 * 60_000
        c.get("v")
        assertEquals(1, calls.get())
        now += 2 * 60_000
        c.get("v")
        assertEquals(2, calls.get())
        assertEquals(expireAt * 1000, StreamUrlCache.urlExpiryMs("https://x.googlevideo.com/videoplayback?expire=$expireAt"))
        assertNull(StreamUrlCache.urlExpiryMs("https://example.com/no-expire"))
        assertNull(StreamUrlCache.urlExpiryMs("not a url"))
    }

    @Test fun freshResolvesAgainAndSeesThePreviousValue() = runBlocking {
        val c = cache()
        val first = c.get("a")
        val refreshed = c.get("a", fresh = true)
        assertTrue(refreshed != first)
        assertEquals(listOf(null, first), previousSeen)
        assertEquals(refreshed, c.get("a"))
        assertEquals(2, calls.get())
    }

    @Test fun concurrentAsksShareOneResolution() = runBlocking {
        val c = cache(delayMs = 200)
        c.prefetch("a")
        val results = (1..8).map { async(Dispatchers.Default) { c.get("a") } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(1, calls.get())
    }

    @Test fun failuresAreNotCachedAndReachEveryWaiter() = runBlocking {
        val c = cache(delayMs = 50, fail = true)
        val waiters = (1..3).map { async(Dispatchers.Default) { runCatching { c.get("a") } } }.awaitAll()
        assertTrue(waiters.all { it.exceptionOrNull() is IllegalStateException })
        assertEquals(1, calls.get())
        try {
            c.get("a")
            fail()
        } catch (_: IllegalStateException) {
        }
        assertEquals(2, calls.get())
    }

    @Test fun aCallerGivingUpDoesNotCancelTheSharedWork() = runBlocking {
        val c = cache(delayMs = 300)
        runCatching { withTimeout(50) { c.get("a") } }
        // The resolution kept going; the next ask joins it (or finds it cached).
        c.get("a")
        assertEquals(1, calls.get())
    }

    @Test fun prefetchWarmsTheCache() = runBlocking {
        val c = cache()
        c.prefetch("p")
        withTimeout(2_000) { while (c.peek("p") == null) delay(10) }
        c.prefetch("p") // already there: nothing to do
        c.get("p")
        assertEquals(1, calls.get())
    }
}
