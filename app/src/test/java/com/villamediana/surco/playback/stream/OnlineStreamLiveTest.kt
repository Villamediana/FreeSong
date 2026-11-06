package com.villamediana.surco.playback.stream

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.data.AudioQuality
import com.villamediana.surco.online.NewPipeHttp
import com.villamediana.surco.online.NewPipeYouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A real online song read the way the player reads it: placeholder uri → NewPipe → googlevideo in
 * 2 MB ranges. Run with `SURCO_LIVE=1`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnlineStreamLiveTest {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val youTube by lazy { NewPipeYouTubeSource(http) }
    private val streams by lazy { OnlineStreams(scope) { youTube } }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /** "Prófugos (Remasterizado 2007)" — 5:18, a few MB at any quality. */
    private val song = "9WT4bMTjQqY"

    @Before fun live() {
        assumeTrue(System.getProperty("surco.live") == "1")
        NewPipeHttp.init(http, Locale("pt", "BR"))
    }

    @After fun tearDown() = scope.cancel()

    /** Reads up to [max] bytes from [position]; returns (bytes, millis, length reported by open). */
    private fun read(quality: AudioQuality, position: Long, max: Long): Triple<Long, Long, Long> {
        val source = StreamDataSources.factory(context, streams).createDataSource()
        val buf = ByteArray(256 * 1024)
        var total = 0L
        val started = System.nanoTime()
        try {
            val length = source.open(DataSpec.Builder().setUri(StreamUris.audio(song, quality)).setPosition(position).build())
            while (total < max) {
                val n = source.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                total += n
            }
            return Triple(total, (System.nanoTime() - started) / 1_000_000, length)
        } finally {
            source.close()
        }
    }

    @Test fun readsAFewMegabytesFast() {
        // Resolution (NewPipe) happens on the first open; time the bytes separately by reading twice.
        val (warm, warmMs, length) = read(AudioQuality.BEST, 0, 64 * 1024)
        println("BEST: length=$length first 64 KB in $warmMs ms (includes resolving the url)")
        assertEquals(64 * 1024L, warm)
        assertTrue("length should be known from Content-Range", length > 3_000_000)

        val want = minOf(4L * 1024 * 1024, length)
        val (bytes, ms, _) = read(AudioQuality.BEST, 0, want)
        val kbps = bytes / 1024.0 / (ms / 1000.0)
        println("BEST: read $bytes bytes in $ms ms (${"%.0f".format(kbps)} KB/s)")
        assertEquals(want, bytes)
        // An open-ended request crawls at a few KB/s; bounded ranges must be far faster than playback (~20 KB/s).
        assertTrue("too slow: ${"%.0f".format(kbps)} KB/s", kbps > 200)

        // A seek: straight to the middle, then to the end.
        val (tail, tailMs, tailLength) = read(AudioQuality.BEST, length / 2, Long.MAX_VALUE)
        println("BEST: from the middle ${tail} bytes in $tailMs ms")
        assertEquals(length - length / 2, tailLength)
        assertEquals(tailLength, tail)
    }

    @Test fun compatibleQualityIsM4a() {
        val (bytes, ms, length) = read(AudioQuality.COMPATIBLE, 0, 2L * 1024 * 1024)
        println("COMPATIBLE: length=$length, 2 MB in $ms ms")
        assertTrue(length > 2_000_000)
        assertEquals(2L * 1024 * 1024, bytes)
    }
}
