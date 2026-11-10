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
import com.villamediana.sound.playback.crossfade.FadeShaper
import com.villamediana.sound.playback.crossfade.Mix
import com.villamediana.sound.playback.crossfade.Mixing

/** What the crossfade does at a given moment of a song (pure: tested on its own). */
object CrossfadePlan {
    /** The tail player is readied this long before the fade, so it starts without a gap. */
    const val PREPARE_AHEAD_MS = 6_000L

    /** The end of the song and the start of the next are measured from this long before the fade. */
    const val MEASURE_AHEAD_MS = 45_000L

    sealed interface Step {
        /** Nothing to do (off, no next song, too short, sleep timer, waiting…). */
        data object Idle : Step

        /** Measure the end of this song and the start of the next ([Mixing.mix]). */
        data object Measure : Step

        /** Ready the tail player at the fade's start, paused. */
        data class Prepare(val mix: Mix) : Step

        /**
         * Start: the tail plays the end out while the next song comes in. On "repeat one" ([loop])
         * the song that comes in is the same one, from its top.
         */
        data class Start(val loop: Boolean = false) : Step
    }

    /**
     * [prepared] is the mix the tail player is ready for (null: not ready); [measuring] says the
     * measure was asked for and [measured] is what came of it (null while it runs).
     */
    fun step(
        seconds: Int,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        hasNext: Boolean,
        repeatOne: Boolean,
        blocked: Boolean,
        prepared: Mix?,
        measuring: Boolean,
        measured: Mix?,
    ): Step {
        // On "repeat one" the song follows itself, whatever comes after it in the queue.
        if (seconds <= 0 || !playing || !(hasNext || repeatOne) || blocked) return Step.Idle
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return Step.Idle
        val fade = seconds * 1_000L
        // A song shorter than a few fades plays whole.
        if (durationMs < fade * 3) return Step.Idle
        if (durationMs - positionMs <= 300) return Step.Idle
        if (prepared != null) {
            // At its moment; never once half of it would be gone (a seek into the fade): the song just ends.
            val start = prepared.fadeStartMs
            return if (positionMs >= start && positionMs < start + prepared.fadeMs / 2) Step.Start(loop = repeatOne) else Step.Idle
        }
        val plain = Mixing.plain(fade, durationMs)
        if (!measuring) return if (positionMs >= plain.fadeStartMs - MEASURE_AHEAD_MS) Step.Measure else Step.Idle
        // Still measuring at the last moment (a network that crawls): the fade goes as it is.
        val mix = measured ?: if (positionMs >= plain.fadeStartMs - PREPARE_AHEAD_MS) plain else return Step.Idle
        // Not readied in time (a seek to the very end): no fade, the song just ends.
        return if (positionMs >= mix.fadeStartMs - PREPARE_AHEAD_MS && positionMs < mix.fadeStartMs) Step.Prepare(mix) else Step.Idle
    }
}

/**
 * Crossfade for the music player. Some 45 s before the end, the end of the song and the start of
 * the next are measured ([CrossfadeMeasure]): where each one's sound really ends and starts, and
 * how loud each is there ([Mix]). A second, "tail" player then plays the last seconds of the song
 * while the main player (the one the session, notification and app follow) already moves on to
 * the next song. Volume and bass of both follow the mix inside their own audio ([FadeShaper]).
 * Both play into the same audio session, so the equalizer applies to both. A skip, seek or pause
 * during the fade ends it at once.
 */
