package com.villamediana.sound.testing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV files, for the tests that run on real songs. */
object Wav {
    class Audio(val rate: Int, val channels: Int, val samples: FloatArray) {
        val frames: Int get() = samples.size / channels
    }

    fun read(file: File): Audio {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 0x46464952) { "not a RIFF file" }
        b.int
        require(b.int == 0x45564157) { "not a WAVE file" }
        var rate = 0
        var channels = 0
        var bits = 0
        while (b.remaining() >= 8) {
            val id = b.int
            val size = b.int
            val next = b.position() + size + (size and 1)
            if (id == 0x20746d66) {
                b.short
                channels = b.short.toInt()
                rate = b.int
                b.int
                b.short
                bits = b.short.toInt()
            } else if (id == 0x61746164) {
                require(bits == 16) { "only 16-bit WAV" }
                val n = minOf(size, b.remaining()) / 2
                return Audio(rate, channels, FloatArray(n) { b.short / 32768f })
            }
            if (next > b.limit()) break
            b.position(next)
        }
        error("no audio in ${file.name}")
    }

    fun write(file: File, rate: Int, channels: Int, samples: FloatArray) {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46464952).putInt(36 + data).putInt(0x45564157)
        b.putInt(0x20746d66).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.putInt(0x61746164).putInt(data)
        for (v in samples) b.putShort((v * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        file.writeBytes(b.array())
    }
}
