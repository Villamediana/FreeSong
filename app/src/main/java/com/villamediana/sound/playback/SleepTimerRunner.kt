package com.villamediana.sound.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer

/**
 * The sleep timer, living in the service so it keeps counting with the app closed.
 * [SleepTimer.At]: the last [FADE_MS] fade the volume out, then playback pauses and the volume is
 * restored. [SleepTimer.EndOfTrack]: the player pauses when the current song ends.
 */
internal class SleepTimerRunner(
    private val player: ExoPlayer,
    private val onChanged: (SleepTimer) -> Unit,
) {
    var timer: SleepTimer = SleepTimer.Off
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var fading = false
    private val tick = Runnable { onTick() }

    fun set(next: SleepTimer) {
        reset()
        timer = when (next) {
            is SleepTimer.At -> if (next.endsAtElapsedMs <= SystemClock.elapsedRealtime()) SleepTimer.Off else next
            else -> next
        }
        when (timer) {
            is SleepTimer.At -> handler.post(tick)
            SleepTimer.EndOfTrack -> player.pauseAtEndOfMediaItems = true
            SleepTimer.Off -> Unit
        }
        onChanged(timer)
    }

    /** The player paused itself at the end of a song ([ExoPlayer.setPauseAtEndOfMediaItems]). */
    fun onPausedAtEndOfItem() {
        if (timer == SleepTimer.EndOfTrack) finish(pause = false)
    }

    /** Playback reached the end of the queue. */
    fun onEnded() {
        if (timer == SleepTimer.EndOfTrack) finish(pause = false)
    }

    fun release() {
        handler.removeCallbacks(tick)
        if (fading) player.volume = 1f
        fading = false
    }

    private fun onTick() {
        val t = timer as? SleepTimer.At ?: return
        val remaining = t.endsAtElapsedMs - SystemClock.elapsedRealtime()
        when {
            remaining <= 0 -> finish(pause = true)
            remaining <= FADE_MS -> {
                fading = true
                // Ease-out: perceived loudness drops evenly instead of falling off a cliff at the end.
                val x = remaining.toFloat() / FADE_MS
                player.volume = (x * x).coerceIn(0f, 1f)
                handler.postDelayed(tick, FADE_STEP_MS)
            }
            else -> handler.postDelayed(tick, minOf(remaining - FADE_MS, 1_000L))
        }
    }

    private fun finish(pause: Boolean) {
        handler.removeCallbacks(tick)
        // Pause before restoring the volume, so the end never blips back to full loudness.
        if (pause) player.pause()
        reset()
        timer = SleepTimer.Off
        onChanged(timer)
    }

    private fun reset() {
        handler.removeCallbacks(tick)
        if (fading) player.volume = 1f
        fading = false
        if (timer == SleepTimer.EndOfTrack) player.pauseAtEndOfMediaItems = false
    }

    companion object {
        const val FADE_MS = 8_000L
        const val FADE_STEP_MS = 100L
    }
}
