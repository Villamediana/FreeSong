package com.villamediana.sound.playback.crossfade

import com.villamediana.sound.data.MAX_CROSSFADE_SECONDS
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * How a crossfade goes (pure: tested on its own). Before the fade, the end of the song going out
 * and the start of the next one are measured ([Envelope]); from that:
 *
 * - the fade lasts exactly what the listener set, over music: silence at the end of the song going
 *   out, and at the start of the next one, is left out of it;
 * - the two meet at the same loudness: a quiet song into a loud one, the loud one comes in from the
 *   quiet one's level and rises to its own along the fade (and a loud one into a quiet one comes
 *   down to it), instead of the loud one taking over at once;
 * - the bass goes from one song to the other in the middle of the fade, so two beats never pound
 *   together (the rest of the sound crosses with equal power, no dip in the middle).
 */

/** One crossfade, decided before it starts. */
data class Mix(
    val fadeMs: Long,
    /** Where in the song going out the fade starts (its silence at the end left out). */
    val fadeStartMs: Long,
    /** Where the next song is picked up (its silence at the start left out). */
    val inStartMs: Long,
    /** dB the song going out comes down to by the end of the fade, besides fading (≤ 0: the next is quieter). */
    val outMatchDb: Float,
    /** dB the next song comes in below its own level, back to it by the end (≤ 0: it is louder). */
    val inMatchDb: Float,
) {
    /** The tail player's song: silent until the fade, where it takes over from the main player, then fading out. */
    fun outgoing() = FadeShape(FadeShape.Role.OUT, fadeStartMs * 1_000, fadeMs * 1_000, outMatchDb, enterUs = fadeStartMs * 1_000)

    /** The main player's song before it moves on: it hands over to the tail at the fade's start. */
    fun leaving() = FadeShape(FadeShape.Role.LEAVE, fadeStartMs * 1_000, FadeShape.HANDOVER_US, 0f)

    fun incoming() = FadeShape(FadeShape.Role.IN, inStartMs * 1_000, fadeMs * 1_000, inMatchDb)

    /** The next song isn't the one measured any more (the queue changed): only the end of this one holds. */
    fun withoutIncoming() = copy(inStartMs = 0, inMatchDb = 0f, outMatchDb = 0f)

    /**
     * The same fade starting [byMs] later and ending where it did (the tail needs a moment more to
     * be on the main player's moment); null when what's left would be shorter than [minFadeMs].
     */
    fun later(byMs: Long, minFadeMs: Long): Mix? =
        if (fadeMs - byMs < minFadeMs) null else copy(fadeStartMs = fadeStartMs + byMs, fadeMs = fadeMs - byMs)
}

object Mixing {
    /** Silence at the end of a song left out of the fade, at most. */
    const val MAX_TAIL_SILENCE_MS = 20_000L

    /** Silence at the start of a song skipped, at most. */
    const val MAX_LEAD_SILENCE_MS = 8_000L

    /** The song going out is as loud as these last seconds before the fade (and its first half). */
    const val LEVEL_BEFORE_MS = 5_000L

    /** The next song is as loud as the fade and these seconds after it. */
    const val LEVEL_AFTER_MS = 5_000L

    /** Two songs further apart than this are brought this close, no closer. */
    const val MAX_MATCH_DB = 18f

    /** Quieter than this (or than the stretch's level − [SILENCE_BELOW_LU]) is silence. */
    const val SILENCE_LUFS = -45f
    const val SILENCE_BELOW_LU = 25f

    /** Kept after the last sound / before the first one, so no note is clipped. */
    const val TAIL_MARGIN_MS = 250L
    const val LEAD_MARGIN_MS = 120L

    private const val MAX_FADE_MS = MAX_CROSSFADE_SECONDS * 1_000L

    /** How much of the end of the song going out is measured. */
    const val TAIL_WINDOW_MS = MAX_TAIL_SILENCE_MS + MAX_FADE_MS + LEVEL_BEFORE_MS + 1_000L

    /** How much of the start of the next song is measured. */
    const val HEAD_WINDOW_MS = MAX_LEAD_SILENCE_MS + MAX_FADE_MS + LEVEL_AFTER_MS + 1_000L

    /** A measure that stops this short of the song's end didn't reach it (a read error): not used. */
    private const val END_SLACK_MS = 1_500L

    /** Nothing measured: the fade over the last seconds of the file, songs as they are. */
    fun plain(fadeMs: Long, durationMs: Long) = Mix(fadeMs, (durationMs - fadeMs).coerceAtLeast(0), 0, 0f, 0f)

    /**
     * The crossfade of [fadeMs] from a song of [durationMs] whose end sounds like [outgoing] into one
     * that starts like [incoming] (either null when it couldn't be measured).
     */
    fun mix(fadeMs: Long, durationMs: Long, outgoing: Envelope?, incoming: Envelope?): Mix {
        val out = outgoing?.takeIf { it.endUs >= (durationMs - END_SLACK_MS) * 1_000 && it.loudness() != null }
        val inc = incoming?.takeIf { it.loudness() != null }

        var end = durationMs
        if (out != null) {
            out.lastSoundUs(silence(out))?.let { lastUs ->
                end = (lastUs / 1_000 + TAIL_MARGIN_MS).coerceAtMost(durationMs)
            }
            end = end.coerceAtLeast(durationMs - MAX_TAIL_SILENCE_MS)
        }
        val fadeStart = (end - fadeMs).coerceAtLeast(0)

        var inStart = 0L
        if (inc != null) {
            inc.firstSoundUs(silence(inc))?.let { firstUs ->
                inStart = (firstUs / 1_000 - LEAD_MARGIN_MS).coerceIn(0, MAX_LEAD_SILENCE_MS)
            }
        }

        val before = out?.loudness((fadeStart - LEVEL_BEFORE_MS) * 1_000, (fadeStart + fadeMs / 2) * 1_000)
        val after = inc?.loudness(inStart * 1_000, (inStart + fadeMs + LEVEL_AFTER_MS) * 1_000)
        val delta = if (before != null && after != null) (after - before).coerceIn(-MAX_MATCH_DB, MAX_MATCH_DB) else 0f
        return Mix(
            fadeMs = fadeMs,
            fadeStartMs = fadeStart,
            inStartMs = inStart,
            outMatchDb = if (delta < 0f) delta else 0f,
            inMatchDb = if (delta > 0f) -delta else 0f,
        )
    }

