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

/** What the crossfade does at a given moment of a song (pure: tested on its own). */
object CrossfadePlan {
    /** The end of the song and the start of the next are measured from this long before the fade. */
    const val MEASURE_AHEAD_MS = 45_000L

    /**
     * The tail player rolls this long before the fade: silent, kept on the same moment of the song
     * as the main player ([TailSync]), so it takes the song over at the fade without a gap. It
     * doesn't wait for the measure: before it comes, the earliest the fade could be is aimed at.
     */
    const val ROLL_AHEAD_MS = 6_000L

    /** The tail player is readied this long before its roll (a song not in the cache yet loads). */
    const val PREPARE_AHEAD_MS = ROLL_AHEAD_MS + 4_000L

    /** A measure still not in this long before the plain fade: the fade goes as it is. */
    const val PLAIN_AFTER_MS = 6_000L

    /**
     * The fade is set on both players at least this long before it (they make their audio a little
     * ahead of what is heard); also the least time before the fade a tail can start rolling.
     */
    const val APPLY_MARGIN_MS = 1_200L

    /** This long before the fade it is decided ([verdict]): the two players in step, or the fade later. */
    const val DECIDE_MS = 1_200L

    /** Not in step yet: the fade starts this much later (ending where it did)… */
    const val LATER_MS = 2_000L

    /** …as long as what is left of it is no shorter than this. */
    const val MIN_FADE_MS = 2_500L

    /** In step: the last measure no further apart than this. */
    const val IN_STEP_MS = 20L

    /** No time left to wait: handed over this far apart at most (a flam no one hears), else no fade. */
    const val TOLERABLE_MS = 40L

    /** The tail is told to play this much before its moment: about what it takes to sound. */
    const val ROLL_EARLY_MS = 100L

    /** The main player moves on once the handover is behind it (from there it is silent). */
    const val START_AFTER_MS = FadeShape.HANDOVER_US / 1_000 + 40

    /** The tail player, paused at [fromMs] of the song or [rolling]; [applied]: the fade set on it. */
    data class Tail(val fromMs: Long, val rolling: Boolean, val applied: Mix?)

    data class Look(
        val seconds: Int,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val hasNext: Boolean,
        val repeatOne: Boolean,
        val blocked: Boolean,
        /** The measure was asked for; [measured] is what came of it (null while it runs). */
        val measuring: Boolean,
        val measured: Mix?,
        val tail: Tail?,
    )

    sealed interface Step {
        /** Nothing to do (off, no next song, too short, sleep timer, waiting…). */
        data object Idle : Step

        /** Measure the end of this song and the start of the next ([Mixing.mix]). */
        data object Measure : Step

        /** Ready the tail player paused at [fromMs], silent. */
        data class Prepare(val fromMs: Long) : Step

        /** Start the tail rolling, silent, kept on the main player's moment. */
        data object Roll : Step

        /** Set [mix] on both players: the tail takes the song over at its start. */
        data class Apply(val mix: Mix) : Step

        /**
         * The song is the tail's now: the main player moves on to the next song. On "repeat one"
         * ([loop]) the song that comes in is the same one, from its top.
         */
        data class Start(val loop: Boolean = false) : Step

        /** A tail that shouldn't go on (crossfade off, sleep timer, nothing next, too late): let it go. */
        data object Cancel : Step
    }

    fun step(l: Look): Step {
        val tail = l.tail
        val fade = l.seconds * 1_000L
        // On "repeat one" the song follows itself, whatever comes after it in the queue. A song
        // shorter than a few fades plays whole.
        val on = l.seconds > 0 && (l.hasNext || l.repeatOne) && !l.blocked && l.durationMs >= fade * 3
        if (!on) return if (tail != null) Step.Cancel else Step.Idle
        if (!l.playing) return Step.Idle
        val plain = Mixing.plain(fade, l.durationMs)
        val applied = tail?.applied
        if (applied == null && !l.measuring) {
            return if (l.positionMs >= plain.fadeStartMs - MEASURE_AHEAD_MS) Step.Measure else Step.Idle
        }
        // Still measuring at the last moment (a network that crawls): the fade goes as it is.
        val decided = l.measured ?: if (l.positionMs >= plain.fadeStartMs - PLAIN_AFTER_MS) plain else null
        if (tail == null) {
            // Aimed at the measured fade or, before it comes, the earliest it could be.
            val aim = decided?.fadeStartMs ?: (plain.fadeStartMs - Mixing.MAX_TAIL_SILENCE_MS)
            val latest = decided?.fadeStartMs ?: plain.fadeStartMs
            return if (l.positionMs >= aim - PREPARE_AHEAD_MS && l.positionMs < latest - APPLY_MARGIN_MS - 400) {
                Step.Prepare(maxOf(aim - ROLL_AHEAD_MS, l.positionMs + 300).coerceAtLeast(0))
            } else {
                Step.Idle
            }
        }
        if (!tail.rolling) {
            val latest = applied?.fadeStartMs ?: decided?.fadeStartMs ?: plain.fadeStartMs
            // Past its moment (a seek, a pause right at it): no fade, the song just ends.
            return if (l.positionMs >= tail.fromMs - ROLL_EARLY_MS && l.positionMs < latest - APPLY_MARGIN_MS) Step.Roll else Step.Idle
        }
        if (applied == null) {
            decided ?: return Step.Idle
            return when {
                decided.fadeStartMs - l.positionMs >= APPLY_MARGIN_MS -> Step.Apply(decided)
                // Measured too late for its own fade: the plain one, if there's still time for it.
                plain.fadeStartMs - l.positionMs >= APPLY_MARGIN_MS -> Step.Apply(plain)
                else -> Step.Cancel
            }
        }
        // Silent on the main player from the handover: moving on now is never heard.
        return if (l.positionMs >= applied.fadeStartMs + START_AFTER_MS) Step.Start(loop = l.repeatOne) else Step.Idle
    }

