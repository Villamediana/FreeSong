package com.villamediana.sound.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the crossfade does at a given moment of a song (pure: tested on its own). */
object CrossfadePlan {
    /** The tail player is readied this long before the fade, so it starts without a gap. */
    const val PREPARE_AHEAD_MS = 6_000L

    sealed interface Step {
        /** Nothing to do (off, no next song, too short, sleep timer…). */
        data object Idle : Step

        /** Ready the end of this song in the tail player, paused at [fromMs]. */
        data class Prepare(val fromMs: Long) : Step

        /**
         * Start: the tail plays the end out while the next song comes in, over [fadeMs]. On "repeat
         * one" ([loop]) the song that comes in is the same one, from the top.
         */
        data class Start(val fadeMs: Long, val loop: Boolean = false) : Step
    }

    fun step(
        seconds: Int,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        hasNext: Boolean,
        repeatOne: Boolean,
        blocked: Boolean,
        prepared: Boolean,
    ): Step {
        // On "repeat one" the song follows itself, whatever comes after it in the queue.
        if (seconds <= 0 || !playing || !(hasNext || repeatOne) || blocked) return Step.Idle
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return Step.Idle
        val fade = seconds * 1_000L
        // A song shorter than a few fades plays whole.
        if (durationMs < fade * 3) return Step.Idle
        val remaining = durationMs - positionMs
        return when {
            remaining <= 300 -> Step.Idle
            remaining <= fade -> if (prepared) Step.Start(remaining, loop = repeatOne) else Step.Idle
            remaining <= fade + PREPARE_AHEAD_MS && !prepared -> Step.Prepare(durationMs - fade)
            else -> Step.Idle
        }
    }

    /** Equal-power curve: [t] 0→1 gives (outgoing, incoming) volumes with no dip in loudness. */
    fun gains(t: Float): Pair<Float, Float> {
        val x = t.coerceIn(0f, 1f)
        return cos(x * PI / 2).toFloat() to sin(x * PI / 2).toFloat()
    }
}

/**
 * Crossfade for the music player. A second, quiet "tail" player plays the last seconds of the song
 * while the main player (the one the session, notification and app follow) already moves on to
 * the next song and fades it in. Both play into the same audio session, so the equalizer applies
 * to both. A skip, seek or pause during the fade ends it at once.
 */
@OptIn(UnstableApi::class)
internal class Crossfader(
    private val player: ExoPlayer,
    private val newTail: () -> ExoPlayer,
    private val seconds: () -> Int,
    /** Another feature owns the volume or the end of the song (sleep timer). */
    private val blocked: () -> Boolean,
    /** "Repeat one" went around by the fade (the player itself saw only a seek): a new listen starts. */
    private val onLoop: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tail: ExoPlayer? = null
    private var preparedFor: String? = null
    private var fading = false
    private var fadeStart = 0L
    private var fadeMs = 0L
    private var ourSkip = false

    private var ticking = false

    /** Watches the position only while something plays (or a fade runs): no wake-ups when idle. */
    private val tick = object : Runnable {
        override fun run() {
            if (fading) stepFade() else plan()
            if (fading || player.isPlaying) {
                handler.postDelayed(this, if (fading) FADE_STEP_MS else WATCH_MS)
            } else {
                ticking = false
            }
        }
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private val events = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (ourSkip) return
            // The listener (or the queue) moved on by itself: whatever was prepared is stale.
            endFade()
            dropTail()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (ourSkip) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SKIP) {
                endFade()
                // A seek inside the same song keeps the tail; it re-readies at the right time anyway.
                if (newPosition.mediaItemIndex != oldPosition.mediaItemIndex) dropTail()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) ensureTicking()
            else if (fading && player.playbackState != Player.STATE_BUFFERING) endFade()
        }
    }

    fun start() {
        player.addListener(events)
        if (player.isPlaying) ensureTicking()
    }

    fun release() {
        handler.removeCallbacks(tick)
        ticking = false
        player.removeListener(events)
        endFade()
        dropTail()
    }

    private fun plan() {
        val current = player.currentMediaItem
        val step = CrossfadePlan.step(
            seconds = seconds(),
            durationMs = player.duration,
            positionMs = player.currentPosition,
            playing = player.isPlaying,
            hasNext = player.hasNextMediaItem(),
            repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
            blocked = blocked(),
            prepared = preparedFor != null && preparedFor == current?.mediaId,
        )
        when (step) {
            CrossfadePlan.Step.Idle -> Unit
            is CrossfadePlan.Step.Prepare -> prepare(current ?: return, step.fromMs)
            is CrossfadePlan.Step.Start -> begin(step.fadeMs, step.loop)
        }
    }

    private fun prepare(item: MediaItem, fromMs: Long) {
        dropTail()
        val t = runCatching { newTail() }.getOrNull() ?: return
        t.setAudioSessionId(player.audioSessionId)
        t.volume = 1f
        t.setMediaItem(item, fromMs)
        t.playWhenReady = false
        t.prepare()
        tail = t
        preparedFor = item.mediaId
    }

    private fun begin(remainingMs: Long, loop: Boolean) {
        val t = tail ?: return
        // The tail picks up exactly where the main player is.
        val pos = player.currentPosition
        if (kotlin.math.abs(t.currentPosition - pos) > 120) t.seekTo(pos)
        t.volume = 1f
        t.play()
        fading = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeMs = remainingMs.coerceAtLeast(500)
        player.volume = 0f
        ourSkip = true
        try {
            // "Next" ignores "repeat one" (it would leave the song): the loop goes back to the top.
            if (loop) player.seekTo(player.currentMediaItemIndex, 0) else player.seekToNextMediaItem()
        } finally {
            ourSkip = false
        }
        preparedFor = null
        if (loop) onLoop()
    }

    private fun stepFade() {
        val t = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
        val (out, `in`) = CrossfadePlan.gains(t)
        tail?.volume = out
        player.volume = `in`
        if (t >= 1f) {
            endFade()
            dropTail()
        }
    }

    private fun endFade() {
        if (!fading) return
        fading = false
        player.volume = 1f
        tail?.pause()
    }

    private fun dropTail() {
        tail?.let { runCatching { it.stop(); it.release() } }
        tail = null
        preparedFor = null
    }

    private companion object {
        const val WATCH_MS = 250L
        const val FADE_STEP_MS = 40L
    }
}
