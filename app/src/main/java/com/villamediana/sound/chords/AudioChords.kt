package com.villamediana.sound.chords

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.playback.stream.AudioCache
import com.villamediana.sound.playback.stream.ChunkedHttpDataSource
import com.villamediana.sound.playback.stream.StreamDataSources
import com.villamediana.sound.playback.stream.StreamResolveException
import com.villamediana.sound.playback.stream.StreamUris
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException

/**
 * Hears a song's chords on the phone: its audio (the file, or the stream read through the same
 * cache the player uses, so a song just heard costs no data), decoded to mono 22 kHz, its beats
 * found, its notes heard by Basic Pitch, and the chords read from those notes on those beats.
 * Nothing leaves the phone.
 */
@OptIn(UnstableApi::class)
internal class AudioChords(
    private val context: Context,
    private val resolver: ChunkedHttpDataSource.UriResolver,
    private val quality: suspend () -> AudioQuality,
) {
    suspend fun analyze(subject: ChordSubject, progress: (Float) -> Unit): ChordChart {
        val context = currentCoroutineContext()
        val active = { context.isActive }
        val (file, temporary) = audioFile(subject, active) { progress(it * 0.22f) }
        try {
            val audio = AudioDecoder.decodeMono(file, BasicPitch.RATE, MAX_MS, active) { progress(0.22f + it * 0.1f) }
            if (audio.size < BasicPitch.RATE * 5) throw ChordFailure(ChordState.Reason.NOTHING_HEARD)
            val beats = BeatTracker.track(audio, active)
            progress(0.36f)
            val notes = BasicPitch.transcribe(this.context, audio, BasicPitch.Options(minNoteMs = 130f)) { progress(0.36f + it * 0.6f) }
            if (notes.size < MIN_NOTES) throw ChordFailure(ChordState.Reason.NOTHING_HEARD)
            val hearing = if (beats.timesMs.size >= MIN_BEATS) ChordHearing.hear(notes, beats.timesMs) else ChordHearing.hear(notes, beats.bpm.toInt())
            progress(1f)
            return ChordChart(
                chords = hearing.chords.map { TimedChord(it.startMs, it.endMs, it.chord.root, it.chord.quality, it.chord.bass) },
                key = hearing.key,
                bpm = beats.bpm,
            )
        } finally {
            if (temporary) file.delete()
        }
    }

    /** The song's audio as a file: its own, or the stream copied out of the player's cache / the network. */
    private suspend fun audioFile(subject: ChordSubject, active: () -> Boolean, progress: (Float) -> Unit): Pair<File, Boolean> {
        subject.path?.let { File(it) }?.takeIf { it.isFile }?.let { return it to false }
        val videoId = subject.videoId ?: throw ChordFailure(ChordState.Reason.ERROR)
        val dir = File(context.cacheDir, "chords-audio").apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() } // what a cancelled hearing left behind
        val out = File(dir, "$videoId.audio")
        val source = StreamDataSources.factory(context, resolver, AudioCache.get(context)).createDataSource()
        try {
            val length = source.open(DataSpec(StreamUris.audio(videoId, quality())))
            out.outputStream().buffered(256 * 1024).use { sink ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    if (!active()) throw kotlinx.coroutines.CancellationException()
                    val n = source.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    sink.write(buf, 0, n)
                    read += n
                    if (length != C.LENGTH_UNSET.toLong() && length > 0) progress((read.toFloat() / length).coerceIn(0f, 1f))
                }
            }
        } catch (e: IOException) {
            out.delete()
            val offline = generateSequence(e as Throwable) { it.cause }.take(8).filterIsInstance<StreamResolveException>().any { it.offline }
            throw ChordFailure(if (offline) ChordState.Reason.OFFLINE else ChordState.Reason.ERROR, e)
        } finally {
            runCatching { source.close() }
        }
        return out to true
    }

    private companion object {
        /** Twelve minutes: past that it is a live set or a mix, not a song to play along to. */
        const val MAX_MS = 12 * 60_000L
        const val MIN_NOTES = 8
        const val MIN_BEATS = 8
    }
}
