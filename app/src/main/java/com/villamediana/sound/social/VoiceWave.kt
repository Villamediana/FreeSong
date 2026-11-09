package com.villamediana.sound.social

import kotlin.math.sqrt

/**
 * A voice message's waves, as they travel: one level 0–f per bar (a hex digit), worked out from
 * the microphone's loudness while recording. Pure.
 */
object VoiceWave {
    const val BARS = 48
    private const val DIGITS = "0123456789abcdef"

    /**
     * [samples] (the recorder's loudness, read every few dozen milliseconds) into [bars] levels:
     * the loudest moment of each slice, on a square-root scale (speech reads like speech, not like
     * a few spikes), against the loudest of the whole message. Silence is "0…".
     */
    fun encode(samples: List<Int>, bars: Int = BARS): String {
        if (samples.isEmpty()) return "0".repeat(bars)
        val peak = samples.maxOrNull()?.coerceAtLeast(1) ?: 1
        return buildString(bars) {
            for (b in 0 until bars) {
                val from = b * samples.size / bars
                val to = ((b + 1) * samples.size / bars).coerceAtLeast(from + 1).coerceAtMost(samples.size)
                val loud = if (from < samples.size) (from until to).maxOf { samples[it].coerceAtLeast(0) } else 0
                val level = (sqrt(loud.toDouble() / peak) * 15).toInt().coerceIn(0, 15)
                append(DIGITS[level])
            }
        }
    }

    /** The bars' heights 0..1 (a bar never quite vanishes, so the line reads as a message). */
    fun decode(wave: String?, bars: Int = BARS): List<Float> {
        val digits = wave?.filter { it in DIGITS }.orEmpty()
        if (digits.isEmpty()) return List(bars) { 0.12f }
        return digits.map { (DIGITS.indexOf(it) / 15f).coerceAtLeast(0.12f) }
    }
}
