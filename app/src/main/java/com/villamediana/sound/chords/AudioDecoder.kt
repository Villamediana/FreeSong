package com.villamediana.sound.chords

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * A song's audio as mono floats at [rate] Hz, decoded by the phone's own codecs (AAC, Opus, MP3…)
 * and resampled as it goes (from the LauncherAI's MediaOps.decodeMono). Streaming on purpose: a
 * five-minute stereo 48 kHz song is over 50 MB decoded; only the mono 22 kHz copy is kept.
 */
internal object AudioDecoder {
    class TooLong : Exception("Song too long to hear its chords")

    fun decodeMono(input: File, rate: Int, maxMs: Long, isActive: () -> Boolean, progress: (Float) -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > maxMs * 1000) throw TooLong()
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val capacity = if (durationUs > 0) (durationUs / 1_000_000.0 * rate).toInt() + rate else rate * 60 * 4
            val out = FloatList(capacity)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var srcIndex = 0L // global index of the first frame of the current block
                var next = 0.0 // position, in source frames, of the next output sample
                var prev = 0f
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!isActive()) throw kotlinx.coroutines.CancellationException()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val time = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, size, time, 0)
                                if (durationUs > 0) progress((time.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val ch = channels.coerceAtLeast(1)
                            val frames = shorts.remaining() / ch
                            if (frames > 0) {
                                val mono = FloatArray(frames)
                                for (fr in 0 until frames) {
                                    var sum = 0
                                    for (c in 0 until ch) sum += shorts.get(fr * ch + c)
                                    mono[fr] = sum.toFloat() / ch / 32768f
                                }
                                val step = srcRate.toDouble() / rate
                                while (next < srcIndex + frames) {
                                    val local = next - srcIndex
                                    val a = kotlin.math.floor(local).toInt()
                                    val t = (local - a).toFloat()
                                    val s0 = if (a < 0) prev else mono[a]
                                    val s1 = if (a + 1 < frames) mono[a + 1] else mono[frames - 1]
                                    out.add(s0 * (1 - t) + s1 * t)
                                    next += step
                                }
                                prev = mono[frames - 1]
                                srcIndex += frames
                                if (out.size > maxMs / 1000 * rate + rate) throw TooLong()
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return out.toArray()
        } finally {
            extractor.release()
        }
    }

    /** A growable float array (a song is millions of samples: no boxing). */
    private class FloatList(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(1024))
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size + data.size / 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
