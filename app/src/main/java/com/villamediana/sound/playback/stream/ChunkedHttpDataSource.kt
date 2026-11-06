package com.villamediana.sound.playback.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import kotlin.math.min

/**
 * A progressive HTTP resource read as consecutive bounded `Range` requests of [chunkSize] bytes.
 *
 * googlevideo throttles an open-ended request to a few KB/s, while a bounded range of a couple of
 * megabytes arrives at full speed. So the player never asks for "everything from here": it asks for
 * the next 2 MB, then the next, transparently — seeking just starts the series somewhere else.
 *
 * The [DataSpec] may name a placeholder (see [StreamUris]); [resolver] turns it into the real url.
 * When the server refuses a url (403/410: it expired), the resolver is asked for a fresh one and the
 * same range is tried once more. A server that ignores `Range` (answers 200 with the whole body) is
 * then read in one plain request.
 */
@OptIn(UnstableApi::class)
class ChunkedHttpDataSource(
    private val upstream: HttpDataSource,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val resolver: UriResolver? = null,
) : BaseDataSource(/* isNetwork = */ true) {

    /** Maps a placeholder uri to the real one; [refresh] = the last answer was refused, get a new one. */
    fun interface UriResolver {
        @Throws(IOException::class)
        fun resolve(uri: Uri, refresh: Boolean): Uri
    }

    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
        private val resolver: UriResolver? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedHttpDataSource(upstream.createDataSource(), chunkSize, resolver)
    }

    private var spec: DataSpec? = null
    private var target: Uri? = null
    private var position = 0L
    /** Absolute end (exclusive) of what was asked for, or -1 while unknown. */
    private var end = UNKNOWN
    private var resourceLength = UNKNOWN
    private var chunkRequested = 0L
    private var chunkRead = 0L
    private var chunkOpen = false
    /** The server ignores `Range`: one plain request serves the rest. */
    private var whole = false
    private var opened = false
    private var headers: Map<String, List<String>> = emptyMap()

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        position = dataSpec.position
        resourceLength = UNKNOWN
        whole = false
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else UNKNOWN
        if (end == position) {
            // Nothing asked for: no request at all.
            opened = true
            transferStarted(dataSpec)
            return 0
        }
        target = resolver?.resolve(dataSpec.uri, false) ?: dataSpec.uri
        openChunk()
        if (resourceLength >= 0) {
            if (position > resourceLength) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            end = if (end >= 0) min(end, resourceLength) else resourceLength
        }
        opened = true
        transferStarted(dataSpec)
        return if (end >= 0) end - position else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (end >= 0 && position >= end) return C.RESULT_END_OF_INPUT
            if (!chunkOpen && !openNextChunk()) return C.RESULT_END_OF_INPUT
            val max = if (end >= 0) min(length.toLong(), end - position).toInt() else length
            val n = upstream.read(buffer, offset, max)
            if (n == C.RESULT_END_OF_INPUT) {
                closeChunk()
                // Size unknown and this range came back short: that was the end of the resource.
                if (whole || (end < 0 && chunkRead < chunkRequested)) return C.RESULT_END_OF_INPUT
                continue
            }
            position += n
            chunkRead += n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = target ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        spec = null
        target = null
        try {
            closeChunk()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    // ---------------------------------------------------------------- chunks

    private fun openNextChunk(): Boolean = try {
        openChunk()
        true
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        // Size unknown and the previous range ended exactly at the end of the resource.
        if (e.responseCode == 416 && end < 0) false else throw e
    }

    private fun openChunk() {
        val logical = spec ?: throw IllegalStateException("not opened")
        val length = if (end >= 0) min(chunkSize, end - position) else chunkSize
        var refreshed = false
        while (true) {
            val chunk = logical.buildUpon()
                .setUri(target ?: logical.uri)
                .setPosition(position)
                .setLength(length)
                .build()
            try {
                upstream.open(chunk)
                break
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                closeQuietly()
                if (!refreshed && resolver != null && (e.responseCode == 403 || e.responseCode == 410)) {
                    refreshed = true
                    target = resolver.resolve(logical.uri, true)
                    continue
                }
                throw e
            } catch (e: IOException) {
                closeQuietly()
                throw e
            }
        }
        chunkOpen = true
        chunkRequested = length
        chunkRead = 0
        headers = upstream.responseHeaders
        val contentRange = header(headers, "Content-Range")
        if (contentRange == null && upstream.responseCode == 200) {
            // Range ignored: the upstream skipped to [position] itself but stops after [length]; ask once for all of it.
            closeQuietly()
            val rest = logical.buildUpon()
                .setUri(target ?: logical.uri)
                .setPosition(position)
                .setLength(if (end >= 0) end - position else C.LENGTH_UNSET.toLong())
                .build()
            val n = upstream.open(rest)
            chunkOpen = true
            headers = upstream.responseHeaders
            whole = true
            chunkRequested = Long.MAX_VALUE
            if (n != C.LENGTH_UNSET.toLong() && end < 0) resourceLength = position + n
            return
        }
        if (resourceLength < 0) totalOf(contentRange)?.let { resourceLength = it }
    }

    private fun closeChunk() {
        if (!chunkOpen) return
        chunkOpen = false
        upstream.close()
    }

    private fun closeQuietly() {
        chunkOpen = false
        try {
            upstream.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** 2 MB: arrives in a fraction of a second, and ~100 s of a 160 kbps song per request. */
        const val DEFAULT_CHUNK_SIZE = 2L * 1024 * 1024
        private const val UNKNOWN = -1L

        /** Size in a Content-Range ("bytes 0-99/1000" → 1000, also for a refused range); an asterisk (unknown) → null. */
        fun totalOf(contentRange: String?): Long? {
            val value = contentRange?.trim() ?: return null
            val slash = value.lastIndexOf('/')
            if (slash < 0 || !value.startsWith("bytes", ignoreCase = true)) return null
            return value.substring(slash + 1).trim().toLongOrNull()?.takeIf { it >= 0 }
        }

        internal fun header(headers: Map<String, List<String>>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }
}
