package com.villamediana.sound.chords

import android.content.Context
import kotlinx.coroutines.ensureActive
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Spotify's Basic Pitch, on the phone: audio in, notes out, any instrument,
 * several at once. The 200 KB model (Apache 2.0) runs in LiteRT; around it, a
 * faithful port of the package's windowing and note creation (onsets, the
 * "melodia trick"), then AIScoreTranscriber's clean-up of fragmented notes and
 * octave ghosts. Brought as it was from the LauncherAI.
 */
object BasicPitch {
    const val RATE = 22_050
    private const val HOP = 256
    private const val WINDOW = RATE * 2 - HOP // 43 844 samples
    private const val ANNOT_FPS = RATE / HOP // 86
    private const val ANNOT_FRAMES = ANNOT_FPS * 2 // 172
    private const val OVERLAP_FRAMES = 30
    private const val OVERLAP = OVERLAP_FRAMES * HOP
    private const val STEP = WINDOW - OVERLAP
    private const val KEYS = 88
    private const val MIDI_OFFSET = 21

    data class Event(val start: Int, val end: Int, val pitch: Int, val amplitude: Float)

    /** What to listen for: how sure a note must be, and in which range. */
    data class Options(
        val onsetThreshold: Float = 0.5f,
        val frameThreshold: Float = 0.3f,
        val minNoteMs: Float = 127.7f,
        val minHz: Float? = null,
        val maxHz: Float? = null,
    ) {
        companion object {
            /** AIScoreTranscriber's instrument ranges. */
            fun forInstrument(name: String): Options = when (name.lowercase()) {
                "voice", "vocals", "voz" -> Options(minHz = 75f, maxHz = 1200f, minNoteMs = 130f)
                "bass", "baixo" -> Options(minHz = 30f, maxHz = 400f, minNoteMs = 130f)
                "guitar", "violão", "violao", "guitarra" -> Options(minHz = 73f, maxHz = 1400f, minNoteMs = 130f)
                "piano" -> Options(minHz = 27.5f, maxHz = 4186f, minNoteMs = 130f)
                else -> Options(minNoteMs = 130f)
            }
        }
    }

    // ------------------------------------------------------------ windows --

    /** The audio, half an overlap of silence in front, cut into model-sized windows. */
    internal fun windows(audio: FloatArray): List<FloatArray> {
        val padded = FloatArray(OVERLAP / 2 + audio.size)
        System.arraycopy(audio, 0, padded, OVERLAP / 2, audio.size)
        val out = mutableListOf<FloatArray>()
        var i = 0
        while (i < padded.size) {
            val w = FloatArray(WINDOW)
            System.arraycopy(padded, i, w, 0, minOf(WINDOW, padded.size - i))
            out += w
            i += STEP
        }
        return out
    }

    /** Window outputs joined: half the overlap dropped at each edge, trimmed to the audio's length. */
    internal fun unwrap(parts: List<Array<FloatArray>>, originalLength: Int): Array<FloatArray> {
        val olap = OVERLAP_FRAMES / 2
        val rows = parts.flatMap { p -> p.toList().subList(olap, p.size - olap) }
        val n = floor(originalLength * (ANNOT_FPS.toDouble() / RATE)).toInt()
        return rows.take(n).toTypedArray()
    }

    /** Seconds at each frame, with the model's own per-window correction. */
    internal fun frameTime(frame: Int): Double {
        val original = frame.toDouble() * HOP / RATE
        val windowNumber = floor(frame.toDouble() / ANNOT_FRAMES)
        val offset = (HOP.toDouble() / RATE) * (ANNOT_FRAMES - WINDOW.toDouble() / HOP) + 0.0018
        return original - offset * windowNumber
    }

    // -------------------------------------------------------------- notes --

    private fun hzToIndex(hz: Float) = (12 * (ln(hz / 440.0) / ln(2.0)) + 69 - MIDI_OFFSET).roundToInt()

