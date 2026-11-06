package com.villamediana.sound.playback.stream

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.online.AudioStream
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.VideoStream
import com.villamediana.sound.online.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The whole read path of an online song: placeholder uri → resolved url (cached, refreshed) → 2 MB ranges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnlineStreamsTest {
    private val server = RangeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val youTube = FakeStreams(server)
    private val streams = OnlineStreams(scope) { youTube }
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun readAll(uri: Uri): ByteArray {
        val source = StreamDataSources.factory(context, streams).createDataSource()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        try {
            source.open(DataSpec(uri))
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Test fun placeholderUrisCarryVideoAndQuality() {
        val uri = StreamUris.audio("dQw4w9WgXcQ", AudioQuality.SAVER)
        assertEquals("sound://stream/dQw4w9WgXcQ?q=SAVER", uri.toString())
        assertEquals(StreamKey("dQw4w9WgXcQ", AudioQuality.SAVER), StreamUris.audioKey(uri))
        assertEquals(StreamKey("abc", AudioQuality.BEST), StreamUris.audioKey(Uri.parse("sound://stream/abc")))
        assertNull(StreamUris.audioKey(Uri.parse("file:///music/1.m4a")))
        assertNull(StreamUris.audioKey(Uri.parse("https://rr1.googlevideo.com/videoplayback")))
        assertNull(StreamUris.audioKey(null))
        val video = StreamUris.video("abc", StreamUris.AUDIO_PART)
        assertEquals("abc" to "a", StreamUris.videoPart(video))
        assertNull(StreamUris.videoPart(uri))
    }

    @Test fun streamsAnOnlineSongThroughTheResolvedUrl() {
        val bytes = bytesOf(5_000_000)
        youTube.serve("song1", bytes)
        val read = readAll(StreamUris.audio("song1", AudioQuality.BEST))
        assertArrayEquals(bytes, read)
        assertEquals(1, youTube.calls("song1"))
        val ranges = server.hits.map { it.range }
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-4194303", "bytes=4194304-4999999"), ranges)
        assertEquals(listOf(AudioQuality.BEST), youTube.qualities("song1"))

        // Played again (repeat, back): the url is reused, nothing is extracted again.
        readAll(StreamUris.audio("song1", AudioQuality.BEST))
        assertEquals(1, youTube.calls("song1"))
        // Another quality is another stream.
        readAll(StreamUris.audio("song1", AudioQuality.SAVER))
        assertEquals(2, youTube.calls("song1"))
    }

    @Test fun anExpiredUrlIsResolvedAgain() {
        val bytes = bytesOf(300_000)
        youTube.serve("old", bytes)
        readAll(StreamUris.audio("old", AudioQuality.BEST))
        server.hits.clear()
        youTube.expireAll()
        assertArrayEquals(bytes, readAll(StreamUris.audio("old", AudioQuality.BEST)))
        assertEquals(2, youTube.calls("old"))
        assertEquals(listOf(403, 206), server.hits.map { it.status })
    }

    @Test fun prefetchResolvesAheadAndThePlayerJoinsIt() {
        youTube.serve("next", bytesOf(1_000))
        youTube.delayMs = 300
        streams.prefetch(StreamUris.audio("next", AudioQuality.BEST))
        streams.prefetch(Uri.parse("file:///music/42.m4a")) // local files: nothing to do
        readAll(StreamUris.audio("next", AudioQuality.BEST))
        assertEquals(1, youTube.calls("next"))
    }

    @Test fun resolutionFailuresBecomePlayerErrorsWithTheirKind() {
        youTube.failWith = OnlineException("gone", kind = OnlineException.Kind.UNAVAILABLE)
        try {
            readAll(StreamUris.audio("x", AudioQuality.BEST))
            fail("expected a resolution failure")
        } catch (e: StreamResolveException) {
            assertEquals(OnlineException.Kind.UNAVAILABLE, e.kind)
            assertEquals(StreamErrors.code(OnlineException.Kind.UNAVAILABLE), e.reason)
            assertEquals(OnlineException.Kind.UNAVAILABLE, StreamErrors.kindOf(e.reason))
        }
        youTube.failWith = IOException("Unable to resolve host")
        try {
            readAll(StreamUris.audio("y", AudioQuality.BEST))
            fail("expected a resolution failure")
        } catch (e: StreamResolveException) {
            assertEquals(OnlineException.Kind.NO_NETWORK, e.kind)
        }
    }

    @Test fun errorCodesMapBackToOnlineKinds() {
        OnlineException.Kind.entries.forEach { assertEquals(it, StreamErrors.kindOf(StreamErrors.code(it))) }
        assertTrue(StreamErrors.code(OnlineException.Kind.NO_NETWORK) >= PlaybackException.CUSTOM_ERROR_CODE_BASE)
        assertEquals(OnlineException.Kind.NO_NETWORK, StreamErrors.kindOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(OnlineException.Kind.NO_NETWORK, StreamErrors.kindOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertNull(StreamErrors.kindOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertNull(StreamErrors.kindOf(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test fun loadErrorPolicyGivesUpOnlyOnHopelessStreams() {
        val policy = StreamLoadErrorPolicy()
        fun delayFor(e: IOException) = policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(0, DataSpec(Uri.parse("sound://stream/a")), 0),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                e,
                1,
            ),
        )
        assertEquals(C.TIME_UNSET, delayFor(StreamResolveException(OnlineException.Kind.UNAVAILABLE, RuntimeException())))
        assertEquals(C.TIME_UNSET, delayFor(StreamResolveException(OnlineException.Kind.GEO_BLOCKED, RuntimeException())))
        assertTrue(delayFor(StreamResolveException(OnlineException.Kind.NO_NETWORK, RuntimeException())) != C.TIME_UNSET)
        assertTrue(delayFor(IOException("connection reset")) != C.TIME_UNSET)
        // No network at all: nothing to wait for.
        assertEquals(C.TIME_UNSET, delayFor(StreamResolveException.offline()))
    }

    @Test fun offlineAStreamFailsAtOnceWithoutAsking() {
        youTube.serve("cached", bytesOf(1_000))
        var online = true
        val streams = OnlineStreams(scope, online = { online }) { youTube }
        streams.resolve(StreamUris.audio("cached", AudioQuality.BEST), refresh = false)
        online = false
        streams.prefetch(StreamUris.audio("next", AudioQuality.BEST))
        for (videoId in listOf("cached", "next")) {
            try {
                streams.resolve(StreamUris.audio(videoId, AudioQuality.BEST), refresh = false)
                fail("expected an offline failure")
            } catch (e: StreamResolveException) {
                assertTrue(e.offline)
                assertEquals(StreamErrors.code(OnlineException.Kind.NO_NETWORK), e.reason)
            }
        }
        assertEquals(1, youTube.calls("cached"))
        assertEquals(0, youTube.calls("next"))
        assertEquals(Uri.parse("file:///music/1.m4a"), streams.resolve(Uri.parse("file:///music/1.m4a"), refresh = false))
    }
}

/** Serves each "video" from the [RangeServer] under a new path per resolution, so a refresh is visible. */
internal class FakeStreams(private val server: RangeServer) : YouTubeSource {
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val asked = ConcurrentHashMap<String, MutableList<AudioQuality>>()
    @Volatile var failWith: Throwable? = null
    @Volatile var delayMs = 0L

    fun serve(videoId: String, bytes: ByteArray) {
        files[videoId] = bytes
    }

    fun calls(videoId: String) = counts[videoId]?.get() ?: 0
    fun qualities(videoId: String): List<AudioQuality> = asked[videoId].orEmpty().distinct()

    private val served = mutableListOf<Served>()

    /** Every url handed out so far stops working (403), like googlevideo after ~6 h. */
    fun expireAll() = synchronized(served) { served.forEach { it.expired = true } }

    override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream {
        if (delayMs > 0) delay(delayMs)
        failWith?.let { throw it }
        val n = counts.getOrPut(videoId) { AtomicInteger() }.incrementAndGet()
        asked.getOrPut(videoId) { java.util.concurrent.CopyOnWriteArrayList() } += quality
        val bytes = files[videoId] ?: throw OnlineException("no such video", kind = OnlineException.Kind.NOT_FOUND)
        val path = "/$videoId-${quality.name}-$n"
        synchronized(served) { served += server.put(path, bytes) }
        return AudioStream(server.url(path), "audio/webm", "webm", 160_000, bytes.size.toLong(), "opus")
    }

    private val videos = ConcurrentHashMap<String, Pair<ByteArray, ByteArray?>>()

    /** A "music video": [video] (+ a separate [audio] part, or null for a muxed stream). */
    fun serveVideo(videoId: String, video: ByteArray, audio: ByteArray?) {
        videos[videoId] = video to audio
    }

    override suspend fun videoStream(videoId: String, maxHeight: Int): VideoStream {
        if (delayMs > 0) delay(delayMs)
        failWith?.let { throw it }
        val n = counts.getOrPut("video:$videoId") { AtomicInteger() }.incrementAndGet()
        val (video, audio) = videos[videoId] ?: throw OnlineException("no such video", kind = OnlineException.Kind.NOT_FOUND)
        val videoPath = "/video-$videoId-$n"
        synchronized(served) { served += server.put(videoPath, video) }
        val audioPath = audio?.let { "/video-audio-$videoId-$n".also { p -> synchronized(served) { served += server.put(p, it) } } }
        return VideoStream(server.url(videoPath), audioPath?.let(server::url), 1280, 720, "audio/wav", audioPath?.let { "audio/wav" })
    }

    override suspend fun search(query: String, filter: SearchFilter): SearchPage = throw UnsupportedOperationException()
    override suspend fun more(page: SearchPage): SearchPage = throw UnsupportedOperationException()
    override suspend fun suggestions(query: String): List<String> = emptyList()
    override suspend fun resolve(text: String): ResolvedLink = ResolvedLink.Unsupported
    override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = throw UnsupportedOperationException()
    override suspend fun track(videoId: String): RemoteTrack = throw UnsupportedOperationException()
}
