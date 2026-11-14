package com.villamediana.sound.karaoke

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.villamediana.sound.playback.crossfade.DataSourceMedia
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/** A song's audio, decoded: two sides at its own rate, any stretch of it. */
interface SongPcm : Closeable {
    val sampleRate: Int

    /** How long the song is, in frames (as its file says: it may be a little off). */
    val frames: Long

    /** Frames [from, from + count) into [left] and [right]; silence before the song and after it. */
    fun read(from: Long, count: Int, left: FloatArray, right: FloatArray)
}

/**
 * A song as the model hears it: at 44.1 kHz ([VoiceMasks.RATE]). A song at another rate (48 kHz,
 * what most streams are) is brought to it with a windowed sinc — handed over as it is, the model
 * hears every note a tone and a half off and tells the voice apart worse.
 */
class ModelAudio(private val pcm: SongPcm) {
    private val ratio = pcm.sampleRate.toDouble() / VoiceMasks.RATE

    /** The song's length at 44.1 kHz. */
    val frames: Long = (pcm.frames / ratio).toLong()

    // The stretch last asked for, both sides (the two are asked one after the other).
    private var from = Long.MIN_VALUE
    private var left = FloatArray(0)
    private var right = FloatArray(0)
    private var srcLeft = FloatArray(0)
    private var srcRight = FloatArray(0)

    /** Side [side] of frames [start, start + into.size) at 44.1 kHz. */
    fun read(side: Int, start: Long, into: FloatArray) {
        if (start != from || left.size != into.size) load(start, into.size)
        System.arraycopy(if (side == 0) left else right, 0, into, 0, into.size)
    }

    private fun load(start: Long, count: Int) {
        if (left.size != count) {
            left = FloatArray(count)
            right = FloatArray(count)
        }
        from = start
        if (ratio == 1.0) {
            pcm.read(start, count, left, right)
            return
        }
        // Going down in rate, what the new rate can't hold is filtered out by a wider kernel.
        val cut = minOf(1.0, 1.0 / ratio)
        val reach = ceil(TAPS / cut).toInt()
        val first = floor(start * ratio).toLong() - reach
        val needed = ceil((count - 1) * ratio).toInt() + 2 * reach + 2
        if (srcLeft.size < needed) {
            srcLeft = FloatArray(needed)
            srcRight = FloatArray(needed)
        }
        pcm.read(first, needed, srcLeft, srcRight)
        for (i in 0 until count) {
            val where = (start + i) * ratio - first
            val whole = floor(where).toInt()
            var l = 0f
            var r = 0f
            var total = 0f
            for (k in whole - reach + 1..whole + reach) {
                val w = kernel((k - where) * cut)
                l += srcLeft[k] * w
                r += srcRight[k] * w
                total += w
            }
            left[i] = l / total
            right[i] = r / total
        }
    }

    private companion object {
        const val TAPS = 12
        const val STEPS = 256

        /** A Hann-windowed sinc, [TAPS] zero crossings to each side. */
        val table = FloatArray(TAPS * STEPS + 2) { i ->
            val x = i.toDouble() / STEPS
            if (x >= TAPS) 0f else {
                val sinc = if (i == 0) 1.0 else sin(PI * x) / (PI * x)
                (sinc * (0.5 + 0.5 * kotlin.math.cos(PI * x / TAPS))).toFloat()
            }
        }

        fun kernel(x: Double): Float {
            val a = if (x < 0) -x else x
            if (a >= TAPS) return 0f
            val scaled = a * STEPS
            val i = scaled.toInt()
            return table[i] + (table[i + 1] - table[i]) * (scaled - i).toFloat()
        }
    }
}

/**
 * [inner] reading under the player's own cache key for the song: what the player already has of a
 * streamed song is read from the phone, and what is fetched here is there for the player after.
 */
@OptIn(UnstableApi::class)
class CachedAs(private val inner: DataSource, private val key: String?) : DataSource by inner {
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long =
        inner.open(if (key != null && dataSpec.key == null) dataSpec.buildUpon().setKey(key).build() else dataSpec)
}

/**
 * A song decoded by the phone's own codecs, read through the player's source ([DataSourceMedia]:
 * a downloaded file from disk, a streamed song through the audio cache — what was already played
 * costs no data). Stretches are asked mostly in order: the decoder runs on; a stretch somewhere
 * else (the listener sought, or the song's start is worked out last) makes it jump there.
 *
 * One thread at a time.
 */
@OptIn(UnstableApi::class)
class DecodedSong(source: DataSource, uri: Uri) : SongPcm {
    private val media = DataSourceMedia(source, uri)
    private val extractor = MediaExtractor()
    private val format: MediaFormat
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    override val sampleRate: Int
    override val frames: Long

    private var rate: Int
    private var channels: Int
    private var encoding = AudioFormat.ENCODING_PCM_16BIT

    // Decoded and kept: frames [keptFrom, keptFrom + kept) of the song.
    private var keptLeft = FloatArray(1 shl 16)
    private var keptRight = FloatArray(1 shl 16)
    private var keptFrom = 0L
    private var kept = 0
    /** The song frame the next decoded block starts at (known from the first block after a jump). */
    private var cursor = 0L
    private var positioned = false
    private var inputDone = false
    private var ended = false
    /** Polls in a row that brought no audio. */
    private var idle = 0

