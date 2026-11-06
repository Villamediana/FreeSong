package com.villamediana.surco.ui.settings

import com.villamediana.surco.data.AudioQuality
import java.util.Locale

/** A typical song (3 min 30 s) — reference for the "≈ size per song" hint. */
const val REFERENCE_SONG_MS = 210_000L

/** Nominal bitrate of each quality, in kbps (what the downloader picks on YouTube). */
fun qualityKbps(q: AudioQuality): Int = when (q) {
    AudioQuality.BEST -> 160
    AudioQuality.COMPATIBLE -> 128
    AudioQuality.SAVER -> 64
}

/** Approximate file size of a song of [durationMs] at quality [q]. */
fun approxSongBytes(q: AudioQuality, durationMs: Long = REFERENCE_SONG_MS): Long =
    qualityKbps(q) * 1000L / 8 * durationMs / 1000

/** Listening time in hours for the stats: "0,4" · "7,5" · "12" · "230". */
fun formatHours(durationMs: Long, locale: Locale = Locale.getDefault()): String {
    val hours = durationMs / 3_600_000.0
    return if (hours < 10) String.format(locale, "%.1f", hours) else String.format(locale, "%.0f", hours)
}

/**
 * Quantity for a stat's plural label, shown under its number ("0" over "músicas"): Portuguese
 * plural rules put 0 in "one", which would read "0 música"; any count but 1 takes the plural.
 */
fun labelQuantity(n: Int): Int = if (n == 0) 2 else n

/** Version line: "1.0.0 (1)". */
fun versionLabel(name: String, code: Int): String = "$name ($code)"
