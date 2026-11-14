package com.villamediana.sound.karaoke

import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.floor

/** How much of each band of a moment of a song is not the voice (1: all of it stays, 0: all voice). */
fun interface MaskSource {
    /**
     * The gains of the window centred at [seconds] of the song, for bands [binHz] apart from 0 Hz
     * up: one per bin into [left] and [right]. False when that moment isn't known (yet).
     */
    fun at(seconds: Double, binHz: Double, left: FloatArray, right: FloatArray): Boolean
}

/**
 * Where the voice is in a song, as the model heard it — the karaoke of that song. Kept as a mask,
 * not as audio: for every moment ([HOP] frames at [RATE] apart) and each side, a byte per band up
 * to 11 kHz saying how much of it is not the voice, and one more for everything above. The player
 * turns the voice down with it in its own audio, so the same mask serves the song streamed or
 * downloaded, at any sample rate, and a few milliseconds off change nothing.
 *
 * The song is worked out in [parts] (about ten seconds each), in any order: a part is readable as
 * soon as it is written. The file is the cache: reopened, it knows which parts it has.
 *
 * Written by the worker thread, read by the playback thread.
 */
class VoiceMasks private constructor(val file: File, val frames: Int, private val map: MappedByteBuffer, private val access: RandomAccessFile) : MaskSource {
    val parts: Int = (frames + PART - 1) / PART
    private val ready = AtomicIntegerArray(parts)

    init {
        for (p in 0 until parts) if (map.get(HEADER_BITS + p / 8).toInt() and (1 shl (p % 8)) != 0) ready.set(p, 1)
    }

    fun isReady(part: Int): Boolean = part in 0 until parts && ready.get(part) == 1

    /** How many parts are done. */
    val done: Int get() = (0 until parts).count { ready.get(it) == 1 }

    val complete: Boolean get() = done == parts

    /** Frames of [part] (the last one is shorter). */
    fun framesIn(part: Int): Int = minOf(PART, frames - part * PART)

    /** The part the moment [positionMs] of the song is in. */
    fun partAt(positionMs: Long): Int = ((positionMs * RATE / 1_000 / HOP) / PART).toInt().coerceIn(0, parts - 1)

    /** Keeps [part]: [data] is its frames one after the other, [FRAME_BYTES] each (left side, then right). */
    fun write(part: Int, data: ByteArray) {
        require(part in 0 until parts)
        val bytes = framesIn(part) * FRAME_BYTES
        require(data.size >= bytes)
        synchronized(map) {
            val at = map.duplicate()
            at.position(HEADER + part * PART * FRAME_BYTES)
            at.put(data, 0, bytes)
            val bits = HEADER_BITS + part / 8
            map.put(bits, (map.get(bits).toInt() or (1 shl (part % 8))).toByte())
        }
        ready.set(part, 1)
    }

    private val frameA = ByteArray(FRAME_BYTES)
    private val frameB = ByteArray(FRAME_BYTES)

    /** One at a time: the player and the crossfade's tail may ask about the same song at once. */
    @Synchronized
    override fun at(seconds: Double, binHz: Double, left: FloatArray, right: FloatArray): Boolean {
        val f = (seconds * RATE / HOP).coerceIn(0.0, (frames - 1).toDouble())
        val a = floor(f).toInt()
        val b = minOf(a + 1, frames - 1)
        if (!isReady(a / PART) || !isReady(b / PART)) return false
        read(a, frameA)
        if (b != a) read(b, frameB)
        val later = if (b == a) 0f else (f - a).toFloat()
        val second = if (b == a) frameA else frameB
        // A band of the player's own audio, in the model's bands (the same when it plays at 44.1 kHz).
        val scale = binHz * FFT / RATE
        for (side in 0..1) {
            val out = if (side == 0) left else right
            val base = side * VALUES
            for (bin in out.indices) {
                val m = bin * scale
                val lo = m.toInt()
                val g = if (lo >= BINS - 1) {
                    // Above what the model hears: what it said of its top bands.
                    value(frameA, base + BINS) * (1 - later) + value(second, base + BINS) * later
                } else {
                    val t = (m - lo).toFloat()
                    val x = value(frameA, base + lo) * (1 - t) + value(frameA, base + lo + 1) * t
                    val y = value(second, base + lo) * (1 - t) + value(second, base + lo + 1) * t
                    x * (1 - later) + y * later
                }
                out[bin] = g
            }
        }
        return true
    }

    private fun value(frame: ByteArray, at: Int): Float = (frame[at].toInt() and 0xFF) * (1f / 255f)

    private fun read(frame: Int, into: ByteArray) {
        val at = map.duplicate()
        at.position(HEADER + frame * FRAME_BYTES)
        at.get(into)
    }

    /** Writes what is in memory to the file (the system does it anyway; this is for a clean stop). */
    fun flush() {
        runCatching { map.force() }
    }

    fun close() {
        flush()
        runCatching { access.close() }
    }

    companion object {
        /** The model's own rate: songs are heard by it at 44.1 kHz. */
        const val RATE = 44_100
        const val FFT = 4_096
        const val HOP = 1_024

        /** Bands the model tells apart (up to 11 kHz). */
        const val BINS = 1_024

        /** Per side: the bands, and one value for all that is above them. */
        const val VALUES = BINS + 1
        const val FRAME_BYTES = 2 * VALUES

        /** Frames per part: the middle the model's window of 512 keeps ([VocalRemover]). */
        const val PART = 448

        private const val MAGIC = 0x46534B31 // "FSK1"
        private const val HEADER_BITS = 16
        private const val HEADER = 256

        /** Frames of masks for a song of [samples] frames at [RATE]. */
        fun framesFor(samples: Long): Int = ((samples + HOP - 1) / HOP).toInt() + 1

        /** The masks a previous time left in [file], whatever their length; null when there are none (or the file is something else). */
        fun reopen(file: File): VoiceMasks? {
            if (!file.isFile || file.length() < HEADER) return null
            val frames = runCatching {
                RandomAccessFile(file, "r").use { if (it.readInt() == MAGIC) it.readInt() else 0 }
            }.getOrDefault(0)
            if (frames <= 0 || file.length() != HEADER.toLong() + frames.toLong() * FRAME_BYTES) return null
            return runCatching { open(file, frames) }.getOrNull()
        }

        /**
         * The masks of a song of [frames] moments in [file]: what a previous time left there, or a
         * new, empty one (also when the file is of another length — another recording).
         */
        fun open(file: File, frames: Int): VoiceMasks {
            require(frames > 0 && (frames + PART - 1) / PART <= (HEADER - HEADER_BITS) * 8) { "song too long for its masks" }
            file.parentFile?.mkdirs()
            val size = HEADER.toLong() + frames.toLong() * FRAME_BYTES
            var fresh = !file.isFile || file.length() != size
            val access = RandomAccessFile(file, "rw")
            try {
                if (!fresh) {
                    access.seek(0)
                    fresh = access.readInt() != MAGIC || access.readInt() != frames
                }
                if (fresh) {
                    access.setLength(0)
                    access.setLength(size)
                    access.seek(0)
                    access.writeInt(MAGIC)
                    access.writeInt(frames)
                }
                val map = access.channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
                return VoiceMasks(file, frames, map, access)
            } catch (e: Exception) {
                runCatching { access.close() }
                throw e
            }
        }
    }
}
