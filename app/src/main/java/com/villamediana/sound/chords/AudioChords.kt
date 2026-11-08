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
 * cache the player uses, so a song just heard costs no data), decoded to mono 22 kHz, its
 * constant-Q spectrum ([Cqt]) given to BTC ([Btc]), a chord recognition model, and its frame-by-frame
 * chords read into the ones a player writes ([ChordReading]). Nothing leaves the phone.
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
            val audio = AudioDecoder.decodeMono(file, Cqt.RATE, MAX_MS, active) { progress(0.22f + it * 0.1f) }
            if (audio.size < Cqt.RATE * 5) throw ChordFailure(ChordState.Reason.NOTHING_HEARD)
            // The tempo, for the rows of bars when there are no lyrics.
            val beats = BeatTracker.track(audio, active)
            progress(0.36f)
            val features = Cqt.features(audio, active)
            progress(0.44f)
            val chords = ChordReading.chords(model().logProbs(features, active, { progress(0.44f + it * 0.56f) }))
            progress(1f)
            return ChordChart(chords = chords, key = ChordReading.key(chords), bpm = beats.bpm)
        } finally {
            if (temporary) file.delete()
        }
    }

    /** BTC's weights (5.9 MB, 12 MB in memory): read when a song is first heard, let go if memory runs short. */
    private var btc: java.lang.ref.SoftReference<Btc>? = null

    @Synchronized
    private fun model(): Btc = btc?.get() ?: Btc(java.nio.ByteBuffer.wrap(context.assets.open(MODEL).use { it.readBytes() })).also {
        btc = java.lang.ref.SoftReference(it)
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
        const val MODEL = "chords/btc_voca.bin"
    }
}
