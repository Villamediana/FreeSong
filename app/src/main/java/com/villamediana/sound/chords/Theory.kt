package com.villamediana.sound.chords

import kotlin.math.sqrt

/**
 * What a chord is made of: the intervals over its root. [symbol] is how a Brazilian chord sheet
 * writes it (Am7, G7, C7M, Bm7(b5)…); [dbSuffix] is the chords-db name of its shapes.
 */
enum class ChordQuality(val symbol: String, val intervals: IntArray, val dbSuffix: String?) {
    MAJOR("", intArrayOf(0, 4, 7), "major"),
    MINOR("m", intArrayOf(0, 3, 7), "minor"),
    DOM7("7", intArrayOf(0, 4, 7, 10), "7"),
    MIN7("m7", intArrayOf(0, 3, 7, 10), "m7"),
    MAJ7("7M", intArrayOf(0, 4, 7, 11), "maj7"),
    MIN_MAJ7("m(7M)", intArrayOf(0, 3, 7, 11), "mmaj7"),
    SIX("6", intArrayOf(0, 4, 7, 9), "6"),
    MIN6("m6", intArrayOf(0, 3, 7, 9), "m6"),
    SUS2("sus2", intArrayOf(0, 2, 7), "sus2"),
    SUS4("sus4", intArrayOf(0, 5, 7), "sus4"),
    DOM7_SUS4("7sus4", intArrayOf(0, 5, 7, 10), "7sus4"),
    ADD9("add9", intArrayOf(0, 2, 4, 7), "add9"),
    MIN_ADD9("madd9", intArrayOf(0, 2, 3, 7), "madd9"),
    DOM9("7(9)", intArrayOf(0, 2, 4, 7, 10), "9"),
    // What written sheets use often enough to have a shape of its own.
    MIN9("m7(9)", intArrayOf(0, 2, 3, 7, 10), "m9"),
    MAJ9("7M(9)", intArrayOf(0, 2, 4, 7, 11), "maj9"),
    SIX9("6(9)", intArrayOf(0, 2, 4, 7, 9), "69"),
    MIN6_9("m6(9)", intArrayOf(0, 2, 3, 7, 9), "m69"),
    MIN11("m7(11)", intArrayOf(0, 3, 5, 7, 10), "m11"),
    DOM13("7(13)", intArrayOf(0, 4, 7, 9, 10), "13"),
    DOM7_FLAT9("7(b9)", intArrayOf(0, 1, 4, 7, 10), "7b9"),
    DOM7_SHARP9("7(#9)", intArrayOf(0, 3, 4, 7, 10), "7#9"),
    DOM7_FLAT5("7(b5)", intArrayOf(0, 4, 6, 10), "7b5"),
    AUG7("7(#5)", intArrayOf(0, 4, 8, 10), "aug7"),
    DIM("°", intArrayOf(0, 3, 6), "dim"),
    DIM7("°7", intArrayOf(0, 3, 6, 9), "dim7"),
    HALF_DIM("m7(b5)", intArrayOf(0, 3, 6, 10), "m7b5"),
    AUG("+", intArrayOf(0, 4, 8), "aug"),
    POWER("5", intArrayOf(0, 7), null),
    ;

    /** The tones a shape must sound for the chord to read as itself (the fifth can go, but not here). */
    val essential: IntArray
        get() = when (this) {
            POWER, DIM, DIM7, HALF_DIM, AUG, AUG7, DOM7_FLAT5 -> intervals
            else -> intervals.filter { it != 7 }.toIntArray()
        }
}

/** A chord: root and bass as pitch classes (C = 0), and its quality. */
data class Chord(val root: Int, val quality: ChordQuality, val bass: Int? = null) {
    init {
        require(root in 0..11) { "root $root" }
        require(bass == null || bass in 0..11) { "bass $bass" }
    }

    /** Pitch classes it sounds, root first, then in interval order; the bass leads when it is another note. */
    val tones: List<Int> get() = buildList {
        bass?.takeIf { it != root }?.let { add(it) }
        quality.intervals.forEach { add((root + it) % 12) }
    }.distinct()

    fun name(spelling: Spelling = Spelling.NEUTRAL): String =
        spelling.name(root) + quality.symbol + (bass?.takeIf { it != root }?.let { "/" + spelling.name(it) } ?: "")

    fun transpose(semitones: Int): Chord = Chord(
        root = Math.floorMod(root + semitones, 12),
        quality = quality,
        bass = bass?.let { Math.floorMod(it + semitones, 12) },
    )

    override fun toString(): String = name()

