package com.villamediana.sound.download

import com.villamediana.sound.online.NewPipeHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Range math and the chunk loop against a real HTTP server (plain JVM, no Android). */
class ChunkedDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: TestServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val chunk = 64L * 1024
    private val downloader get() = ChunkedDownloader(http, chunk)
    private lateinit var dest: File

    @Before fun setUp() {
        server = TestServer()
        dest = File(tmp.root, "song.part")
    }

    @After fun tearDown() = server.close()

    private fun ranges(path: String) = server.hitsFor(path).mapNotNull { it.range }

    @Test fun rangeMath() {
        assertEquals(0L..29L, nextRange(0, 100, 30))
        assertEquals(30L..59L, nextRange(30, 100, 30))
        assertEquals(90L..99L, nextRange(90, 100, 30))
        assertNull(nextRange(100, 100, 30))
        assertNull(nextRange(120, 100, 30))
        assertEquals(0L..29L, nextRange(0, null, 30))
        assertEquals(60L..89L, nextRange(60, null, 30))
        // The real chunk: 8 MB ranges, last one cut at the file size.
        val mb8 = DownloadEngine.CHUNK_SIZE
        assertEquals(0L until mb8, nextRange(0, 20_000_000, mb8))
        assertEquals(2 * mb8 until 20_000_000L, nextRange(2 * mb8, 20_000_000, mb8))
    }

    @Test fun contentRangeParsing() {
        assertEquals(ContentRange(0, 99, 1000), parseContentRange("bytes 0-99/1000"))
        assertEquals(ContentRange(100, 199, null), parseContentRange("bytes 100-199/*"))
        assertEquals(ContentRange(5, 9, 10), parseContentRange("Bytes 5-9/10"))
        assertNull(parseContentRange("bytes */1000"))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("garbage"))
    }

    @Test fun downloadsInBoundedChunks() = runBlocking {
        val bytes = randomBytes(300_000)
        server.put("/a", bytes)
        val progress = mutableListOf<Long>()
        val size = downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = { fail(); error("") }) { done, _ -> progress += done }
        assertEquals(bytes.size.toLong(), size)
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals(
            listOf("bytes=0-65535", "bytes=65536-131071", "bytes=131072-196607", "bytes=196608-262143", "bytes=262144-299999"),
            ranges("/a"),
        )
        assertTrue(server.hitsFor("/a").all { it.userAgent == NewPipeHttp.USER_AGENT })
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test fun resumesFromWhatIsOnDisk() = runBlocking {
        val bytes = randomBytes(200_000)
        server.put("/a", bytes)
        dest.writeBytes(bytes.copyOfRange(0, 100_000))
        downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = { error("") })
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals("bytes=100000-165535", ranges("/a").first())
    }

    @Test fun oversizedPartIsDiscarded() = runBlocking {
        val bytes = randomBytes(50_000)
        server.put("/a", bytes)
        dest.writeBytes(randomBytes(60_000, 7))
        downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = { error("") })
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test fun refreshesTheUrlOn403AndKeepsGoing() = runBlocking {
        val bytes = randomBytes(200_000)
        server.put("/a", bytes).forbidden += 2
        val refreshes = AtomicInteger()
        downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = {
            refreshes.incrementAndGet()
            FreshUrl(server.url("/a") + "?fresh", bytes.size.toLong())
        })
        assertEquals(1, refreshes.get())
        assertArrayEquals(bytes, dest.readBytes())
        // The refused range is asked again with the new url, nothing is skipped.
        assertEquals(listOf("bytes=0-65535", "bytes=65536-131071", "bytes=65536-131071"), ranges("/a").take(3))
    }

    @Test fun givesUpAfterThreeRefreshes() = runBlocking {
        val bytes = randomBytes(100_000)
        server.put("/a", bytes).forbidden += (1..10)
        val refreshes = AtomicInteger()
        try {
            downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = {
                refreshes.incrementAndGet()
                FreshUrl(server.url("/a"), bytes.size.toLong())
            })
            fail("should throw")
        } catch (e: HttpStatusException) {
            assertEquals(403, e.code)
        }
        assertEquals(DownloadEngine.MAX_URL_REFRESHES, refreshes.get())
    }

    @Test fun unknownLengthStopsAtTheShortChunk() = runBlocking {
        val bytes = randomBytes(150_000)
        server.put("/a", bytes).advertiseTotal = false
        downloader.download(dest, server.url("/a"), null, refresh = { error("") })
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals(3, ranges("/a").size)
    }

    @Test fun unknownLengthExactMultipleEndsWith416() = runBlocking {
        val bytes = randomBytes((2 * chunk).toInt())
        server.put("/a", bytes).advertiseTotal = false
        downloader.download(dest, server.url("/a"), null, refresh = { error("") })
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals(listOf(206, 206, 416), server.hitsFor("/a").map { it.status })
    }

    @Test fun learnsTheLengthFromContentRange() = runBlocking {
        val bytes = randomBytes(130_000)
        server.put("/a", bytes)
        downloader.download(dest, server.url("/a"), null, refresh = { error("") })
        assertArrayEquals(bytes, dest.readBytes())
        // Knowing the total after the first answer, the last range is cut exactly at the end.
        assertEquals(listOf("bytes=0-65535", "bytes=65536-129999"), ranges("/a"))
    }

    @Test fun serverIgnoringRangeStillWorks() = runBlocking {
        val bytes = randomBytes(120_000)
        server.put("/a", bytes).honorRange = false
        dest.writeBytes(bytes.copyOfRange(0, 10))
        downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = { error("") })
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test fun serverErrorIsReported() = runBlocking {
        server.put("/a", randomBytes(10)).failWith = 500
        try {
            downloader.download(dest, server.url("/a"), 10, refresh = { error("") })
            fail("should throw")
        } catch (e: HttpStatusException) {
            assertEquals(500, e.code)
        }
    }

    @Test fun cancellationAbortsABlockedRead() = runBlocking {
        val bytes = randomBytes(400_000)
        server.put("/a", bytes).writeDelayMs = 300
        val job = async(Dispatchers.IO) {
            downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = { error("") })
        }
        withTimeout(5_000) { while (dest.length() == 0L) delay(10) }
        val started = System.nanoTime()
        job.cancel()
        try {
            job.await()
            fail("should be cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took $tookMs ms", tookMs < 1_000)
        assertTrue(dest.length() in 1 until bytes.size) // the partial file stays for a resume
    }

    @Test fun beforeChunkCanStopTheLoop() = runBlocking {
        val bytes = randomBytes(300_000)
        server.put("/a", bytes)
        val calls = AtomicInteger()
        try {
            downloader.download(dest, server.url("/a"), bytes.size.toLong(), refresh = { error("") }, beforeChunk = {
                if (calls.incrementAndGet() == 3) throw CancellationException("stop")
            })
            fail("should stop")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(2 * chunk, dest.length())
    }
}