    init {
        try {
            extractor.setDataSource(media)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            format = extractor.getTrackFormat(track)
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            sampleRate = rate
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            frames = durationUs * rate / 1_000_000L
            start(0)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Puts the decoder at [frame] (a little before it, to settle). */
    private fun start(frame: Long) {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        val wantUs = frame * 1_000_000L / rate
        val warmUs = (wantUs - WARM_UP_US).coerceAtLeast(0)
        extractor.seekTo(warmUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // Without an index the seek may land far before: skip ahead without decoding.
        while (extractor.sampleTime in 0 until warmUs - WARM_UP_US) if (!extractor.advance()) break
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        codec = decoder
        kept = 0
        keptFrom = frame
        positioned = false
        inputDone = false
        ended = false
    }

    override fun read(from: Long, count: Int, left: FloatArray, right: FloatArray) {
        left.fill(0f, 0, count)
        right.fill(0f, 0, count)
        val begin = maxOf(from, 0)
        val end = from + count
        if (end <= begin) return
        // Before what is kept, or far after it: jump. Otherwise the decoder just runs on.
        if (begin < keptFrom || begin > keptFrom + kept + JUMP_FRAMES) start(begin)
        // What comes before this stretch won't be asked for again.
        forget(begin)
        idle = 0
        while (keptFrom + kept < end && !ended) decodeSome()
        // What is kept may start after the stretch does (the decoder had nowhere earlier to start from).
        val first = maxOf(begin, keptFrom)
        val n = (minOf(end, keptFrom + kept) - first).toInt()
        if (n > 0) {
            System.arraycopy(keptLeft, (first - keptFrom).toInt(), left, (first - from).toInt(), n)
            System.arraycopy(keptRight, (first - keptFrom).toInt(), right, (first - from).toInt(), n)
        }
    }

    private fun forget(before: Long) {
        if (before >= keptFrom + kept) {
            // A little ahead of what is decoded: the decoder runs up to it, keeping nothing on the way.
            kept = 0
            keptFrom = before
            return
        }
        val drop = (before - keptFrom).toInt()
        if (drop <= 0) return
        System.arraycopy(keptLeft, drop, keptLeft, 0, kept - drop)
        System.arraycopy(keptRight, drop, keptRight, 0, kept - drop)
        keptFrom += drop
        kept -= drop
    }

    private fun decodeSome() {
        val decoder = codec ?: run { ended = true; return }
        if (!inputDone) {
            val i = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) {
                val buffer = decoder.getInputBuffer(i)
                val n = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                val time = extractor.sampleTime
                if (n < 0 || time < 0) {
                    decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(i, 0, n, time, 0)
                    extractor.advance()
                }
            }
        }
        val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
        // A decoder that gives nothing for this long (about half a minute) is stuck: better an error than waiting forever.
        if (o < 0 && o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (++idle > MAX_IDLE) throw java.io.IOException("decoder stalled")
        } else {
            idle = 0
        }
        when {
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val out = decoder.outputFormat
                if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
            }
            o >= 0 -> {
                val buffer = decoder.getOutputBuffer(o)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    take(buffer.slice().order(ByteOrder.nativeOrder()), info.presentationTimeUs)
                }
                decoder.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
            }
        }
    }

    /** One block of decoded audio, which starts at [timeUs] of the song. */
    private fun take(bytes: ByteBuffer, timeUs: Long) {
        val float = encoding == AudioFormat.ENCODING_PCM_FLOAT
        val ch = channels.coerceAtLeast(1)
        val n = bytes.remaining() / (if (float) 4 else 2) / ch
        if (n <= 0) return
        if (!positioned) {
            // The first block after a jump says where the decoder really is; from there frames are counted.
            positioned = true
            cursor = (timeUs * rate + 500_000L) / 1_000_000L
            // It landed after the frame asked for (nowhere earlier to start from): what is kept starts there.
            if (cursor > keptFrom) keptFrom = cursor
        }
        val start = cursor
        cursor += n
        // Frames before the ones wanted (the decoder settling, or running up to a stretch ahead) are not kept.
        val skip = if (kept == 0) (keptFrom - start).coerceIn(0L, n.toLong()).toInt() else 0
        val add = n - skip
        if (add <= 0) return
        if (kept + add > keptLeft.size) {
            val capacity = maxOf(keptLeft.size * 2, kept + add)
            keptLeft = keptLeft.copyOf(capacity)
            keptRight = keptRight.copyOf(capacity)
        }
        for (i in 0 until add) {
            val frame = (skip + i) * ch
            val l: Float
            val r: Float
            if (float) {
                l = bytes.getFloat(frame * 4)
                r = if (ch > 1) bytes.getFloat((frame + 1) * 4) else l
            } else {
                l = bytes.getShort(frame * 2) / 32768f
                r = if (ch > 1) bytes.getShort((frame + 1) * 2) / 32768f else l
            }
            keptLeft[kept + i] = l
            keptRight[kept + i] = r
        }
        kept += add
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        runCatching { extractor.release() }
        runCatching { media.close() }
    }

    private companion object {
        const val WARM_UP_US = 300_000L
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 3_000

        /** A stretch this far past what is decoded is reached by jumping, not by decoding up to it. */
        const val JUMP_FRAMES = 3L * 48_000
    }
}
