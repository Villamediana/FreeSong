package com.villamediana.surco.download

import com.villamediana.surco.online.NewPipeHttp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The server answered with an HTTP status we cannot work with. */
internal class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** A `Content-Range: bytes start-end/total` header (`total` null when the server sent `*`). */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

/** A fresh stream url (and its length, if known) handed out after a 403/410. */
internal data class FreshUrl(val url: String, val contentLength: Long?)

/**
 * The next `Range` to ask for when [start] bytes are already on disk, or null when the file is
 * complete. Chunks are at most [chunk] bytes; the last one is cut at [total] when it is known.
 */
internal fun nextRange(start: Long, total: Long?, chunk: Long): LongRange? {
    require(chunk > 0)
    if (total != null && start >= total) return null
    val end = if (total != null) minOf(start + chunk, total) - 1 else start + chunk - 1
    return start..end
}

internal fun parseContentRange(header: String?): ContentRange? {
    val m = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
    val (s, e, t) = m.destructured
    return ContentRange(s.toLong(), e.toLong(), t.toLongOrNull())
}

private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

/**
 * Downloads one url into a `.part` file in HTTP Range chunks. YouTube throttles a single open-ended
 * request to a few KB/s, while bounded 8 MB ranges come at full speed — so every request asks for
 * one chunk and the loop keeps going until the file is complete.
 *
 * Resumes from whatever is already in the file, refreshes the url on 403/410 (expired signature)
 * and works without a known length (keeps asking until a short chunk or a 416).
 */
internal class ChunkedDownloader(
    private val http: OkHttpClient,
    private val chunkSize: Long = DownloadEngine.CHUNK_SIZE,
    private val maxRefreshes: Int = DownloadEngine.MAX_URL_REFRESHES,
    private val userAgent: String = NewPipeHttp.USER_AGENT,
) {
    /**
     * @param refresh called on 403/410 for a new url; it may delete [dest] when the new stream is a
     *   different file (then the download restarts from zero).
     * @param beforeChunk called before every request; throws to stop (e.g. the task was canceled).
     * @param onProgress called after every buffer with the bytes on disk and the total, if known.
     * @return the final size of [dest].
     */
    suspend fun download(
        dest: File,
        url: String,
        contentLength: Long?,
        refresh: suspend () -> FreshUrl,
        beforeChunk: suspend () -> Unit = {},
        onProgress: suspend (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        var currentUrl = url
        var total = contentLength?.takeIf { it > 0 }
        var refreshes = 0
        dest.parentFile?.mkdirs()
        if (total != null && dest.length() > total) dest.delete()

        while (true) {
            currentCoroutineContext().ensureActive()
            beforeChunk()
            val start = dest.length()
            val range = nextRange(start, total, chunkSize) ?: break
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=${range.first}-${range.last}")
                .build()

            val step = http.newCall(request).executeCancellable { response ->
                when (response.code) {
                    206 -> {
                        val cr = parseContentRange(response.header("Content-Range"))
                        if (cr != null && cr.start != start) throw IOException("Range mismatch: asked $start, got ${cr.start}")
                        // The server's total is authoritative (the advertised length can be off by a few bytes).
                        cr?.total?.let { total = it }
                        val got = appendBody(response, dest, append = true) { onProgress(it, total) }
                        val asked = range.last - range.first + 1
                        when {
                            total != null -> if (got == 0L) throw IOException("Empty chunk at $start") else Step.Next
                            got < asked -> Step.Complete
                            else -> Step.Next
                        }
                    }
                    200 -> {
                        // Range ignored: the body is the whole file.
                        val got = appendBody(response, dest, append = false) { onProgress(it, total) }
                        val expected = response.body.contentLength().takeIf { it > 0 }
                        if (expected != null && got < expected) throw IOException("Short body: $got of $expected")
                        total = dest.length()
                        Step.Complete
                    }
                    416 -> when {
                        total == null && start > 0 -> Step.Complete
                        else -> {
                            dest.delete()
                            throw HttpStatusException(416)
                        }
                    }
                    403, 410 -> {
                        if (refreshes >= maxRefreshes) throw HttpStatusException(response.code)
                        Step.Refresh
                    }
                    else -> throw HttpStatusException(response.code)
                }
            }

            when (step) {
                Step.Next -> Unit
                Step.Complete -> break
                Step.Refresh -> {
                    refreshes++
                    val fresh = refresh()
                    currentUrl = fresh.url
                    fresh.contentLength?.takeIf { it > 0 }?.let { total = it }
                    val known = total
                    if (known != null && dest.length() > known) dest.delete()
                }
            }
        }
        val size = dest.length()
        if (total != null && size != total) throw IOException("Incomplete: $size of $total")
        size
    }

    private enum class Step { Next, Complete, Refresh }

    /** Streams the body into [dest]. Returns the bytes written by this response. */
    private suspend fun appendBody(response: Response, dest: File, append: Boolean, onProgress: suspend (Long) -> Unit): Long {
        var written = 0L
        val base = if (append) dest.length() else 0L
        val source = response.body.source()
        FileOutputStream(dest, append).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = source.read(buffer)
                if (n == -1) break
                out.write(buffer, 0, n)
                written += n
                onProgress(base + written)
            }
            out.fd.sync()
        }
        return written
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * Executes the call on the IO dispatcher and hands the response to [block]; if the coroutine is
 * cancelled while the socket is blocked, the call is cancelled so the read aborts right away.
 */
internal suspend fun <T> Call.executeCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@executeCancellable
    val hook = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { call.execute() }.use { block(it) }
    } catch (e: IOException) {
        // A read aborted by call.cancel() surfaces as "Socket closed": report it as the cancellation it is.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        hook.cancel()
    }
}
