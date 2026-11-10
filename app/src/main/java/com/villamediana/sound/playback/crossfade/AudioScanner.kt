package com.villamediana.sound.playback.crossfade

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Measures a stretch of a song ahead of time ([Envelope]): reads it through [sources] (the
 * player's own: a downloaded file from disk, a streamed song through the audio cache, so what is
 * read here is what the player then finds ready) and decodes only that stretch. Off the main thread.
 */
@OptIn(UnstableApi::class)
internal class AudioScanner(private val sources: DataSource.Factory) {

    /** [uri] from [fromMs] to [toMs], or null when it can't be read (or [cancelled] said stop). */
    fun envelope(uri: Uri, fromMs: Long, toMs: Long, cancelled: () -> Boolean): Envelope? {
        val media = DataSourceMedia(sources.createDataSource(), uri)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(media)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val fromUs = fromMs * 1_000
            val toUs = toMs * 1_000
            // A little before the stretch, to settle the decoder and the meter's filters.
            val warmUs = (fromUs - WARM_UP_US).coerceAtLeast(0)
            if (warmUs > 0) {
                extractor.seekTo(warmUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // Without an index the seek may land far before: skip ahead without decoding.
                while (extractor.sampleTime in 0 until warmUs - WARM_UP_US) {
                    if (cancelled() || !extractor.advance()) break
                }
            }
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: return null
            var channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: return null
            var float = false
            var meter: LoudnessMeter? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + MAX_SCAN_MS
            while (true) {
                if (cancelled() || SystemClock.elapsedRealtime() > deadline) return null
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i) ?: return null
                        val n = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (n < 0 || time < 0 || time > toUs + WARM_UP_US) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        rate = out.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        channels = out.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        float = out.intOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        // Measuring starts over in the format that comes out.
                        meter = null
                    }
                    o >= 0 -> {
                        val buffer = decoder.getOutputBuffer(o)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val m = meter ?: LoudnessMeter(rate, channels, fromUs, toUs).also { meter = it }
                            m.feed(buffer, info.presentationTimeUs, float)
                        }
                        decoder.releaseOutputBuffer(o, false)
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (end || meter?.full == true) return meter?.envelope()
                    }
                }
            }
        } catch (e: Exception) {
            // A song that can't be measured just crossfades as it is.
            return null
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
            runCatching { media.close() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private companion object {
        const val WARM_UP_US = 500_000L
        const val TIMEOUT_US = 10_000L

        /** Longer than this (a network that crawls) and the fade goes without measures. */
        const val MAX_SCAN_MS = 20_000L
    }
}

/**
 * A [DataSource] seen as the random-access file [MediaExtractor] reads: in blocks, the last few
 * kept, the source left open while reading goes forward (a jump reopens it where needed).
 */
@OptIn(UnstableApi::class)
internal class DataSourceMedia(private val source: DataSource, private val uri: Uri) : MediaDataSource() {
    private var length = C.LENGTH_UNSET.toLong()
    private var openAt = -1L
    private var lengthKnown = false

    /** Block start to its bytes, most recently used last. */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(KEEP_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > KEEP_BLOCKS
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        ensureLength()
        if (length != C.LENGTH_UNSET.toLong() && position >= length) return -1
        var done = 0
        while (done < size) {
            val at = position + done
            val start = at - at % BLOCK
            val block = blocks[start] ?: load(start) ?: break
            val inBlock = (at - start).toInt()
            if (inBlock >= block.size) break
            val n = minOf(size - done, block.size - inBlock)
            System.arraycopy(block, inBlock, buffer, offset + done, n)
            done += n
            if (block.size < BLOCK) break
        }
        return if (done == 0) -1 else done
    }

    @Synchronized
    override fun getSize(): Long {
        ensureLength()
        return length
    }

    private fun ensureLength() {
        if (lengthKnown) return
        lengthKnown = true
        reopen(0)
    }

    /** One block from [start] (shorter at the end of the file); null past the end. */
    private fun load(start: Long): ByteArray? {
        if (openAt != start) reopen(start)
        val bytes = ByteArray(BLOCK)
        var n = 0
        while (n < BLOCK) {
            val r = source.read(bytes, n, BLOCK - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        openAt = if (n == BLOCK) start + BLOCK else -1
        if (n == 0) return null
        val block = if (n == BLOCK) bytes else bytes.copyOf(n)
        blocks[start] = block
        return block
    }

    private fun reopen(position: Long) {
        runCatching { source.close() }
        val spec = DataSpec.Builder().setUri(uri).setPosition(position).build()
        val n = source.open(spec)
        if (n != C.LENGTH_UNSET.toLong()) length = position + n
        openAt = position
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        blocks.clear()
        openAt = -1
        source.close()
    }

    private companion object {
        const val BLOCK = 64 * 1024
        const val KEEP_BLOCKS = 24
    }
}
