package com.villamediana.sound.playback.crossfade

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(UnstableApi::class)
class DataSourceMediaTest {
    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }

    /** Counts how often the source is opened (each open of a streamed song is a request). */
    private inner class Counting(private val inner: ByteArrayDataSource = ByteArrayDataSource(bytes)) : DataSource by inner {
        var opens = 0

        override fun open(dataSpec: DataSpec): Long {
            opens++
            return inner.open(dataSpec)
        }
    }

    private fun read(m: DataSourceMedia, at: Long, size: Int): ByteArray? {
        val out = ByteArray(size)
        val n = m.readAt(at, out, 0, size)
        return if (n < 0) null else out.copyOf(n)
    }

    @Test fun readsAnyStretchAndKnowsItsSize() {
        val m = DataSourceMedia(Counting(), Uri.parse("sound://stream/abc"))
        assertEquals(300_000L, m.size)
        assertArrayEquals(bytes.copyOfRange(0, 100), read(m, 0, 100))
        // Across blocks, and from the middle of one.
        assertArrayEquals(bytes.copyOfRange(65_000, 140_000), read(m, 65_000, 75_000))
        // The end: what's left, then nothing.
        assertArrayEquals(bytes.copyOfRange(299_990, 300_000), read(m, 299_990, 100))
        assertEquals(null, read(m, 300_000, 10))
        m.close()
    }

    @Test fun readingOnDoesntReopenAndAJumpBackIsKept() {
        val source = Counting()
        val m = DataSourceMedia(source, Uri.parse("sound://stream/abc"))
        var at = 0L
        while (at < 300_000) {
            val got = read(m, at, 4_096) ?: break
            assertArrayEquals(bytes.copyOfRange(at.toInt(), at.toInt() + got.size), got)
            at += got.size
        }
        assertEquals(1, source.opens)
        // The last blocks are kept: going back a little needs no new open.
        assertArrayEquals(bytes.copyOfRange(290_000, 290_100), read(m, 290_000, 100))
        assertEquals(1, source.opens)
        m.close()
    }
}
