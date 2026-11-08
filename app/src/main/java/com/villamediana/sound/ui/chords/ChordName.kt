package com.villamediana.sound.ui.chords

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * A chord name set the way songbooks print it: the chord itself (root, sharp or flat, minor) at full
 * size, what colors it ("7M", "7(13)", "sus4", "º") smaller and raised, and a bass after the slash
 * back on the line. "Bm7(9)" reads Bm⁷⁽⁹⁾, "D/F#" stays D/F#.
 */
internal object ChordName {
    /** [head] is the chord, [extension] what colors it, [bass] the "/F#" part (with the slash). */
    data class Parts(val head: String, val extension: String, val bass: String)

    /** The parts of a written name; null when it isn't a chord name ("N.C.", a note sign). */
    fun split(name: String): Parts? {
        if (name.isEmpty() || name[0] !in 'A'..'G') return null
        var i = 1
        if (i < name.length && (name[i] == '#' || name[i] == 'b')) i++
        // "m" is minor; "maj" belongs to the extension.
        if (i < name.length && name[i] == 'm' && !name.startsWith("maj", i)) i++
        // A slash followed by a note is a bass; "7/9" (as some sheets write 7(9)) is not.
        val slash = (i until name.length).firstOrNull { name[it] == '/' && it + 1 < name.length && name[it + 1] in 'A'..'G' }
        return Parts(
            head = name.substring(0, i),
            extension = if (slash != null) name.substring(i, slash) else name.substring(i),
            bass = if (slash != null) name.substring(slash) else "",
        )
    }

    /**
     * [name] typeset: the extension smaller and raised, its top about the chord letter's. Not a
     * chord name: as it is. A big title ([display]) takes a smaller extension, or it weighs as
     * much as the chord.
     */
    fun typeset(name: String, display: Boolean = false): AnnotatedString {
        val parts = split(name) ?: return AnnotatedString(name)
        if (parts.extension.isEmpty()) return AnnotatedString(name)
        return buildAnnotatedString {
            append(parts.head)
            withStyle(if (display) DISPLAY_EXTENSION else EXTENSION) { append(parts.extension) }
            append(parts.bass)
        }
    }

    private val EXTENSION = SpanStyle(fontSize = 0.7.em, baselineShift = BaselineShift(0.36f), letterSpacing = 0.02.em)
    // The shift is measured on the chord's own size, not the extension's.
    private val DISPLAY_EXTENSION = SpanStyle(fontSize = 0.5.em, baselineShift = BaselineShift(0.4f), letterSpacing = 0.01.em)
}