    /** basic_pitch.note_creation.output_to_notes_polyphonic, line for line. */
    internal fun notes(
        framesIn: Array<FloatArray>,
        onsetsIn: Array<FloatArray>,
        options: Options,
        minNoteLen: Int,
        energyTol: Int = 11,
    ): List<Event> {
        val n = framesIn.size
        if (n == 0) return emptyList()
        val frames = Array(n) { framesIn[it].copyOf() }
        var onsets = Array(n) { onsetsIn[it].copyOf() }
        options.maxHz?.let { hz -> val idx = hzToIndex(hz).coerceIn(0, KEYS); for (r in 0 until n) for (f in idx until KEYS) { onsets[r][f] = 0f; frames[r][f] = 0f } }
        options.minHz?.let { hz -> val idx = hzToIndex(hz).coerceIn(0, KEYS); for (r in 0 until n) for (f in 0 until idx) { onsets[r][f] = 0f; frames[r][f] = 0f } }

        onsets = inferredOnsets(onsets, frames)

        // Local maxima in time, per key.
        data class Onset(val t: Int, val f: Int)
        val found = mutableListOf<Onset>()
        for (t in 1 until n - 1) for (f in 0 until KEYS) {
            val v = onsets[t][f]
            if (v > onsets[t - 1][f] && v > onsets[t + 1][f] && v >= options.onsetThreshold) found += Onset(t, f)
        }
        // Backwards in time, as numpy's where()[::-1] reads them.
        found.sortWith(compareBy<Onset>({ it.t }, { it.f }))
        found.reverse()

        val remaining = Array(n) { frames[it].copyOf() }
        val events = mutableListOf<Event>()
        for ((start, f) in found) {
            if (start >= n - 1) continue
            var i = start + 1
            var k = 0
            while (i < n - 1 && k < energyTol) {
                if (remaining[i][f] < options.frameThreshold) k++ else k = 0
                i++
            }
            i -= k
            if (i - start <= minNoteLen) continue
            for (r in start until i) {
                remaining[r][f] = 0f
                if (f < KEYS - 1) remaining[r][f + 1] = 0f
                if (f > 0) remaining[r][f - 1] = 0f
            }
            events += Event(start, i, f + MIDI_OFFSET, mean(frames, start, i, f))
        }

        // The melodia trick: notes whose onset was missed, grown from the
        // strongest remaining energy outwards.
        //
        // The reference rescans the whole matrix for its maximum on every note,
        // which on a phone is minutes for a song. Energy only ever drops (it is
        // zeroed, never raised), so a heap of what is above the threshold,
        // checked against the matrix when popped, picks the same cell in the
        // same order — ties first in time, then lowest key, as argmax does.
        val heap = java.util.PriorityQueue<Long>(compareBy<Long> { -Float.fromBits((it ushr 32).toInt()) }.thenBy { it and 0xFFFFFFFFL })
        for (t in 0 until n) for (f in 0 until KEYS) {
            val v = remaining[t][f]
            if (v > options.frameThreshold) heap += (v.toRawBits().toLong() shl 32) or (t.toLong() * KEYS + f)
        }
        while (true) {
            val top = heap.poll() ?: break
            val cell = (top and 0xFFFFFFFFL).toInt()
            val bt = cell / KEYS
            val bf = cell % KEYS
            if (remaining[bt][bf] != Float.fromBits((top ushr 32).toInt())) continue
            remaining[bt][bf] = 0f
            var i = bt + 1
            var k = 0
            while (i < n - 1 && k < energyTol) {
                if (remaining[i][bf] < options.frameThreshold) k++ else k = 0
                remaining[i][bf] = 0f
                if (bf < KEYS - 1) remaining[i][bf + 1] = 0f
                if (bf > 0) remaining[i][bf - 1] = 0f
                i++
            }
            val end = i - 1 - k
            i = bt - 1
            k = 0
            while (i > 0 && k < energyTol) {
                if (remaining[i][bf] < options.frameThreshold) k++ else k = 0
                remaining[i][bf] = 0f
                if (bf < KEYS - 1) remaining[i][bf + 1] = 0f
                if (bf > 0) remaining[i][bf - 1] = 0f
                i--
            }
            val start = i + 1 + k
            if (end - start <= minNoteLen) continue
            events += Event(start, end, bf + MIDI_OFFSET, mean(frames, start, end, bf))
        }
        return events
    }