    sealed interface Verdict {
        /** Hand over at the fade's start. */
        data object Go : Verdict

        /** Not in step: the fade starts later, so the tail has time to get there. */
        data class Later(val mix: Mix) : Verdict

        /** Too far apart and no time left: no fade this time, the song just ends. */
        data object Drop : Verdict
    }

    /** Whether to hand over at [applied]'s start, the players [offsetMs] apart at the last measure. */
    fun verdict(applied: Mix, offsetMs: Long?): Verdict {
        if (offsetMs != null && kotlin.math.abs(offsetMs) <= IN_STEP_MS) return Verdict.Go
        applied.later(LATER_MS, MIN_FADE_MS)?.let { return Verdict.Later(it) }
        return if (offsetMs == null || kotlin.math.abs(offsetMs) <= TOLERABLE_MS) Verdict.Go else Verdict.Drop
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
 * the fade, silent, and is kept on the very moment the main player is at ([TailSync]) — without
 * waiting for the measure, which may still be on its way (a song never heard, a seek near its
 * end). At the fade's start the main player goes quiet and the tail comes up, both by the position
 * in their own audio ([FadeShape.Role.LEAVE]), and only then does the main player move on. Shortly
 * before, it is checked that the two are in step ([CrossfadePlan.verdict]); if not, the fade starts
 * a little later. A skip, seek or pause before or during the fade ends it at once.
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
    /** The song the tail has (null: no tail). */
    private var tailFor: String? = null
    /** Where the tail waits, paused (it moves on when a roll is stopped). */
    private var tailFrom = 0L
    private var rolling = false
    private val sync = TailSync()

    /** The fade set on the tail (and, while rolling, on the main player), and whether it was decided. */
    private var applied: Mix? = null
    private var decided = false
    /** The next song [applied] measured (null: it measured none). */
    private var appliedNext: String? = null

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
        // A tail of another song (the queue changed under it) is no use.
        if (tail != null && tailFor != current.mediaId) {
            stopRoll()
            dropTail()
        }
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        val nextIndex = player.nextMediaItemIndex
        val next = if (repeatOne) current else if (nextIndex != C.INDEX_UNSET) player.getMediaItemAt(nextIndex) else null
        val fade = seconds() * 1_000L
        val duration = player.duration
        // The queue or the setting changed since the measure: measure again (a fade already set stays).
        val key = next?.let { "${current.mediaId}|${it.mediaId}|$fade|$duration" }
        if (applied == null && key != measureKey) forgetMeasure()
        val position = player.currentPosition
        val step = CrossfadePlan.step(
            CrossfadePlan.Look(
                seconds = seconds(),
                durationMs = duration,
                positionMs = position,
                playing = player.isPlaying,
                hasNext = player.hasNextMediaItem(),
                repeatOne = repeatOne,
                blocked = blocked(),
                measuring = measureKey != null,
                measured = measured,
                tail = tail?.let { CrossfadePlan.Tail(tailFrom, rolling, applied) },
            ),
        )
        when (step) {
            CrossfadePlan.Step.Idle -> Unit
            CrossfadePlan.Step.Measure -> if (next != null && key != null) startMeasure(current, duration, next, fade, key)
            is CrossfadePlan.Step.Prepare -> prepare(current, step.fromMs)
            CrossfadePlan.Step.Roll -> roll()
            is CrossfadePlan.Step.Apply -> apply(step.mix, position)
            is CrossfadePlan.Step.Start -> begin(step.loop)
            CrossfadePlan.Step.Cancel -> {
                stopRoll()
                dropTail()
            }
        }
        if (fading) return FADE_WATCH_MS
        val t = tail ?: return WATCH_MS
        if (rolling) return keepInStep(t)
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

    /** The tail, paused at [fromMs] of [item] and silent until a fade is set on it. */
    private fun prepare(item: MediaItem, fromMs: Long) {
        dropTail()
        val t = runCatching { newTail() }.getOrNull() ?: return
        // Shaped before any of its audio is made.
        t.shaper.hold(FadeShape.SILENT)
        t.player.setAudioSessionId(player.audioSessionId)
        t.player.volume = 1f
        t.player.setMediaItem(item, fromMs)
        t.player.playWhenReady = false
        t.player.prepare()
        tail = t
        tailFor = item.mediaId
        tailFrom = fromMs
    }

    /** The tail starts, silent; with a fade set, the main player will go quiet at it, by its own audio. */
    private fun roll() {
        val t = tail ?: return
        // Far from where the main player is (it came in late): near it, the rest is [TailSync]'s.
        val pos = player.currentPosition
        if (abs(t.player.currentPosition - pos) > TailSync.JUMP_OVER_MS) t.player.seekTo(pos + TailSync.JUMP_LEAD_MS)
        t.player.setPlaybackSpeed(1f)
        t.player.play()
        applied?.let { shaper.leave(it.leaving()) }
        decided = false
        rolling = true
        sync.start(SystemClock.elapsedRealtime())
    }

    /** Sets [mix] on both players (rolling): the tail comes in at its start, the main player leaves there. */
    private fun apply(mix: Mix, positionMs: Long) {
        val t = tail ?: return
        if (mix.fadeStartMs - positionMs < CrossfadePlan.APPLY_MARGIN_MS) return
        t.shaper.hold(mix.outgoing())
        shaper.leave(mix.leaving())
        // A measured mix holds for the next song it measured; a plain one for whatever comes next.
        appliedNext = if (mix == measured) measureNext else null
        applied = mix
        decided = false
        sync.again()
    }

    /** While rolling: keeps the tail on the main player's moment; returns when to look again. */
    private fun keepInStep(t: Tail): Long {
        val now = SystemClock.elapsedRealtime()
        val position = player.currentPosition
        val mix = applied
        val handover = mix?.fadeStartMs ?: (Mixing.plain(seconds() * 1_000L, player.duration).fadeStartMs)
        val toHandover = handover - position
        if (toHandover <= SAFE_MS && !t.player.isPlaying) {
            // The tail isn't sounding (stuck loading): the main player keeps the song, no fade this time.
            stopRoll()
            dropTail()
            return WATCH_MS
        }
        val offset = if (t.player.isPlaying && player.isPlaying) position - t.player.currentPosition else null
        when (val action = sync.step(now, offset, toHandover - CrossfadePlan.DECIDE_MS)) {
            TailSync.Action.None -> Unit
            is TailSync.Action.Speed -> t.player.setPlaybackSpeed(action.speed)
            is TailSync.Action.Jump -> t.player.seekTo(t.player.currentPosition + action.byMs)
        }
        if (mix != null && !decided && toHandover <= CrossfadePlan.DECIDE_MS) decide(t, mix, toHandover)
        if (!rolling) return WATCH_MS
        // Right at the decision and at the main player's move, else often enough to measure and nudge on time.
        val next = listOf(toHandover - CrossfadePlan.DECIDE_MS, toHandover + CrossfadePlan.START_AFTER_MS)
            .filter { it in 1 until ROLL_WATCH_MS }.minOrNull() ?: ROLL_WATCH_MS
        return sync.lookInMs(now, next)
    }

    /** Shortly before the fade: in step, it goes; else a little later; no time left, no fade. */
    private fun decide(t: Tail, mix: Mix, toHandoverMs: Long) {
        val verdict = CrossfadePlan.verdict(mix, sync.lastOffsetMs)
        // Looked at late (a busy thread): the players' audio may be past changing safely; it goes.
        if (verdict is CrossfadePlan.Verdict.Later && toHandoverMs < CrossfadePlan.DECIDE_MS - LATE_SLACK_MS) {
            decided = true
            return
        }
        when (verdict) {
            CrossfadePlan.Verdict.Go -> decided = true
            is CrossfadePlan.Verdict.Later -> {
                // Both still well ahead of the old start in their audio: the new one takes over cleanly.
                t.shaper.hold(verdict.mix.outgoing())
                shaper.leave(verdict.mix.leaving())
                applied = verdict.mix
                sync.again()
            }
            CrossfadePlan.Verdict.Drop -> {
                stopRoll()
                dropTail()
            }
        }
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
        val set = applied ?: return
        val nextIndex = if (loop) player.currentMediaItemIndex else player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextId = player.getMediaItemAt(nextIndex).mediaId
        val mix = if (appliedNext == null || appliedNext == nextId) set else set.withoutIncoming()
        rolling = false
        sync.stop()
        // The tail has the song already (it took it over at the handover); only a changed queue changes its end.
        if (mix != set) t.shaper.hold(mix.outgoing())
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
        tailFor = null
        applied = null
        appliedNext = null
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
        tailFor = null
        applied = null
        appliedNext = null
        decided = false
    }

    private companion object {
        const val WATCH_MS = 250L
        const val FADE_WATCH_MS = 200L

        /** While the tail rolls: one position compared per look. */
        const val ROLL_WATCH_MS = 50L

        /** A decision this late past its moment doesn't move the fade any more. */
        const val LATE_SLACK_MS = 400L

        /** This close to the handover the tail must be sounding, or the main player keeps the song. */
        const val SAFE_MS = 250L

        /** The tail is let go this long after the fade (its audio runs a little behind the clock; it's silent by then). */
        const val TAIL_LINGER_MS = 800L
    }
}