    /** Silence for a stretch: below −45 LUFS, lower still for a quiet song. */
    private fun silence(e: Envelope): Float {
        val level = e.loudness() ?: return SILENCE_LUFS
        return min(SILENCE_LUFS, level - SILENCE_BELOW_LU)
    }
}

/**
 * What one song does along the fade, by its own position (µs): [Role.OUT] fades out from
 * [startUs], [Role.IN] fades in from it; both over [lengthUs]. Volume and bass are read per moment
 * of the audio itself ([FadeShaper]), so the fade follows the music to the sample.
 *
 * The song going out changes players at the fade's start: the main player ([Role.LEAVE]) goes
 * quiet there in [HANDOVER_US] while the tail player, rolling on the same moment of the song but
 * silent until then ([enterUs]), comes up in the same stretch. Both by position, so nothing of the
 * song is lost or heard twice.
 */
data class FadeShape(
    val role: Role,
    val startUs: Long,
    val lengthUs: Long,
    val matchDb: Float,
    /** [Role.OUT] only: silent before this, taking over in [HANDOVER_US] (null: sounding from the start). */
    val enterUs: Long? = null,
) {
    enum class Role { OUT, IN, LEAVE }

    /** 0 at the start of the fade, 1 at its end (beyond either side outside it). */
    fun progress(us: Long): Float = (us - startUs).toFloat() / lengthUs.coerceAtLeast(1)

    /** Past the fade: an incoming song plays as it is, a song going out is silent. */
    fun over(us: Long): Boolean = progress(us) >= 1f

    /**
     * Volume at [us] (linear). Equal power (cos/sin: no dip in the middle), times the level match,
     * which eases in and out (smoothstep) so it is never heard as a step.
     */
    fun gain(us: Long): Float {
        val t = progress(us)
        return when (role) {
            Role.OUT -> enterUs.let { if (it == null) 1f else handIn(us - it) } * when {
                t <= 0f -> 1f
                t >= 1f -> 0f
                else -> cos(t * PI / 2).toFloat() * dbToGain(matchDb * smooth(t))
            }
            Role.IN -> when {
                t <= 0f -> 0f
                t >= 1f -> 1f
                else -> sin(t * PI / 2).toFloat() * dbToGain(matchDb * (1f - smooth(t)))
            }
            Role.LEAVE -> handOut(us - startUs)
        }
    }

    /**
     * The bass at [us], as the gain of a low shelf (dB, 0 = untouched): the song going out keeps
     * its bass until a quarter of the fade and hands it over by three quarters; the next one gets
     * it in the same stretch (equal power).
     */
    fun bassDb(us: Long): Float {
        if (role == Role.LEAVE) return 0f
        val t = progress(us)
        val u = smooth(((t - BASS_FROM) / (BASS_TO - BASS_FROM)).coerceIn(0f, 1f))
        val level = when (role) {
            Role.OUT -> cos(u * PI / 2).toFloat()
            Role.IN -> sin(u * PI / 2).toFloat()
        }
        return if (level <= 0f) BASS_FLOOR_DB else max(BASS_FLOOR_DB, 20f * log10(level))
    }

    companion object {
        /** The low shelf's corner: kick and bass line, below the voice. */
        const val BASS_HZ = 220.0

        /** The deepest the bass goes while it belongs to the other song. */
        const val BASS_FLOOR_DB = -24f

        private const val BASS_FROM = 0.25f
        private const val BASS_TO = 0.75f

        /** The tail rolling before its fade is decided: silent, whatever the position. */
        val SILENT = FadeShape(Role.OUT, Long.MAX_VALUE / 4, 1, 0f, enterUs = Long.MAX_VALUE / 4)

        /** How long the song going out takes to pass from the main player to the tail. */
        const val HANDOVER_US = 40_000L

        /**
         * The tail's volume along the handover, [sinceUs] after its start ([handOut]: the main
         * player's). Equal power: the two players are never exactly on the same sample (a few ms
         * apart the highs no longer line up), so it never dips; on the very same sample it is a
         * few dB over for those few ms, which the ear doesn't catch.
         */
        fun handIn(sinceUs: Long): Float {
            val x = (sinceUs.toFloat() / HANDOVER_US).coerceIn(0f, 1f)
            return if (x >= 1f) 1f else sin(x * PI / 2).toFloat()
        }

        fun handOut(sinceUs: Long): Float {
            val x = (sinceUs.toFloat() / HANDOVER_US).coerceIn(0f, 1f)
            return if (x >= 1f) 0f else cos(x * PI / 2).toFloat()
        }

        fun smooth(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
    }
}