    private fun mean(frames: Array<FloatArray>, from: Int, to: Int, f: Int): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (r in from until to) s += frames[r][f]
        return (s / (to - from)).toFloat()
    }

    private fun inferredOnsets(onsets: Array<FloatArray>, frames: Array<FloatArray>, nDiff: Int = 2): Array<FloatArray> {
        val n = frames.size
        val diff = Array(n) { FloatArray(KEYS) }
        var maxDiff = 0f
        var maxOnset = 0f
        for (t in 0 until n) for (f in 0 until KEYS) {
            var m = Float.MAX_VALUE
            for (d in 1..nDiff) {
                val prev = if (t - d >= 0) frames[t - d][f] else 0f
                m = minOf(m, frames[t][f] - prev)
            }
            val v = if (t < nDiff || m < 0) 0f else m
            diff[t][f] = v
            if (v > maxDiff) maxDiff = v
            if (onsets[t][f] > maxOnset) maxOnset = onsets[t][f]
        }
        return Array(n) { t ->
            FloatArray(KEYS) { f ->
                val scaled = if (maxDiff > 0) maxOnset * diff[t][f] / maxDiff else 0f
                maxOf(onsets[t][f], scaled)
            }
        }
    }

    // ---------------------------------------------------------- clean up --

    /** AIScoreTranscriber's clean-up: fragments joined, octave ghosts dropped, crumbs swept. */
    internal fun clean(notes: List<Note>, minMs: Long): List<Note> {
        val merged = notes.groupBy { it.pitch }.flatMap { (_, group) ->
            val sorted = group.sortedBy { it.startMs }
            val out = mutableListOf<Note>()
            var cur = sorted.first()
            for (next in sorted.drop(1)) {
                val curEnd = cur.startMs + cur.durationMs
                if (next.startMs - curEnd <= 45) {
                    val end = maxOf(curEnd, next.startMs + next.durationMs)
                    cur = cur.copy(durationMs = end - cur.startMs, velocity = maxOf(cur.velocity, next.velocity))
                } else {
                    out += cur
                    cur = next
                }
            }
            out += cur
            out
        }.sortedWith(compareBy({ it.startMs }, { it.pitch }))

        val drop = mutableSetOf<Int>()
        for (i in merged.indices) {
            val a = merged[i]
            for (j in i + 1 until merged.size) {
                val b = merged[j]
                if (b.startMs > a.startMs + a.durationMs) break
                if (kotlin.math.abs(b.pitch - a.pitch) != 12) continue
                val overlap = minOf(a.startMs + a.durationMs, b.startMs + b.durationMs) - maxOf(a.startMs, b.startMs)
                val shortest = minOf(a.durationMs, b.durationMs)
                if (shortest <= 0 || overlap.toDouble() / shortest < 0.7) continue
                if (b.velocity < a.velocity * 0.35) drop += j else if (a.velocity < b.velocity * 0.35) drop += i
            }
        }
        return merged.filterIndexed { i, n -> i !in drop && n.durationMs >= minMs * 0.6 }
    }

    // --------------------------------------------------------------- model --

    private var interpreter: org.tensorflow.lite.Interpreter? = null

    @Synchronized
    private fun model(context: Context): org.tensorflow.lite.Interpreter = interpreter ?: run {
        val fd = context.assets.openFd("chords/nmp.tflite")
        val buffer = FileInputStream(fd.fileDescriptor).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        // Two threads: it runs while the music plays and the lyrics move.
        org.tensorflow.lite.Interpreter(buffer, org.tensorflow.lite.Interpreter.Options().setNumThreads(2)).also { interpreter = it }
    }

    /**
     * The notes in [audio] (mono, [RATE] Hz, −1…1). [progress] goes 0 → 1
     * across the windows; a two-minute song is a few seconds on a phone.
     * Runs on the caller's dispatcher (the chords' own background thread).
     */
    suspend fun transcribe(
        context: Context,
        audio: FloatArray,
        options: Options = Options(),
        progress: (Float) -> Unit = {},
    ): List<Note> = run {
        val model = model(context)
        val windows = windows(audio)
        val noteParts = ArrayList<Array<FloatArray>>(windows.size)
        val onsetParts = ArrayList<Array<FloatArray>>(windows.size)
        val input = ByteBuffer.allocateDirect(WINDOW * 4).order(ByteOrder.nativeOrder())
        windows.forEachIndexed { w, window ->
            coroutineContext.ensureActive()
            input.rewind()
            for (v in window) input.putFloat(v)
            input.rewind()
            val note = Array(1) { Array(ANNOT_FRAMES) { FloatArray(KEYS) } }
            val onset = Array(1) { Array(ANNOT_FRAMES) { FloatArray(KEYS) } }
            val contour = Array(1) { Array(ANNOT_FRAMES) { FloatArray(KEYS * 3) } }
            synchronized(model) {
                model.runSignature(
                    mapOf("input_2" to input),
                    mapOf<String, Any>("note" to note, "onset" to onset, "contour" to contour),
                    "serving_default",
                )
            }
            noteParts += note[0]
            onsetParts += onset[0]
            progress((w + 1f) / windows.size)
        }
        val frames = unwrap(noteParts, audio.size)
        val onsets = unwrap(onsetParts, audio.size)
        eventsToNotes(notes(frames, onsets, options, minLen(options)), options)
    }

    internal fun minLen(options: Options) = (options.minNoteMs / 1000 * (RATE.toFloat() / HOP)).roundToInt()

    internal fun eventsToNotes(events: List<Event>, options: Options): List<Note> {
        val raw = events.map { e ->
            val start = (frameTime(e.start) * 1000).toLong().coerceAtLeast(0)
            val end = (frameTime(e.end) * 1000).toLong()
            Note(e.pitch, start, (end - start).coerceAtLeast(60), (e.amplitude * 127).roundToInt().coerceIn(30, 127))
        }
        return clean(raw, options.minNoteMs.toLong())
    }
}
