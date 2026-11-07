package com.villamediana.sound.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What listening to the room gave. */
sealed interface Recognition {
    data class Found(val match: SongMatch) : Recognition
    /** Heard, but no song matched. */
    data object NotFound : Recognition
    /** Shazam couldn't be asked (no network) or the microphone didn't open. */
    data class Failed(val noMicrophone: Boolean) : Recognition
}

/** Listens and names the song playing nearby (a fake in tests). */
interface SongListener {
    /** [onLevel] = loudness 0..1 as it comes in, [onSeconds] = seconds heard. */
    suspend fun recognize(onLevel: (Float) -> Unit, onSeconds: (Float) -> Unit): Recognition
}

/**
 * The microphone at 16 kHz mono for up to [MAX_SECONDS]: the fingerprint is made on the phone
 * ([ShazamSignature]) and asked of Shazam a few times as more is heard (at 4.5 s, 8 s and the end),
 * so a clear song is named in seconds and a hard one gets the whole stretch.
 */
class MicSongListener(
    private val identify: suspend (ShortArray) -> Result<SongMatch?> = { SongId.identify(it) },
) : SongListener {
    @SuppressLint("MissingPermission")
    override suspend fun recognize(onLevel: (Float) -> Unit, onSeconds: (Float) -> Unit): Recognition = withContext(Dispatchers.IO) {
        val rate = ShazamSignature.RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            return@withContext Recognition.Failed(noMicrophone = true)
        }
        val heard = ShortArray(rate * MAX_SECONDS)
        val chunk = ShortArray(rate / 10)
        val checkpoints = ArrayDeque(CHECKPOINTS.map { (it * rate).toInt() })
        var count = 0
        var found: SongMatch? = null
        var asked = 0
        var failed = 0
        try {
            rec.startRecording()
            coroutineScope {
                var attempt: Deferred<Result<SongMatch?>>? = null
                fun settle(r: Result<SongMatch?>) {
                    asked++
                    if (r.isFailure) failed++
                    r.getOrNull()?.let { found = it }
                }
                while (count < heard.size && found == null) {
                    ensureActive()
                    val read = rec.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    val take = min(read, heard.size - count)
                    System.arraycopy(chunk, 0, heard, count, take)
                    count += take
                    onLevel(level(chunk, read))
                    onSeconds(count.toFloat() / rate)
                    attempt?.takeIf { it.isCompleted }?.let {
                        settle(it.await())
                        attempt = null
                    }
                    if (attempt == null && found == null && checkpoints.isNotEmpty() && count >= checkpoints.first()) {
                        checkpoints.removeFirst()
                        val snapshot = heard.copyOf(count)
                        attempt = async { identify(snapshot) }
                    }
                }
                rec.stop()
                // The last ask (the whole stretch) is still out: wait for its answer.
                attempt?.let { if (found == null) settle(it.await()) else it.cancel() }
                if (found == null && checkpoints.isNotEmpty() && count > 0) settle(identify(heard.copyOf(count)))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        found?.let { return@withContext Recognition.Found(it) }
        if (asked > 0 && failed == asked) Recognition.Failed(noMicrophone = false) else Recognition.NotFound
    }

    /** Loudness of [n] samples, 0..1 (speech-level sound already moves the rings). */
    private fun level(samples: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return (sqrt(sum / n) * 5).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val MAX_SECONDS = 12
        /** When to ask Shazam (seconds heard). */
        val CHECKPOINTS = listOf(4.5, 8.0, 12.0)
    }
}
