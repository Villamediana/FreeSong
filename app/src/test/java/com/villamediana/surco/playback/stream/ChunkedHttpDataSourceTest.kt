package com.villamediana.surco.playback.stream

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.villamediana.surco.online.NewPipeHttp
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** The 2 MB-range reader against a real local HTTP server (Range, 403 refresh, servers that ignore Range). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChunkedHttpDataSourceTest {
    private val server = RangeServer()
    private val chunk = 64L * 1024

    @After fun tearDown() = server.close()

    private fun source(resolver: ChunkedHttpDataSource.UriResolver? = null, size: Long = chunk): DataSource =
        ChunkedHttpDataSource(StreamDataSources.http().createDataSource(), size, resolver)

    private fun readAll(source: DataSource, spec: DataSpec, buffer: Int = 10_000): Pair<Long, ByteArray> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(buffer)
        try {
            val length = source.open(spec)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            return length to out.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun spec(path: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(server.url(path)).setPosition(position).setLength(length).build()

    @Test fun readsTheWholeResourceInBoundedRanges() {
        val bytes = bytesOf(200_000)
        server.put("/a", bytes)
        val (length, read) = readAll(source(), spec("/a"))
        assertEquals(200_000L, length)
        assertArrayEquals(bytes, read)
        assertEquals(listOf("bytes=0-65535", "bytes=65536-131071", "bytes=131072-196607", "bytes=196608-199999"), server.rangesFor("/a"))
        assertTrue(server.hitsFor("/a").all { it.userAgent == NewPipeHttp.USER_AGENT })
    }

    @Test fun startsAtAPositionAndHonoursALength() {
        val bytes = bytesOf(300_000)
        server.put("/b", bytes)
        // A seek: from 100 000 to the end.
        val (length, tail) = readAll(source(), spec("/b", position = 100_000))
        assertEquals(200_000L, length)
        assertArrayEquals(bytes.copyOfRange(100_000, 300_000), tail)
        assertEquals("bytes=100000-165535", server.rangesFor("/b").first())

        // A bounded read that spans two ranges.
        val (len2, middle) = readAll(source(), spec("/b", position = 10, length = 70_000))
        assertEquals(70_000L, len2)
        assertArrayEquals(bytes.copyOfRange(10, 70_010), middle)
        assertEquals(listOf("bytes=10-65545", "bytes=65546-70009"), server.rangesFor("/b").takeLast(2))
    }

    @Test fun twoMegabyteRangesByDefault() {
        val bytes = bytesOf(5_000_000)
        server.put("/big", bytes)
        val (_, read) = readAll(ChunkedHttpDataSource(StreamDataSources.http().createDataSource()), spec("/big"), buffer = 64 * 1024)
        assertArrayEquals(bytes, read)
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-4194303", "bytes=4194304-4999999"), server.rangesFor("/big"))
    }

    @Test fun expiredUrlIsRefreshedOnceAndTheSameRangeRetried() {
        val bytes = bytesOf(150_000)
        server.put("/old", bytes).expired = true
        server.put("/new", bytes)
        val asked = CopyOnWriteArrayList<Boolean>()
        val resolver = ChunkedHttpDataSource.UriResolver { uri, refresh ->
            asked += refresh
            assertEquals("surco://stream/abc?q=BEST", uri.toString())
            Uri.parse(server.url(if (refresh) "/new" else "/old"))
        }
        val placeholder = DataSpec(Uri.parse("surco://stream/abc?q=BEST"))
        val (length, read) = readAll(source(resolver), placeholder)
        assertEquals(150_000L, length)
        assertArrayEquals(bytes, read)
        assertEquals(listOf(false, true), asked)
        assertEquals(listOf(403), server.hitsFor("/old").map { it.status })
        assertEquals("bytes=0-65535", server.rangesFor("/new").first())
    }

    @Test fun urlThatExpiresMidSongIsRefreshedForTheNextRange() {
        val bytes = bytesOf(200_000)
        server.put("/first", bytes).forbidden += 2 // second range refused
        server.put("/second", bytes)
        var refreshes = 0
        val resolver = ChunkedHttpDataSource.UriResolver { _, refresh ->
            if (refresh) refreshes++
            Uri.parse(server.url(if (refresh) "/second" else "/first"))
        }
        val (_, read) = readAll(source(resolver), DataSpec(Uri.parse("surco://stream/xyz")))
        assertArrayEquals(bytes, read)
        assertEquals(1, refreshes)
        assertEquals(listOf("bytes=65536-131071", "bytes=131072-196607", "bytes=196608-199999"), server.rangesFor("/second"))
    }

    @Test fun refusedTwiceGivesUp() {
        server.put("/dead", bytesOf(10_000)).expired = true
        val resolver = ChunkedHttpDataSource.UriResolver { _, _ -> Uri.parse(server.url("/dead")) }
        try {
            readAll(source(resolver), DataSpec(Uri.parse("surco://stream/dead")))
            fail("expected a 403")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }
        assertEquals(2, server.hitsFor("/dead").size)
    }

    @Test fun withoutAResolverA403IsFinal() {
        server.put("/x", bytesOf(1_000)).expired = true
        try {
            readAll(source(), spec("/x"))
            fail("expected a 403")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }
        assertEquals(1, server.hitsFor("/x").size)
    }

    @Test fun serverThatIgnoresRangeIsReadInOnePlainRequest() {
        val bytes = bytesOf(150_000)
        server.put("/plain", bytes).honorRange = false
        val (_, all) = readAll(source(), spec("/plain"))
        assertArrayEquals(bytes, all)
        // One probe (answered with everything), then one plain request for the rest.
        assertEquals(2, server.hitsFor("/plain").size)

        val (_, tail) = readAll(source(), spec("/plain", position = 100_000))
        assertArrayEquals(bytes.copyOfRange(100_000, 150_000), tail)
    }

    @Test fun unknownTotalEndsOnAShortRangeOrA416() {
        val bytes = bytesOf(100_000)
        server.put("/u", bytes).advertiseTotal = false
        val (length, read) = readAll(source(), spec("/u"))
        assertEquals(C.LENGTH_UNSET.toLong(), length)
        assertArrayEquals(bytes, read)

        // Exactly two full ranges: the third asks past the end and gets 416 — that's the end, not an error.
        val exact = bytesOf(2 * chunk.toInt(), seed = 7)
        server.put("/e", exact).advertiseTotal = false
        val (_, all) = readAll(source(), spec("/e"))
        assertArrayEquals(exact, all)
        assertEquals(416, server.hitsFor("/e").last().status)
    }

    @Test fun reportsTheRealUrlAndHeaders() {
        server.put("/h", bytesOf(1_000))
        val resolver = ChunkedHttpDataSource.UriResolver { _, _ -> Uri.parse(server.url("/h")) }
        val source = source(resolver)
        source.open(DataSpec(Uri.parse("surco://stream/h")))
        assertEquals(server.url("/h"), source.uri.toString())
        assertEquals("audio/webm", ChunkedHttpDataSource.header(source.responseHeaders, "content-type"))
        source.close()
    }

    @Test fun contentRangeTotals() {
        assertEquals(1000L, ChunkedHttpDataSource.totalOf("bytes 0-99/1000"))
        assertEquals(1000L, ChunkedHttpDataSource.totalOf("Bytes 5-9/1000"))
        assertEquals(1000L, ChunkedHttpDataSource.totalOf("bytes */1000"))
        assertNull(ChunkedHttpDataSource.totalOf("bytes 0-99/*"))
        assertNull(ChunkedHttpDataSource.totalOf("garbage"))
        assertNull(ChunkedHttpDataSource.totalOf(null))
    }
}
