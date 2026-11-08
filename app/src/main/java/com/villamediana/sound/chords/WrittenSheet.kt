package com.villamediana.sound.chords

import kotlinx.serialization.Serializable

/** A chord written over column [column] of its line, by the name the sheet gives it. */
@Serializable
data class SheetChord(val column: Int, val name: String)

/** One line of a written sheet: words with their chords over them, or chords alone (blank [text]). */
@Serializable
data class SheetLine(val text: String, val chords: List<SheetChord> = emptyList()) {
    val lyric: Boolean get() = text.isNotBlank()
}

/**
 * A song's chords as a player wrote them (a chord sheet from [source], at [url]): lines of words
 * with the chords over them and lines of chords alone (intro, solos, the end), in reading order.
 * [capo] is the fret the shapes are played at (0: none).
 */
@Serializable
data class WrittenSheet(
    val lines: List<SheetLine>,
    val source: String,
    val url: String,
    val capo: Int = 0,
    /** Whose version it is, when another artist's than the song playing (a cover of the same song). */
    val versionOf: String = "",
) {
    /** Every chord as written, in reading order: what the rows' chord indices point at. */
    val names: List<String> get() = lines.flatMap { l -> l.chords.map { it.name } }

    /** Where each line's chords start in [names]. */
    fun firstIndex(): IntArray {
        val out = IntArray(lines.size)
        var at = 0
        for (i in lines.indices) {
            out[i] = at
            at += lines[i].chords.size
        }
        return out
    }
}

/** What asking for a written sheet came to. */
sealed interface SheetLookup {
    data class Found(val chart: ChordChart) : SheetLookup

    /** Asked, and there is none for this song. */
    data object Missing : SheetLookup

    /** Couldn't ask: no connection, the site refused or took too long. */
    data object Unreachable : SheetLookup
}