    companion object {
        private val ROOT = Regex("^([A-G])([#♯b♭]?)")

        /**
         * Reads the usual ways of writing a chord: Am, A-, Amin, A7, Am7, A7M, Amaj7, AΔ, A°, Adim,
         * Am7(b5), Aø, A+, Aaug, Asus4, A4, A7(9), A9, Aadd9, A6, A/C#… Null for anything else.
         */
        fun parse(text: String): Chord? {
            val t = text.trim().replace('♯', '#').replace('♭', 'b')
            val m = ROOT.find(t) ?: return null
            val root = pitchClass(m.groupValues[1] + m.groupValues[2].replace('♯', '#').replace('♭', 'b')) ?: return null
            var rest = t.substring(m.range.last + 1)
            var bass: Int? = null
            val slash = rest.lastIndexOf('/')
            if (slash >= 0) {
                val after = rest.substring(slash + 1)
                val b = ROOT.matchEntire(after)
                if (b != null) {
                    bass = pitchClass(b.groupValues[1] + b.groupValues[2]) ?: return null
                    rest = rest.substring(0, slash)
                }
            }
            val quality = QUALITY_SPELLINGS[rest.replace(" ", "")] ?: return null
            return Chord(root, quality, bass?.takeIf { it != root })
        }

        /**
         * The chord a written name is closest to, for its shapes: [parse] when it can; otherwise the
         * name with its extra notes taken off from the end ("F7M(2/4+)" plays as F7M, "Bb5-" as Bb5),
         * the bass kept. Null only when it doesn't start like a chord.
         */
        fun closest(text: String): Chord? {
            parse(text)?.let { return it }
            val t = text.trim().replace('♯', '#').replace('♭', 'b')
            val m = ROOT.find(t) ?: return null
            val head = m.value
            var rest = t.substring(m.range.last + 1)
            var bass = ""
            val slash = rest.lastIndexOf('/')
            if (slash >= 0 && ROOT.matchEntire(rest.substring(slash + 1)) != null) {
                bass = rest.substring(slash)
                rest = rest.substring(0, slash)
            }
            // "(…)" groups first, whole; then a character at a time.
            while (true) {
                val group = Regex("\\([^()]*\\)$").find(rest)
                rest = if (group != null) rest.substring(0, group.range.first) else rest.dropLast(1)
                parse(head + rest + bass)?.let { return it }
                if (rest.isEmpty()) return parse(head + bass) ?: parse(head)
            }
        }

        private val QUALITY_SPELLINGS: Map<String, ChordQuality> = buildMap {
            fun put(q: ChordQuality, vararg names: String) = names.forEach { put(it, q) }
            put(ChordQuality.MAJOR, "", "M", "maj", "major")
            put(ChordQuality.MINOR, "m", "-", "min", "minor", "mi")
            put(ChordQuality.DOM7, "7")
            put(ChordQuality.MIN7, "m7", "-7", "min7", "mi7")
            put(ChordQuality.MAJ7, "7M", "maj7", "M7", "Δ", "Δ7", "7+", "ma7")
            put(ChordQuality.MIN_MAJ7, "m(7M)", "m7M", "mmaj7", "m(maj7)", "mM7", "-Δ", "m(M7)")
            put(ChordQuality.SIX, "6")
            put(ChordQuality.MIN6, "m6", "-6", "min6")
            put(ChordQuality.SUS2, "sus2", "2", "sus9")
            put(ChordQuality.SUS4, "sus4", "sus", "4")
            put(ChordQuality.DOM7_SUS4, "7sus4", "7sus", "7(4)", "74", "7/4")
            // As Brazilian sheets write it: C9 is C with an added ninth; the dominant ninth is C7(9).
            put(ChordQuality.ADD9, "add9", "9", "(9)", "add2", "9add")
            put(ChordQuality.MIN_ADD9, "madd9", "m9", "m(9)", "m(add9)", "madd2")
            put(ChordQuality.DOM9, "7(9)", "7/9", "79")
            put(ChordQuality.MIN9, "m7(9)", "m7/9", "m79", "-7(9)", "min9")
            put(ChordQuality.MAJ9, "7M(9)", "maj9", "maj7(9)", "M9", "7M/9", "Δ9")
            put(ChordQuality.SIX9, "6(9)", "69", "6/9")
            put(ChordQuality.MIN6_9, "m6(9)", "m69", "m6/9")
            put(ChordQuality.MIN11, "m7(11)", "m11", "m7/11", "m7(4)")
            put(ChordQuality.DOM13, "7(13)", "13", "7/13")
            put(ChordQuality.DOM7_FLAT9, "7(b9)", "7b9", "7(9-)", "7/9-")
            put(ChordQuality.DOM7_SHARP9, "7(#9)", "7#9", "7(9+)", "7/9+")
            put(ChordQuality.DOM7_FLAT5, "7(b5)", "7b5", "7(5-)", "7/5-")
            put(ChordQuality.AUG7, "7(#5)", "7#5", "7(5+)", "7/5+", "+7", "aug7")
            put(ChordQuality.DIM, "°", "º", "dim", "o")
            put(ChordQuality.DIM7, "°7", "º7", "dim7", "o7")
            put(ChordQuality.HALF_DIM, "m7(b5)", "m7b5", "ø", "ø7", "m7(5-)", "m7/5-", "-7b5")
            put(ChordQuality.AUG, "+", "aug", "5+", "(#5)", "#5")
            put(ChordQuality.POWER, "5")
        }

        private val INDEX = mapOf(
            "C" to 0, "B#" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4, "Fb" to 4,
            "E#" to 5, "F" to 5, "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10,
            "Bb" to 10, "B" to 11, "Cb" to 11,
        )

        fun pitchClass(name: String): Int? = INDEX[name.trim()]
    }
}

