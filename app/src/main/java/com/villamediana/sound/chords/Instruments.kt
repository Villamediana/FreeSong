package com.villamediana.sound.chords

/**
 * The instruments a chord can be shown on. Fretted ones list their open strings as MIDI notes in
 * the order the diagram draws them, left to right (the ukulele's high G first, re-entrant).
 */
enum class Instrument(
    val strings: IntArray,
    val stringNames: List<String>,
    /** chords-db shapes in assets (players' own fingerings); null = worked out here. */
    val asset: String?,
) {
    VIOLAO(intArrayOf(40, 45, 50, 55, 59, 64), listOf("E", "A", "D", "G", "B", "E"), "chords/guitar.json"),
    UKULELE(intArrayOf(67, 60, 64, 69), listOf("G", "C", "E", "A"), "chords/ukulele.json"),
    CAVAQUINHO(intArrayOf(62, 67, 71, 74), listOf("D", "G", "B", "D"), null),
    PIANO(intArrayOf(), emptyList(), null),
    ;

    val fretted: Boolean get() = strings.isNotEmpty()

    /** A guitar leaves its low strings out of a chord now and then; a small four-string sounds them all. */
    val maxMuted: Int get() = if (strings.size >= 6) 2 else 0

    companion object {
        /**
         * The ones offered, in this order. The cavaquinho is set aside for now (Miguel's call): its
         * shapes still work, it just isn't shown, and a saved choice of it reads as Violão.
         */
        val offered: List<Instrument> = listOf(VIOLAO, UKULELE, PIANO)

        fun fromId(id: String?): Instrument = offered.firstOrNull { it.name == id } ?: VIOLAO
    }
}

/** A barre: one finger across [from]..[to] (string indexes, inclusive) at [fret]. */
data class Barre(val fret: Int, val from: Int, val to: Int)

/**
 * Where the fingers go on a fretted instrument: an absolute fret per string (−1 not played, 0 open),
 * the finger on each (1 index … 4 little, 0 none) and the barres.
 */
data class Fingering(val frets: List<Int>, val fingers: List<Int>, val barres: List<Barre> = emptyList()) {
    private val pressed: List<Int> get() = frets.filter { it > 0 }

    /** The first fret the diagram shows: the nut when everything fits in the first four. */
    val firstFret: Int get() = if ((pressed.maxOrNull() ?: 0) <= 4) 1 else pressed.min()

    /** How many frets the diagram draws (at least four). */
    val rows: Int get() = maxOf(4, (pressed.maxOrNull() ?: 0) - firstFret + 1)

    fun pitches(instrument: Instrument): List<Int> =
        instrument.strings.indices.filter { frets[it] >= 0 }.map { instrument.strings[it] + frets[it] }
}

/** The notes of a chord on the piano: close position around middle C, then each inversion. */
object PianoVoicing {
    private const val MIDDLE_C = 60

    /** Root position first, then the inversions; a slash chord adds its bass an octave below. */
    fun voicings(chord: Chord): List<List<Int>> {
        val tones = chord.quality.intervals.map { (chord.root + it) % 12 }.distinct()
        return tones.indices.map { k ->
            val order = tones.drop(k) + tones.take(k)
            val notes = mutableListOf<Int>()
            var last = MIDDLE_C - 1
            for (pc in order) {
                var n = last + 1
                while (Math.floorMod(n, 12) != pc) n++
                // The first note sits in the octave of middle C.
                if (notes.isEmpty()) n = MIDDLE_C + pc
                notes += n
                last = n
            }
            val bass = chord.bass?.takeIf { it != chord.root }?.let { MIDDLE_C - 12 + it }
            listOfNotNull(bass) + notes
        }
    }
}
