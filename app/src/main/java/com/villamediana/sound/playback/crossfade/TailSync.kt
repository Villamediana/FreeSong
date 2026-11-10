package com.villamediana.sound.playback.crossfade

import kotlin.math.abs

/**
 * Puts the tail player on the very moment of the song the main player is at, while it rolls
 * silent before the handover (pure: tested on its own).
 *
 * A player that starts takes its own while to sound (the output warming up, a busy thread): tens
 * to a couple hundred ms. Handed over like that, a bit of the song would go missing — the blink
 * heard before the fade — or be heard twice. The tail is silent until the fade, so it can be put
 * right freely: a moment a little faster or slower, until both players say the same position.
 * Once there it is checked again every few seconds, and the last word is [lastOffsetMs].
 */
class TailSync {
    sealed interface Action {
        data object None : Action

        /** Play the tail at this speed (1: as it is again). */
        data class Speed(val speed: Float) : Action

        /** Too far off to nudge: the tail moves this much at once (and settles again). */
        data class Jump(val byMs: Long) : Action
    }

    private enum class Phase { SETTLE, SAMPLE, NUDGE, DONE }

    private var phase = Phase.DONE
    private var untilMs = Long.MAX_VALUE
    private val samples = ArrayDeque<Long>()
    private var looks = 0

    /** Main player's position − tail's at the last steady measure (null: none since the start). */
    var lastOffsetMs: Long? = null
        private set

    /** The tail just started (or moved): let its position settle, then measure. */
    fun start(nowMs: Long) {
        lastOffsetMs = null
        settle(nowMs, SETTLE_START_MS)
    }

    fun stop() {
        phase = Phase.DONE
        untilMs = Long.MAX_VALUE
        samples.clear()
        lastOffsetMs = null
    }

    /** Measure again now (more time was given before the handover). */
    fun again() {
        if (phase != Phase.DONE || untilMs == Long.MAX_VALUE) return
        phase = Phase.SAMPLE
        samples.clear()
        looks = 0
    }

    /** When to look again, at the latest: a nudge ends right on time. */
    fun lookInMs(nowMs: Long, otherwise: Long): Long =
        if (phase == Phase.NUDGE) (untilMs - nowMs).coerceIn(1, otherwise) else otherwise

    /**
     * One look. [offsetMs] = main player's position − tail's (positive: the tail is behind; null:
     * not both playing); [toDecideMs] = how long until the handover is decided (all must be done by then).
     */
    fun step(nowMs: Long, offsetMs: Long?, toDecideMs: Long): Action {
        when (phase) {
            Phase.DONE -> {
                // A look again now and then, while there is time to act on it.
                if (offsetMs == null || nowMs < untilMs || toDecideMs < SAMPLE_SPAN_MS) return Action.None
                phase = Phase.SAMPLE
                samples.clear()
                looks = 0
            }
            Phase.NUDGE -> {
                if (nowMs < untilMs && offsetMs != null) return Action.None
                settle(nowMs, if (offsetMs == null) SETTLE_START_MS else SETTLE_NUDGE_MS)
                return Action.Speed(1f)
            }
            Phase.SETTLE -> {
                if (offsetMs == null) {
                    settle(nowMs, SETTLE_START_MS)
                    return Action.None
                }
                if (nowMs < untilMs) return Action.None
                phase = Phase.SAMPLE
                samples.clear()
                looks = 0
            }
            Phase.SAMPLE -> if (offsetMs == null) {
                settle(nowMs, SETTLE_START_MS)
                return Action.None
            }
        }
        samples.addLast(offsetMs!!)
        looks++
        if (samples.size > SAMPLES) samples.removeFirst()
        if (samples.size < SAMPLES) return Action.None
        val sorted = samples.sorted()
        // Still moving (a position that hasn't settled yet): a few more looks.
        if (sorted.last() - sorted.first() > STEADY_SPREAD_MS && looks < MAX_LOOKS) return Action.None
        val off = sorted[SAMPLES / 2]
        lastOffsetMs = off
        return when {
            abs(off) <= GOOD_MS -> done(nowMs)
            abs(off) > JUMP_OVER_MS ->
                if (toDecideMs >= SETTLE_START_MS + SAMPLE_SPAN_MS) {
                    settle(nowMs, SETTLE_START_MS)
                    Action.Jump(off + JUMP_LEAD_MS)
                } else {
                    done(nowMs)
                }
            toDecideMs >= NUDGE_MS + SETTLE_NUDGE_MS + SAMPLE_SPAN_MS -> {
                phase = Phase.NUDGE
                untilMs = nowMs + NUDGE_MS
                Action.Speed(1f + (off.toFloat() / NUDGE_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE))
            }
            // No time left to put it right before the handover is decided: as close as it got.
            else -> done(nowMs)
        }
    }

    private fun settle(nowMs: Long, forMs: Long) {
        phase = Phase.SETTLE
        untilMs = nowMs + forMs
        samples.clear()
        looks = 0
    }

    private fun done(nowMs: Long): Action {
        phase = Phase.DONE
        untilMs = nowMs + RECHECK_MS
        samples.clear()
        return Action.None
    }

    companion object {
        /** A player's position needs about this long after it starts to say what is really heard. */
        const val SETTLE_START_MS = 1_000L

        /** After a nudge: what was played at the other speed leaves the output's buffer. */
        const val SETTLE_NUDGE_MS = 600L

        /** Positions compared in a row (one per look), the middle one counts… */
        const val SAMPLES = 5

        /** …once they agree this closely (or after [MAX_LOOKS] looks anyway). */
        const val STEADY_SPREAD_MS = 12L
        const val MAX_LOOKS = 12

        /** The looks a measure takes, at most. */
        const val SAMPLE_SPAN_MS = 600L

        /** Close enough: well inside the handover. */
        const val GOOD_MS = 8L

        /** In step, it is measured again this often until the handover. */
        const val RECHECK_MS = 3_000L

        /** A nudge lasts this long, the speed set so it makes up the whole offset. */
        const val NUDGE_MS = 800L
        const val MAX_NUDGE = 0.5f

        /** Further off than this (the listener came in late), a jump instead of a nudge… */
        const val JUMP_OVER_MS = 600L

        /** …a little further, since the jump itself sets the tail back a bit. */
        const val JUMP_LEAD_MS = 100L
    }
}
