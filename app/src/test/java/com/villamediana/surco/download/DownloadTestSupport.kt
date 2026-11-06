package com.villamediana.surco.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.villamediana.surco.data.AudioQuality
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.Storage
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.DownloadTask
import com.villamediana.surco.data.db.SurcoDatabase
import com.villamediana.surco.online.AudioStream
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.Lyrics
import com.villamediana.surco.online.LyricsSource
import com.villamediana.surco.online.MetaInput
import com.villamediana.surco.online.MetadataEnricher
import com.villamediana.surco.online.RemoteTrack
import com.villamediana.surco.online.ResolvedLink
import com.villamediana.surco.online.SearchFilter
import com.villamediana.surco.online.SearchPage
import com.villamediana.surco.online.TrackMeta
import com.villamediana.surco.online.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * API 36 under Robolectric needs `--add-opens java.base/java.io=ALL-UNNAMED` on the test JVM (its
 * ApplicationSharedMemory pokes FileDescriptor internals); the shared Gradle config does not pass it,
 * so these tests run on API 35, which exercises the same code paths.
 */
internal const val ROBOLECTRIC_SDK = 35

/** A file served by [TestServer], with knobs to misbehave like googlevideo does. */
internal class Resource(val bytes: ByteArray) {
    /** Content-Range carries the total (false → an asterisk instead of the size). */
    @Volatile var advertiseTotal = true

    /** false → ignore Range and answer 200 with the whole body. */
    @Volatile var honorRange = true

    /** 1-based request numbers answered with 403. */
    val forbidden: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Answer every request with this status (e.g. 500). */
    @Volatile var failWith: Int? = null

    /** Sleep before answering each request. */
    @Volatile var delayMs = 0L

    /** Sleep between 16 KB writes (slow server). */
    @Volatile var writeDelayMs = 0L

    /** Abort the connection once this absolute byte offset is reached (once). */
    @Volatile var dropAt: Long? = null

    val requests = AtomicInteger()
}

internal data class Hit(val path: String, val range: String?, val userAgent: String?, val status: Int)

