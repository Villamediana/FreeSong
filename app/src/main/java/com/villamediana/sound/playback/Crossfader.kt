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
import com.villamediana.sound.playback.crossfade.FadeShape
import com.villamediana.sound.playback.crossfade.FadeShaper
import com.villamediana.sound.playback.crossfade.Mix
import com.villamediana.sound.playback.crossfade.Mixing
import com.villamediana.sound.playback.crossfade.TailSync
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the crossfade does at a given moment of a song (pure: tested on its own). */
object CrossfadePlan {
    /** The end of the song and the start of the next are measured from this long before the fade. */
    const val MEASURE_AHEAD_MS = 45_000L

    /**
     * The tail player rolls this long before the fade: silent, kept on the same moment of the song
     * as the main player ([TailSync]), so it takes the song over at the fade without a gap.
     */
    const val ROLL_AHEAD_MS = 6_000L

    /** The tail player is readied this long before the fade, so it is ready when its roll comes. */
    const val PREPARE_AHEAD_MS = 8_000L

    /** Less than this before the fade, the tail can't be started in time: no fade, the song just ends. */
    const val MIN_ROLL_MS = 500L

    /** The tail is told to play this much before its moment: about what it takes to sound. */
    const val ROLL_EARLY_MS = 100L

    /** The main player moves on once the handover is behind it (from there it is silent). */
    const val START_AFTER_MS = FadeShape.HANDOVER_US / 1_000 + 40

    /** The tail player is ready for [mix], paused at [fromMs] of the song, or [rolling] already. */
    data class Ready(val mix: Mix, val fromMs: Long, val rolling: Boolean)

    sealed interface Step {
        /** Nothing to do (off, no next song, too short, sleep timer, waiting…). */
        data object Idle : Step

        /** Measure the end of this song and the start of the next ([Mixing.mix]). */
        data object Measure : Step

        /** Ready the tail player before the fade, paused. */
        data class Prepare(val mix: Mix) : Step

        /** Start the tail rolling (silent) and hand the song over to it at the fade. */
        data object Roll : Step

        /**
         * The song is the tail's now: the main player moves on to the next song. On "repeat one"
         * ([loop]) the song that comes in is the same one, from its top.
         */
        data class Start(val loop: Boolean = false) : Step

        /** A roll that shouldn't go on (crossfade turned off, sleep timer, nothing next): let it go. */
        data object Cancel : Step
    }

    /**
     * [ready] is the tail player's state (null: not ready); [measuring] says the measure was asked
     * for and [measured] is what came of it (null while it runs).
     */
    fun step(
        seconds: Int,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        hasNext: Boolean,
        repeatOne: Boolean,
        blocked: Boolean,
        ready: Ready?,
        measuring: Boolean,
        measured: Mix?,
    ): Step {
        // On "repeat one" the song follows itself, whatever comes after it in the queue.
        val on = seconds > 0 && (hasNext || repeatOne) && !blocked
        if (ready?.rolling == true && !on) return Step.Cancel
        if (!on || !playing) return Step.Idle
        val fade = seconds * 1_000L
        // A song shorter than a few fades plays whole.
        if (durationMs < fade * 3) return if (ready?.rolling == true) Step.Cancel else Step.Idle
        if (ready != null) {
            val handover = ready.mix.fadeStartMs
            return when {
                // Silent on the main player from the handover: moving on now is never heard.
                ready.rolling -> if (positionMs >= handover + START_AFTER_MS) Step.Start(loop = repeatOne) else Step.Idle
                positionMs >= ready.fromMs - ROLL_EARLY_MS && positionMs < handover - MIN_ROLL_MS -> Step.Roll
                // Past its moment (a seek, a pause right at it): no fade, the song just ends.
                else -> Step.Idle
            }
        }
        if (durationMs - positionMs <= 300) return Step.Idle
        val plain = Mixing.plain(fade, durationMs)
        if (!measuring) return if (positionMs >= plain.fadeStartMs - MEASURE_AHEAD_MS) Step.Measure else Step.Idle
        // Still measuring at the last moment (a network that crawls): the fade goes as it is.
        val mix = measured ?: if (positionMs >= plain.fadeStartMs - PREPARE_AHEAD_MS) plain else return Step.Idle
        // Not readied in time (a seek to the very end): no fade, the song just ends.
        return if (positionMs >= mix.fadeStartMs - PREPARE_AHEAD_MS && positionMs < mix.fadeStartMs - MIN_ROLL_MS) Step.Prepare(mix) else Step.Idle
    }