/** How the black keys are named: C# or Db, A# or Bb… decided by the song's key. */
enum class Spelling(private val names: List<String>) {
    /** What chord sheets use in C / Am and around: C# F#, but Eb Ab Bb. */
    NEUTRAL(listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")),
    SHARPS(listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")),
    FLATS(listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")),
    ;

    fun name(pc: Int): String = names[Math.floorMod(pc, 12)]

    /** "Lá bemol", "Fá sustenido", "Ré". */
    fun spoken(pc: Int): String {
        val n = name(pc)
        val base = SOLFEGE.getValue(n[0])
        return when (n.getOrNull(1)) {
            '#' -> "$base sustenido"
            'b' -> "$base bemol"
            else -> base
        }
    }

    companion object {
        private val SOLFEGE = mapOf('C' to "Dó", 'D' to "Ré", 'E' to "Mi", 'F' to "Fá", 'G' to "Sol", 'A' to "Lá", 'B' to "Si")

        /** Major keys written with sharps (G D A E B F#), their relative minors too; flats for F Bb Eb Ab Db. */
        fun forKey(key: MusicKey?): Spelling {
            if (key == null) return NEUTRAL
            val major = if (key.minor) Math.floorMod(key.tonic + 3, 12) else key.tonic
            return when (major) {
                7, 2, 9, 4, 11, 6 -> SHARPS
                5, 10, 3, 8, 1 -> FLATS
                else -> NEUTRAL
            }
        }
    }
}

/** A key: its tonic as a pitch class (C = 0) and whether it is minor. */
@kotlinx.serialization.Serializable
data class MusicKey(val tonic: Int, val minor: Boolean) {
    fun name(spelling: Spelling = Spelling.forKey(this)): String = spelling.name(tonic) + if (minor) "m" else ""
}

/** The key of what rings: Krumhansl–Kessler profiles against a pitch-class histogram. */
object Keys {
    // How strongly each scale degree belongs to a major / minor key.
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun of(histogram: DoubleArray): MusicKey? {
        if (histogram.sum() <= 0.0) return null
        var best: MusicKey? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (tonic in 0 until 12) {
            for (minor in listOf(false, true)) {
                val s = correlate(histogram, if (minor) MINOR else MAJOR, tonic)
                if (s > bestScore) { bestScore = s; best = MusicKey(tonic, minor) }
            }
        }
        return best
    }

    private fun correlate(h: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        val mh = h.average()
        val mp = profile.average()
        var num = 0.0
        var dh = 0.0
        var dp = 0.0
        for (i in 0 until 12) {
            val a = h[(i + tonic) % 12] - mh
            val b = profile[i] - mp
            num += a * b
            dh += a * a
            dp += b * b
        }
        return if (dh == 0.0 || dp == 0.0) 0.0 else num / sqrt(dh * dp)
    }

    /** The key a chord progression sits in, from the chords' own notes; the first and last weigh more. */
    fun ofChords(chords: List<Chord>): MusicKey? {
        if (chords.isEmpty()) return null
        val h = DoubleArray(12)
        chords.forEachIndexed { i, c ->
            val weight = if (i == 0 || i == chords.lastIndex) 2.0 else 1.0
            c.quality.intervals.forEach { h[(c.root + it) % 12] += weight }
            h[c.root] += weight
        }
        return of(h)
    }
}

/** How a chord is said: "Lá menor com sétima", "Sol com sétima"; in Spanish "La menor con séptima". */
object ChordSpeech {
    fun say(chord: Chord, spelling: Spelling, language: String = "pt"): String {
        val es = language == "es"
        val root = if (es) spanish(spelling.name(chord.root)) else spelling.spoken(chord.root)
        val quality = if (es) {
            when (chord.quality) {
                ChordQuality.MAJOR -> "mayor"
                ChordQuality.MINOR -> "menor"
                ChordQuality.DOM7 -> "con séptima"
                ChordQuality.MIN7 -> "menor con séptima"
                ChordQuality.MAJ7 -> "con séptima mayor"
                ChordQuality.MIN_MAJ7 -> "menor con séptima mayor"
                ChordQuality.SIX -> "con sexta"
                ChordQuality.MIN6 -> "menor con sexta"
                ChordQuality.SUS2 -> "con segunda suspendida"
                ChordQuality.SUS4 -> "con cuarta suspendida"
                ChordQuality.DOM7_SUS4 -> "con séptima y cuarta"
                ChordQuality.ADD9 -> "con novena"
                ChordQuality.MIN_ADD9 -> "menor con novena"
                ChordQuality.DOM9 -> "con séptima y novena"
                ChordQuality.MIN9 -> "menor con séptima y novena"
                ChordQuality.MAJ9 -> "con séptima mayor y novena"
                ChordQuality.SIX9 -> "con sexta y novena"
                ChordQuality.MIN6_9 -> "menor con sexta y novena"
                ChordQuality.MIN11 -> "menor con séptima y oncena"
                ChordQuality.DOM13 -> "con séptima y trecena"
                ChordQuality.DOM7_FLAT9 -> "con séptima y novena menor"
                ChordQuality.DOM7_SHARP9 -> "con séptima y novena aumentada"
                ChordQuality.DOM7_FLAT5 -> "con séptima y quinta disminuida"
                ChordQuality.AUG7 -> "aumentado con séptima"
                ChordQuality.DIM -> "disminuido"
                ChordQuality.DIM7 -> "disminuido con séptima"
                ChordQuality.HALF_DIM -> "semidisminuido"
                ChordQuality.AUG -> "aumentado"
                ChordQuality.POWER -> "con quinta"
            }
        } else {
            when (chord.quality) {
                ChordQuality.MAJOR -> "maior"
                ChordQuality.MINOR -> "menor"
                ChordQuality.DOM7 -> "com sétima"
                ChordQuality.MIN7 -> "menor com sétima"
                ChordQuality.MAJ7 -> "com sétima maior"
                ChordQuality.MIN_MAJ7 -> "menor com sétima maior"
                ChordQuality.SIX -> "com sexta"
                ChordQuality.MIN6 -> "menor com sexta"
                ChordQuality.SUS2 -> "com segunda suspensa"
                ChordQuality.SUS4 -> "com quarta suspensa"
                ChordQuality.DOM7_SUS4 -> "com sétima e quarta"
                ChordQuality.ADD9 -> "com nona"
                ChordQuality.MIN_ADD9 -> "menor com nona"
                ChordQuality.DOM9 -> "com sétima e nona"
                ChordQuality.MIN9 -> "menor com sétima e nona"
                ChordQuality.MAJ9 -> "com sétima maior e nona"
                ChordQuality.SIX9 -> "com sexta e nona"
                ChordQuality.MIN6_9 -> "menor com sexta e nona"
                ChordQuality.MIN11 -> "menor com sétima e décima primeira"
                ChordQuality.DOM13 -> "com sétima e décima terceira"
                ChordQuality.DOM7_FLAT9 -> "com sétima e nona menor"
                ChordQuality.DOM7_SHARP9 -> "com sétima e nona aumentada"
                ChordQuality.DOM7_FLAT5 -> "com sétima e quinta diminuta"
                ChordQuality.AUG7 -> "aumentado com sétima"
                ChordQuality.DIM -> "diminuto"
                ChordQuality.DIM7 -> "diminuto com sétima"
                ChordQuality.HALF_DIM -> "meio-diminuto"
                ChordQuality.AUG -> "aumentado"
                ChordQuality.POWER -> "com quinta"
            }
        }
        val bass = chord.bass?.takeIf { it != chord.root }?.let { b ->
            if (es) " con bajo en " + spanish(spelling.name(b)) else " com baixo em " + spelling.spoken(b)
        }.orEmpty()
        return "$root $quality$bass"
    }

    private fun spanish(name: String): String {
        val base = mapOf('C' to "Do", 'D' to "Re", 'E' to "Mi", 'F' to "Fa", 'G' to "Sol", 'A' to "La", 'B' to "Si").getValue(name[0])
        return when (name.getOrNull(1)) {
            '#' -> "$base sostenido"
            'b' -> "$base bemol"
            else -> base
        }
    }
}