/** Tiny HTTP server on 127.0.0.1 that honours `Range: bytes=s-e`. */
internal class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val resources = ConcurrentHashMap<String, Resource>()
    val hits: MutableList<Hit> = CopyOnWriteArrayList()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    fun put(path: String, bytes: ByteArray): Resource = Resource(bytes).also { resources[path] = it }

    fun resource(path: String): Resource = resources.getValue(path)

    fun hitsFor(path: String): List<Hit> = hits.filter { it.path == path }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val range = ex.requestHeaders.getFirst("Range")
        val ua = ex.requestHeaders.getFirst("User-Agent")
        val res = resources[path]
        try {
            if (res == null) return status(ex, Hit(path, range, ua, 404))
            val n = res.requests.incrementAndGet()
            if (res.delayMs > 0) Thread.sleep(res.delayMs)
            res.failWith?.let { return status(ex, Hit(path, range, ua, it)) }
            if (n in res.forbidden) return status(ex, Hit(path, range, ua, 403))
            val size = res.bytes.size.toLong()
            if (range == null || !res.honorRange) {
                hits += Hit(path, range, ua, 200)
                ex.sendResponseHeaders(200, size)
                write(ex, res, 0, size)
                return
            }
            val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range) ?: return status(ex, Hit(path, range, ua, 400))
            val start = m.groupValues[1].toLong()
            val end = (m.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
            if (start >= size) {
                ex.responseHeaders.add("Content-Range", "bytes */$size")
                return status(ex, Hit(path, range, ua, 416))
            }
            hits += Hit(path, range, ua, 206)
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${if (res.advertiseTotal) size.toString() else "*"}")
            ex.sendResponseHeaders(206, end - start + 1)
            write(ex, res, start, end + 1)
        } catch (e: IOException) {
            // client went away
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun write(ex: HttpExchange, res: Resource, from: Long, to: Long) {
        val out = ex.responseBody
        var pos = from
        while (pos < to) {
            val drop = res.dropAt
            if (drop != null && pos >= drop) {
                res.dropAt = null
                out.flush()
                // Abort mid-body: the connection closes short and the client sees an unexpected end of stream.
                throw IOException("dropped")
            }
            val limit = if (drop != null && drop in (pos + 1) until to) drop else to
            val n = minOf(16 * 1024L, limit - pos).toInt()
            out.write(res.bytes, pos.toInt(), n)
            out.flush()
            pos += n
            if (res.writeDelayMs > 0) Thread.sleep(res.writeDelayMs)
        }
        out.close()
    }

    private fun status(ex: HttpExchange, hit: Hit) {
        hits += hit
        ex.sendResponseHeaders(hit.status, -1)
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

internal fun randomBytes(size: Int, seed: Int = size): ByteArray = Random(seed).nextBytes(size)

/** PNG of [w]x[h] filled with [color], with an optional inner rect of another color. */
internal fun png(w: Int, h: Int, color: Int, inner: Pair<android.graphics.Rect, Int>? = null): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(color)
    inner?.let { (rect, c) -> canvas.drawRect(rect, Paint().apply { this.color = c }) }
    return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
}

internal fun Int.isReddish() = Color.red(this) > 180 && Color.green(this) < 80 && Color.blue(this) < 80
internal fun Int.isBluish() = Color.blue(this) > 180 && Color.red(this) < 80 && Color.green(this) < 80
internal fun Int.isGreenish() = Color.green(this) > 150 && Color.red(this) < 80 && Color.blue(this) < 80

/** YouTube stand-in: every video is served by [server] at /audio/<videoId>. */
internal class FakeYouTube(private val server: TestServer) : YouTubeSource {
    val streamCalls = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var knownLength = true
    @Volatile var failure: ((String) -> Throwable?)? = null

    fun calls(videoId: String) = streamCalls[videoId]?.get() ?: 0

    override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream {
        val n = streamCalls.getOrPut(videoId) { AtomicInteger() }.incrementAndGet()
        failure?.invoke(videoId)?.let { throw it }
        val size = server.resource("/audio/$videoId").bytes.size.toLong()
        return AudioStream(
            url = server.url("/audio/$videoId") + "?sig=$n",
            mimeType = "audio/mp4",
            extension = "m4a",
            bitrate = 130_000,
            contentLength = if (knownLength) size else null,
            codec = "mp4a.40.2",
        )
    }

    override suspend fun search(query: String, filter: SearchFilter): SearchPage = throw UnsupportedOperationException()
    override suspend fun more(page: SearchPage): SearchPage = throw UnsupportedOperationException()
    override suspend fun suggestions(query: String): List<String> = emptyList()
    override suspend fun resolve(text: String): ResolvedLink = ResolvedLink.Unsupported
    override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = throw UnsupportedOperationException()
    override suspend fun track(videoId: String): RemoteTrack = throw UnsupportedOperationException()
}

internal class FakeEnricher : MetadataEnricher {
    val inputs: MutableList<MetaInput> = CopyOnWriteArrayList()

    @Volatile var answer: (MetaInput) -> TrackMeta = { TrackMeta(it.title, it.artist, it.album, it.albumArtist, null, it.year, it.trackNumber, null) }

    override suspend fun enrich(input: MetaInput): TrackMeta {
        inputs += input
        return answer(input)
    }
}

internal class FakeLyrics(@Volatile var result: Lyrics? = null) : LyricsSource {
    override suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? = result
}

/** Engine + queue over an in-memory database, the [TestServer] and fakes. */
internal class Harness(context: Context, val db: SurcoDatabase = SurcoDatabase.inMemory(context)) : AutoCloseable {
    val dao = db.downloads()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val library = LibraryRepository(db, scope)
    val storage = Storage(context).also { it.audioDir.parentFile?.deleteRecursively() }
    val server = TestServer()
    val youTube = FakeYouTube(server)
    val enricher = FakeEnricher()
    val lyrics = FakeLyrics()
    val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Volatile var settings = Settings(parallelDownloads = 2, fetchLyrics = true)
    @Volatile var online = true
    val scheduled: MutableList<SchedulePolicy> = CopyOnWriteArrayList()
    val background: MutableList<suspend () -> Unit> = CopyOnWriteArrayList()

    val engine = DownloadEngine(
        dao = dao,
        library = library,
        storage = storage,
        settings = { settings },
        youTube = youTube,
        enricher = enricher,
        lyrics = lyrics,
        http = http,
        messages = AndroidDownloadMessages(context),
        isOnline = { online },
        accentOf = { ACCENT },
        probeDuration = { PROBED_DURATION },
        chunkSize = CHUNK,
        retryDelayMs = { 5L },
    )

    val queue = DownloadQueue(
        dao = dao,
        library = library,
        storage = storage,
        engine = engine,
        scheduler = DownloadScheduler { scheduled += it },
        background = { background += it },
    )

    /** Serves a fake song and returns the RemoteTrack pointing at it. */
    fun song(videoId: String, size: Int = 200_000, title: String = "Song $videoId", artist: String = "Artist", durationMs: Long? = 180_000L): RemoteTrack {
        server.put("/audio/$videoId", randomBytes(size, videoId.hashCode()))
        return RemoteTrack(
            videoId = videoId,
            url = "https://music.youtube.com/watch?v=$videoId",
            title = title,
            artist = artist,
            durationMs = durationMs,
            thumbnailUrl = server.url("/img/thumb_$videoId"),
            fromMusicCatalog = true,
        )
    }

    fun audio(videoId: String) = server.resource("/audio/$videoId")

    suspend fun allTasks(): List<DownloadTask> = dao.observeAll().first()

    suspend fun task(videoId: String): DownloadTask = allTasks().first { it.videoId == videoId }

    suspend fun awaitState(taskId: Long, predicate: (DownloadTask) -> Boolean, timeoutMs: Long = 10_000): DownloadTask =
        withTimeout(timeoutMs) {
            while (true) {
                val t = dao.get(taskId)
                if (t != null && predicate(t)) return@withTimeout t
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    override fun close() {
        server.close()
        scope.cancel()
        db.close()
    }

    companion object {
        const val CHUNK = 64L * 1024
        const val ACCENT = 0xFF336699.toInt()
        const val PROBED_DURATION = 123_000L
    }
}

internal val DownloadTask.isDone get() = state == DownloadState.DONE
