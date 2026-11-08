package com.villamediana.sound.ui.chords

import kotlin.math.ceil

/**
 * Where the chords of one printed line of lyrics go: always one row, read left to right, never on
 * top of each other.
 *
 * Each chord wants to start at its word ([want], already less the chip's own padding) and is pushed
 * right of the one before it; the last ones that ran past the edge come back in, pushing the ones
 * before them left. When there are more chords than the line is wide (a short line under a run of
 * chords: "I say, oh" with six), that line's chords are set smaller ([Line.scale]), just enough for
 * the row to fit — a second row would read like a table, with no telling which comes first.
 */
internal object ChordPlacement {
    /** [x] per chord; each one drawn at [scale] of its size, from its bottom left corner. */
    class Line(val x: IntArray, val scale: Float)

    /**
     * [want] and [widths] per chord, in order; [width] the line's; chords may hang [pad] past the
     * left edge (their padding), and keep [gap] between them.
     */
    fun place(want: IntArray, widths: IntArray, width: Int, pad: Int, gap: Int): Line {
        val n = want.size
        val x = IntArray(n)
        if (n == 0) return Line(x, 1f)
        val left = -pad
        val total = widths.sum() + gap * (n - 1)
        // Less a pixel per chord: the widths round up.
        val scale = if (total <= width - left) 1f else (width - left - n).coerceAtLeast(1).toFloat() / total
        val w = IntArray(n) { ceil(widths[it] * scale).toInt() }
        val g = (gap * scale).toInt()
        for (i in 0 until n) {
            var at = want[i]
            if (i > 0) at = maxOf(at, x[i - 1] + w[i - 1] + g)
            x[i] = at.coerceAtLeast(left)
        }
        var limit = width
        for (i in n - 1 downTo 0) {
            if (x[i] + w[i] > limit) x[i] = maxOf(left, limit - w[i])
            limit = x[i] - g
        }
        return Line(x, scale)
    }
}
