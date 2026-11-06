package com.villamediana.surco.playback.stream

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A file served by [RangeServer], with knobs to misbehave like googlevideo does. */
internal class Served(val bytes: ByteArray) {
    /** false → Content-Range says "bytes a-b/" + an asterisk instead of the size. */
    @Volatile var advertiseTotal = true

    /** false → ignore Range and answer 200 with the whole body. */
    @Volatile var honorRange = true

    /** 1-based request numbers answered with 403 (an expired url). */
    val forbidden: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Every request from now on gets 403 (the url died for good). */
    @Volatile var expired = false

    val requests = AtomicInteger()
}

internal data class ServedHit(val path: String, val range: String?, val userAgent: String?, val status: Int)

/** Tiny HTTP server on 127.0.0.1 that honours `Range: bytes=s-e` (and open-ended `bytes=s-`). */
internal class RangeServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val files = ConcurrentHashMap<String, Served>()
    val hits: MutableList<ServedHit> = CopyOnWriteArrayList()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    fun put(path: String, bytes: ByteArray): Served = Served(bytes).also { files[path] = it }

    fun hitsFor(path: String): List<ServedHit> = hits.filter { it.path == path }

    fun rangesFor(path: String): List<String?> = hitsFor(path).map { it.range }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val range = ex.requestHeaders.getFirst("Range")
        val ua = ex.requestHeaders.getFirst("User-Agent")
        val file = files[path]
        try {
            if (file == null) return status(ex, ServedHit(path, range, ua, 404))
            val n = file.requests.incrementAndGet()
            if (file.expired || n in file.forbidden) return status(ex, ServedHit(path, range, ua, 403))
            val size = file.bytes.size.toLong()
            ex.responseHeaders.add("Content-Type", "audio/webm")
            if (range == null || !file.honorRange) {
                hits += ServedHit(path, range, ua, 200)
                ex.sendResponseHeaders(200, size)
                ex.responseBody.use { it.write(file.bytes) }
                return
            }
            val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range) ?: return status(ex, ServedHit(path, range, ua, 400))
            val start = m.groupValues[1].toLong()
            val end = (m.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
            if (start >= size) {
                ex.responseHeaders.add("Content-Range", "bytes */$size")
                return status(ex, ServedHit(path, range, ua, 416))
            }
            hits += ServedHit(path, range, ua, 206)
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${if (file.advertiseTotal) size.toString() else "*"}")
            ex.sendResponseHeaders(206, end - start + 1)
            ex.responseBody.use { it.write(file.bytes, start.toInt(), (end - start + 1).toInt()) }
        } catch (_: IOException) {
            // client went away
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun status(ex: HttpExchange, hit: ServedHit) {
        hits += hit
        ex.sendResponseHeaders(hit.status, -1)
    }

    override fun close() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }
}

internal fun bytesOf(size: Int, seed: Int = size): ByteArray = Random(seed).nextBytes(size)

/** 16-bit stereo 44.1 kHz PCM in a WAV container, [seconds] long — real media ExoPlayer can prepare. */
internal fun wav(seconds: Int): ByteArray {
    val data = seconds * 44_100 * 4
    val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(44_100 * 4).putShort(4).putShort(16)
    b.put("data".toByteArray()).putInt(data)
    for (i in 0 until data / 2) b.putShort((Math.sin(i / 20.0) * 8000).toInt().toShort())
    return b.array()
}
