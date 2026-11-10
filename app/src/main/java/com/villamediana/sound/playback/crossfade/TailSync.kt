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
    private var untilMs = 0L
    private val samples = ArrayList<Long>(SAMPLES)

    /** Off by no more than [GOOD_MS] the last time it was measured. */
    var inStep = false
        private set

    /** The tail just started (or moved): let its position settle, then measure. */
    fun start(nowMs: Long) {
        inStep = false
        settle(nowMs, SETTLE_START_MS)
    }

    fun stop() {
        phase = Phase.DONE
        samples.clear()
    }

    /** When to look again, at the latest: a nudge ends right on time. */
    fun lookInMs(nowMs: Long, otherwise: Long): Long =
        if (phase == Phase.NUDGE) (untilMs - nowMs).coerceIn(1, otherwise) else otherwise

    /**
     * One look. [offsetMs] = main player's position − tail's (positive: the tail is behind; null:
     * not both playing); [toHandoverMs] = how long until the fade starts.
     */
    fun step(nowMs: Long, offsetMs: Long?, toHandoverMs: Long): Action {
        when (phase) {
            Phase.DONE -> return Action.None
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
            }
            Phase.SAMPLE -> if (offsetMs == null) {
                settle(nowMs, SETTLE_START_MS)
                return Action.None
            }
        }
        samples += offsetMs!!
        if (samples.size < SAMPLES) return Action.None
        val off = samples.sorted()[SAMPLES / 2]
        samples.clear()
        inStep = abs(off) <= GOOD_MS
        return when {
            inStep -> done()
            abs(off) > JUMP_OVER_MS ->
                if (toHandoverMs >= SETTLE_START_MS + SAMPLE_SPAN_MS) {
                    settle(nowMs, SETTLE_START_MS)
                    Action.Jump(off + JUMP_LEAD_MS)
                } else {
                    done()
                }
            toHandoverMs >= NUDGE_MS + SETTLE_NUDGE_MS + SAMPLE_SPAN_MS -> {
                phase = Phase.NUDGE
                untilMs = nowMs + NUDGE_MS
                Action.Speed(1f + (off.toFloat() / NUDGE_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE))
            }
            // No time left to put it right: as close as it got.
            else -> done()
        }
    }

    private fun settle(nowMs: Long, forMs: Long) {
        phase = Phase.SETTLE
        untilMs = nowMs + forMs
        samples.clear()
    }

    private fun done(): Action {
        phase = Phase.DONE
        return Action.None
    }

    companion object {
        /** A player's position needs this long after it starts to say what is really heard. */
        const val SETTLE_START_MS = 1_500L

        /** After a nudge: what was played at the other speed leaves the output's buffer. */
        const val SETTLE_NUDGE_MS = 600L

        /** Positions compared in a row (one per look), the middle one counts. */
        const val SAMPLES = 5

        /** The looks a measure takes. */
        const val SAMPLE_SPAN_MS = 300L

        /** Close enough: well inside the handover. */
        const val GOOD_MS = 8L

        /** A nudge lasts this long, the speed set so it makes up the whole offset. */
        const val NUDGE_MS = 800L
        const val MAX_NUDGE = 0.5f

        /** Further off than this (the listener came in late), a jump instead of a nudge… */
        const val JUMP_OVER_MS = 600L

        /** …a little further, since the jump itself sets the tail back a bit. */
        const val JUMP_LEAD_MS = 100L
    }
}