    /** Where the tail waits: [ROLL_AHEAD_MS] before the fade, or a little ahead of a song already past that. */
    fun tailFromMs(mix: Mix, positionMs: Long): Long {
        val handover = mix.fadeStartMs
        return max(handover - ROLL_AHEAD_MS, min(positionMs + 600, handover - MIN_ROLL_MS)).coerceAtLeast(0)
    }
}

/**
 * Crossfade for the music player. Some 45 s before the end, the end of the song and the start of
 * the next are measured ([CrossfadeMeasure]): where each one's sound really ends and starts, and
 * how loud each is there ([Mix]). A second, "tail" player then plays the last seconds of the song
 * while the main player (the one the session, notification and app follow) already moves on to
 * the next song. Volume and bass of both follow the mix inside their own audio ([FadeShaper]).
 * Both play into the same audio session, so the equalizer applies to both.
 *
 * The song passes from one player to the other without a gap: the tail starts some seconds before
 * the fade, silent, and is kept on the very moment the main player is at ([TailSync]); at the fade's
 * start the main player goes quiet and the tail comes up, both by the position in their own audio
 * ([FadeShape.Role.LEAVE]), and only then does the main player move on. A skip, seek or pause
 * before or during the fade ends it at once.
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
    /** Where the tail waits, paused (it moves on when a roll is stopped). */
    private var tailFrom = 0L

    private var rolling = false
    private val sync = TailSync()

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
            stopRoll()
            endFade(interrupted = true)
            dropTail()
            forgetMeasure()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (ourSkip) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SKIP) {
                stopRoll()
                endFade(interrupted = true)
                // A seek inside the same song keeps the tail; it rolls again at its moment.
                if (newPosition.mediaItemIndex != oldPosition.mediaItemIndex) {
                    dropTail()
                    forgetMeasure()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                ensureTicking()
            } else {
                // Paused or loading: the tail waits where it is and rolls again (and is set right) after.
                stopRoll()
                if (fading && player.playbackState != Player.STATE_BUFFERING) endFade(interrupted = true)
            }
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
        stopRoll()
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
            ready = prepared?.let { CrossfadePlan.Ready(it, tailFrom, rolling) },
            measuring = measureKey != null,
            measured = measured,
        )
        when (step) {
            CrossfadePlan.Step.Idle -> Unit
            CrossfadePlan.Step.Measure -> if (next != null && key != null) startMeasure(current, duration, next, fade, key)
            is CrossfadePlan.Step.Prepare -> prepare(current, step.mix, position)
            CrossfadePlan.Step.Roll -> roll()
            is CrossfadePlan.Step.Start -> begin(step.loop)
            CrossfadePlan.Step.Cancel -> {
                stopRoll()
                dropTail()
            }
        }
        if (fading) return FADE_WATCH_MS
        val ready = preparedMix?.takeIf { preparedFor == current.mediaId } ?: return WATCH_MS
        if (rolling) return keepInStep(ready, position)
        // Ready and close to the roll: wake up right at it, not up to a watch later.
        val until = tailFrom - CrossfadePlan.ROLL_EARLY_MS - position
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

    private fun prepare(item: MediaItem, mix: Mix, positionMs: Long) {
        dropTail()
        val t = runCatching { newTail() }.getOrNull() ?: return
        val from = CrossfadePlan.tailFromMs(mix, positionMs)
        // Shaped before any of its audio is made: silent until the handover.
        t.shaper.hold(mix.outgoing())
        t.player.setAudioSessionId(player.audioSessionId)
        t.player.volume = 1f
        t.player.setMediaItem(item, from)
        t.player.playWhenReady = false
        t.player.prepare()
        tail = t
        tailFrom = from
        preparedFor = item.mediaId
        preparedMix = mix
        // A plain mix (nothing measured) holds whatever comes next.
        preparedNext = if (mix == measured) measureNext else null
    }

    /** The tail starts, silent; the main player will go quiet at the handover, by its own audio. */
    private fun roll() {
        val t = tail ?: return
        val mix = preparedMix ?: return
        // Far from where the main player is (it came in late): near it, the rest is [TailSync]'s.
        val pos = player.currentPosition
        if (abs(t.player.currentPosition - pos) > TailSync.JUMP_OVER_MS) t.player.seekTo(pos + TailSync.JUMP_LEAD_MS)
        t.player.setPlaybackSpeed(1f)
        t.player.play()
        shaper.leave(mix.leaving())
        rolling = true
        sync.start(SystemClock.elapsedRealtime())
    }

    /** While rolling: keeps the tail on the main player's moment; returns when to look again. */
    private fun keepInStep(mix: Mix, positionMs: Long): Long {
        val t = tail ?: return ROLL_WATCH_MS
        val now = SystemClock.elapsedRealtime()
        val toHandover = mix.fadeStartMs - positionMs
        if (toHandover <= SAFE_MS && !t.player.isPlaying) {
            // The tail isn't sounding (stuck loading): the main player keeps the song, no fade this time.
            stopRoll()
            dropTail()
            return WATCH_MS
        }
        val offset = if (t.player.isPlaying && player.isPlaying) player.currentPosition - t.player.currentPosition else null
        when (val action = sync.step(now, offset, toHandover)) {
            TailSync.Action.None -> Unit
            is TailSync.Action.Speed -> t.player.setPlaybackSpeed(action.speed)
            is TailSync.Action.Jump -> t.player.seekTo(t.player.currentPosition + action.byMs)
        }
        // Right at the start of the main player's move, else often enough to measure and nudge on time.
        val untilStart = toHandover + CrossfadePlan.START_AFTER_MS
        return sync.lookInMs(now, if (untilStart in 1 until ROLL_WATCH_MS) untilStart else ROLL_WATCH_MS)
    }

    /** A roll cut short (pause, seek, loading): the main player keeps the song; the tail waits where it is. */
    private fun stopRoll() {
        if (!rolling) return
        rolling = false
        sync.stop()
        shaper.release()
        tail?.player?.let {
            it.pause()
            it.setPlaybackSpeed(1f)
            tailFrom = it.currentPosition
        }
    }

    private fun begin(loop: Boolean) {
        val t = tail ?: return
        val prepared = preparedMix ?: return
        val nextIndex = if (loop) player.currentMediaItemIndex else player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextId = player.getMediaItemAt(nextIndex).mediaId
        val mix = if (preparedNext == null || preparedNext == nextId) prepared else prepared.withoutIncoming()
        rolling = false
        sync.stop()
        // The tail has the song already (it took it over at the handover); only a changed queue changes its end.
        if (mix != prepared) t.shaper.hold(mix.outgoing())
        t.player.setPlaybackSpeed(1f)
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

        /** While the tail rolls: one position compared per look. */
        const val ROLL_WATCH_MS = 50L

        /** This close to the handover the tail must be sounding, or the main player keeps the song. */
        const val SAFE_MS = 250L

        /** The tail is let go this long after the fade (its audio runs a little behind the clock; it's silent by then). */
        const val TAIL_LINGER_MS = 800L
    }
}
