package com.villamediana.sound.chords

import android.media.AudioFormat
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
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                val resampler = MonoResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), rate)
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
                        resampler.rate(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        // Most decoders give 16-bit samples; some give floats (or wider integers).
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val mono = mono(buf.slice().order(ByteOrder.nativeOrder()), channels.coerceAtLeast(1), encoding)
                            if (mono.isNotEmpty()) {
                                for (v in resampler.push(mono)) out.add(v)
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

    /** One block of decoded PCM, its channels averaged, as −1…1 floats. */
    private fun mono(bytes: java.nio.ByteBuffer, channels: Int, encoding: Int): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val f = bytes.asFloatBuffer()
            FloatArray(f.remaining() / channels) { fr ->
                var sum = 0f
                for (c in 0 until channels) sum += f.get(fr * channels + c)
                sum / channels
            }
        }
        AudioFormat.ENCODING_PCM_32BIT -> {
            val ints = bytes.asIntBuffer()
            FloatArray(ints.remaining() / channels) { fr ->
                var sum = 0.0
                for (c in 0 until channels) sum += ints.get(fr * channels + c)
                (sum / channels / 2147483648.0).toFloat()
            }
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val frames = bytes.remaining() / 3 / channels
            FloatArray(frames) { fr ->
                var sum = 0.0
                for (c in 0 until channels) {
                    val at = bytes.position() + (fr * channels + c) * 3
                    // Little-endian, sign from the top byte.
                    val v = (bytes.get(at).toInt() and 0xFF) or ((bytes.get(at + 1).toInt() and 0xFF) shl 8) or (bytes.get(at + 2).toInt() shl 16)
                    sum += v
                }
                (sum / channels / 8388608.0).toFloat()
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> FloatArray(bytes.remaining() / channels) { fr ->
            var sum = 0
            for (c in 0 until channels) sum += (bytes.get(bytes.position() + fr * channels + c).toInt() and 0xFF) - 128
            sum.toFloat() / channels / 128f
        }
        else -> {
            val shorts = bytes.asShortBuffer()
            FloatArray(shorts.remaining() / channels) { fr ->
                var sum = 0
                for (c in 0 until channels) sum += shorts.get(fr * channels + c)
                sum.toFloat() / channels / 32768f
            }
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
