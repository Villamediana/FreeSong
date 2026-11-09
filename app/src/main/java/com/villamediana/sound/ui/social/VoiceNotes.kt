package com.villamediana.sound.ui.social

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records a voice message: AAC in MP4, mono, 16 kHz at 24 kbps (five minutes ≈ 0,9 MB), reading the
 * loudness every [TICK_MS] for the waves. One recording at a time.
 */
class VoiceRecorder(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val samples = ArrayList<Int>()

    /** Loudness right now, 0..1 (for the pulse while recording). */
    var level by mutableFloatStateOf(0f)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    val recording: Boolean get() = recorder != null

    private var ticker: Job? = null

    /** Starts recording (false when the microphone can't be had: another app holds it). */
    fun start(scope: CoroutineScope, onLimit: () -> Unit): Boolean {
        cancel()
        val out = File(context.cacheDir, "voice-rec-${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_MS.toInt())
            r.setOutputFile(out.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            // The music steps aside while you speak (it would be in the message) and comes back after.
            takeFocus()
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            samples.clear()
            elapsedMs = 0
            ticker = scope.launch(Dispatchers.Main) {
                while (isActive && recorder != null) {
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    samples += amp
                    level = (amp / 18_000f).coerceIn(0f, 1f)
                    elapsedMs = System.currentTimeMillis() - startedAt
                    delay(TICK_MS)
                }
            }
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            giveFocus()
            false
        }
    }

    private fun takeFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun giveFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Stops and hands the recording over: the file, its length and its waves; null if it was too short or failed. */
    fun finish(): Recorded? {
        val r = recorder ?: return null
        val out = file
        val ms = System.currentTimeMillis() - startedAt
        stopTicker()
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        // The music comes back only once the microphone is closed.
        giveFocus()
        if (!ok || out == null || ms < MIN_MS || !out.isFile || out.length() < 64) {
            out?.delete()
            return null
        }
        return Recorded(out, ms.coerceAtMost(MAX_MS), com.villamediana.sound.social.VoiceWave.encode(samples.toList()))
    }

    /** Throws the recording away. */
    fun cancel() {
        val r = recorder ?: return
        stopTicker()
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        giveFocus()
        file?.delete()
        file = null
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        level = 0f
    }

    data class Recorded(val file: File, val ms: Long, val wave: String)

    companion object {
        const val MAX_MS = 5 * 60_000L
        /** Shorter than this was a tap, not a message. */
        const val MIN_MS = 700L
        private const val TICK_MS = 80L
    }
}

/**
 * Plays the conversation's voice messages, one at a time. The music steps aside while it speaks
 * (a short audio focus: the player pauses and comes back by itself, in a jam too).
 */
class VoicePlayer(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var ticker: Job? = null

    /** The message playing (its row id), or null. */
    var current by mutableStateOf<Long?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    /** 0..1 of the message playing (or paused). */
    var progress by mutableFloatStateOf(0f)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /** Plays [key] from [file] (from [fraction] of it), or pauses/resumes it when it is the one already here. */
    fun toggle(key: Long, file: File, fraction: Float? = null) {
        val p = player
        if (p != null && current == key) {
            if (fraction != null) {
                // A tap on the waves jumps there and plays (it never pauses).
                seek(fraction)
                if (!p.isPlaying) resume()
            } else if (p.isPlaying) {
                pause()
            } else {
                resume()
            }
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(ATTRS)
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            return
        }
        mp.setOnCompletionListener { stop() }
        player = mp
        current = key
        if (fraction != null) mp.seekTo((fraction.coerceIn(0f, 1f) * mp.duration).toInt())
        resume()
    }

    fun seek(fraction: Float) {
        val p = player ?: return
        p.seekTo((fraction.coerceIn(0f, 1f) * p.duration).toInt())
        tick()
    }

    private fun resume() {
        val p = player ?: return
        if (!takeFocus()) return
        p.start()
        playing = true
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive && player === p) {
                tick()
                delay(50)
            }
        }
    }

    private fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
        ticker?.cancel()
        giveFocus()
    }

    private fun tick() {
        val p = player ?: return
        val d = p.duration.coerceAtLeast(1)
        positionMs = p.currentPosition.toLong()
        progress = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        current = null
        playing = false
        progress = 0f
        positionMs = 0
        giveFocus()
    }

    fun release() = stop()

    private fun takeFocus(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ATTRS)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }
            .build()
        focus = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun giveFocus() {
        focus?.let { if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