@OptIn(UnstableApi::class)
internal class Crossfader(
    private val player: ExoPlayer,
    /** The shaper in the main player's audio. */
    private val shaper: FadeShaper,
    private val newTail: () -> Tail,
    private val measure: Measure,
    private val seconds: () -> Int,
    /** Another feature owns the volume or the end of the song (sleep timer). */
    private val blocked: () -> Boolean,
    /** "Repeat one" went around by the fade (the player itself saw only a seek): a new listen starts. */
    private val onLoop: () -> Unit = {},
) {
    /** A tail player and the shaper in its audio. */
    class Tail(val player: ExoPlayer, val shaper: FadeShaper)

    /** Measures a crossfade off the main thread; [done] comes on the main thread. Returns a cancel. */
    fun interface Measure {
        fun start(current: MediaItem, durationMs: Long, next: MediaItem, fadeMs: Long, done: (Mix) -> Unit): () -> Unit
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tail: Tail? = null
    private var preparedFor: String? = null
    private var preparedMix: Mix? = null
    /** The next song the prepared mix measured (null: it measured none). */
    private var preparedNext: String? = null

    private var measureKey: String? = null
    private var measureNext: String? = null
    private var measured: Mix? = null
    private var cancelMeasure: (() -> Unit)? = null

    private var fading = false
    private var fadeStart = 0L
    private var fadeMs = 0L
    private var ourSkip = false

    private var ticking = false

    /** Watches the position only while something plays (or a fade runs): no wake-ups when idle. */
    private val tick = object : Runnable {
        override fun run() {
            val delay = if (fading) stepFade() else plan()
            if (fading || player.isPlaying) {
                handler.postDelayed(this, delay)
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
            endFade(interrupted = true)
            dropTail()
            forgetMeasure()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (ourSkip) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SKIP) {
                endFade(interrupted = true)
                // A seek inside the same song keeps the tail; it re-readies at the right time anyway.
                if (newPosition.mediaItemIndex != oldPosition.mediaItemIndex) {
                    dropTail()
                    forgetMeasure()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) ensureTicking()
            else if (fading && player.playbackState != Player.STATE_BUFFERING) endFade(interrupted = true)
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
        endFade(interrupted = true)
        dropTail()
        forgetMeasure()
    }

    /** One look at the song; returns when to look again. */
    private fun plan(): Long {
        val current = player.currentMediaItem ?: return WATCH_MS
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        val nextIndex = player.nextMediaItemIndex
        val next = if (repeatOne) current else if (nextIndex != C.INDEX_UNSET) player.getMediaItemAt(nextIndex) else null
        val fade = seconds() * 1_000L
        val duration = player.duration
        val prepared = preparedMix?.takeIf { preparedFor != null && preparedFor == current.mediaId }
        // The queue or the setting changed since the measure: measure again (the ready tail stays).
        val key = next?.let { "${current.mediaId}|${it.mediaId}|$fade|$duration" }
        if (prepared == null && key != measureKey) forgetMeasure()
        val position = player.currentPosition
        val step = CrossfadePlan.step(
            seconds = seconds(),
            durationMs = duration,
            positionMs = position,
            playing = player.isPlaying,
            hasNext = player.hasNextMediaItem(),
            repeatOne = repeatOne,
            blocked = blocked(),
            prepared = prepared,
            measuring = measureKey != null,
            measured = measured,
        )
        when (step) {
            CrossfadePlan.Step.Idle -> Unit
            CrossfadePlan.Step.Measure -> if (next != null && key != null) startMeasure(current, duration, next, fade, key)
            is CrossfadePlan.Step.Prepare -> prepare(current, step.mix)
            is CrossfadePlan.Step.Start -> begin(step.loop)
        }
        // Ready and close to the fade: wake up right at it, not up to a watch later.
        val ready = preparedMix?.takeIf { preparedFor == current.mediaId } ?: return WATCH_MS
        val until = ready.fadeStartMs - position
        return if (until in 1 until WATCH_MS) until else WATCH_MS
    }

    private fun startMeasure(current: MediaItem, durationMs: Long, next: MediaItem, fadeMs: Long, key: String) {
        forgetMeasure()
        measureKey = key
        measureNext = next.mediaId
        cancelMeasure = measure.start(current, durationMs, next, fadeMs) { mix ->
            if (measureKey == key) measured = mix
        }
    }

    private fun forgetMeasure() {
        cancelMeasure?.invoke()
        cancelMeasure = null
        measureKey = null
        measureNext = null
        measured = null
    }

    private fun prepare(item: MediaItem, mix: Mix) {
        dropTail()
        val t = runCatching { newTail() }.getOrNull() ?: return
        t.player.setAudioSessionId(player.audioSessionId)
        t.player.volume = 1f
        t.player.setMediaItem(item, mix.fadeStartMs)
        t.player.playWhenReady = false
        t.player.prepare()
        tail = t
        preparedFor = item.mediaId
        preparedMix = mix
        // A plain mix (nothing measured) holds whatever comes next.
        preparedNext = if (mix == measured) measureNext else null
    }

    private fun begin(loop: Boolean) {
        val t = tail ?: return
        val prepared = preparedMix ?: return
        val nextIndex = if (loop) player.currentMediaItemIndex else player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextId = player.getMediaItemAt(nextIndex).mediaId
        val mix = if (preparedNext == null || preparedNext == nextId) prepared else prepared.withoutIncoming()
        // The tail picks up exactly where the main player is.
        val pos = player.currentPosition
        if (kotlin.math.abs(t.player.currentPosition - pos) > 120) t.player.seekTo(pos)
        t.shaper.hold(mix.outgoing())
        t.player.volume = 1f
        t.player.play()
        fading = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeMs = mix.fadeMs
        player.volume = 1f
        shaper.arm(mix.incoming())
        ourSkip = true
        try {
            // "Next" ignores "repeat one" (it would leave the song): the loop goes back to the top.
            player.seekTo(nextIndex, mix.inStartMs)
        } finally {
            ourSkip = false
        }
        preparedFor = null
        preparedMix = null
        preparedNext = null
        forgetMeasure()
        if (loop) onLoop()
    }

    /** The fade runs inside the audio; this only lets the tail go once it's over. */
    private fun stepFade(): Long {
        val elapsed = SystemClock.elapsedRealtime() - fadeStart
        if (elapsed >= fadeMs + TAIL_LINGER_MS) {
            endFade(interrupted = false)
            dropTail()
            return WATCH_MS
        }
        return FADE_WATCH_MS
    }

    /** [interrupted]: a pause, seek or skip cut the fade short, so the main player goes plain at once. */
    private fun endFade(interrupted: Boolean) {
        if (!fading) return
        fading = false
        if (interrupted) shaper.release()
        tail?.player?.pause()
    }

    private fun dropTail() {
        tail?.let { runCatching { it.player.stop(); it.player.release() } }
        tail = null
        preparedFor = null
        preparedMix = null
        preparedNext = null
    }

    private companion object {
        const val WATCH_MS = 250L
        const val FADE_WATCH_MS = 200L

        /** The tail is let go this long after the fade (its audio runs a little behind the clock; it's silent by then). */
        const val TAIL_LINGER_MS = 800L
    }
}
