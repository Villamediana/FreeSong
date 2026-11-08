package com.villamediana.sound.chords

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shapes for a chord on a fretted instrument. Violão and ukulele read players' fingerings from
 * chords-db (MIT, in assets): every one is checked against the chord's notes before it is shown
 * (the database has a few wrong ones). The cavaquinho, and any chord the database lacks, get
 * fingerings worked out here: the easiest ones that sound every note the chord needs.
 */
class Fingerings(private val readAsset: (String) -> String?) {
    private val tables = HashMap<Instrument, Map<String, Map<String, List<Fingering>>>>()

    /** Worked out once per chord: the search over the neck is milliseconds on a guitar. */
    private val known = java.util.concurrent.ConcurrentHashMap<Triple<Chord, Instrument, Int>, List<Fingering>>()

    /**
     * Up to [limit] shapes, the easiest first, from different places on the neck when there is a
     * choice. Players' shapes and found ones are ranked together by how easy they are (the
     * database's own order puts a Bm with F# in the bass first); a players' shape keeps its fingers.
     */
    fun of(chord: Chord, instrument: Instrument, limit: Int = 4): List<Fingering> {
        if (!instrument.fretted) return emptyList()
        val key = Triple(chord, instrument, limit)
        known[key]?.let { return it }
        return work(chord, instrument, limit).also { known[key] = it }
    }

    private fun work(chord: Chord, instrument: Instrument, limit: Int): List<Fingering> {
        val fromDb = table(instrument)?.let { lookup(it, chord) }.orEmpty()
            .filter { FretMath.plays(chord, it, instrument, strict = false) }
        val candidates = (fromDb + FretSearch.find(chord, instrument, CANDIDATES)).distinctBy { it.frets }
        val ranked = candidates.mapNotNull { f -> FretSearch.ease(chord, f.frets, instrument)?.let { f to it } }
            .sortedBy { it.second }.map { it.first }
        // The shape everyone learns comes first, whatever the ranking thinks.
        val canonical = Canonical.shape(chord, instrument)?.let { frets ->
            candidates.firstOrNull { it.frets == frets }
                ?: FretMath.fingers(frets)?.let { (fingers, barres) -> Fingering(frets, fingers, barres) }
        }?.takeIf { FretMath.plays(chord, it, instrument, strict = false) }
        val rest = FretSearch.spread(ranked.filter { it.frets != canonical?.frets }, if (canonical != null) limit - 1 else limit) { it.frets }
        return listOfNotNull(canonical) + rest
    }

    @Synchronized
    private fun table(instrument: Instrument): Map<String, Map<String, List<Fingering>>>? {
        val asset = instrument.asset ?: return null
        tables[instrument]?.let { return it }
        val text = runCatching { readAsset(asset) }.getOrNull() ?: return null
        val parsed = runCatching { parse(text) }.getOrNull() ?: return null
        tables[instrument] = parsed
        return parsed
    }

    private fun lookup(table: Map<String, Map<String, List<Fingering>>>, chord: Chord): List<Fingering>? {
        val byKey = KEY_NAMES[chord.root].firstNotNullOfOrNull { table[it] } ?: return null
        val bass = chord.bass?.takeIf { it != chord.root }
        if (bass == null) return chord.quality.dbSuffix?.let { byKey[it] }
        // Slash chords: chords-db has some major and minor ones ("/E", "m/B").
        val prefix = when (chord.quality) {
            ChordQuality.MAJOR -> "/"
            ChordQuality.MINOR -> "m/"
            else -> return null
        }
        return KEY_NAMES[bass].firstNotNullOfOrNull { byKey[prefix + it] }
    }

    companion object {
        /** Found shapes ranked with the players' ones. */
        private const val CANDIDATES = 12

        private val KEY_NAMES = listOf(
            listOf("C"), listOf("C#", "Db"), listOf("D"), listOf("Eb", "D#"), listOf("E"), listOf("F"),
            listOf("F#", "Gb"), listOf("G"), listOf("Ab", "G#"), listOf("A"), listOf("Bb", "A#"), listOf("B"),
        )

        /**
         * The asset: `{"C": {"major": [[frets], [fingers], baseFret, [barres]], …}, …}`, frets counted
         * from baseFret as chords-db writes them (1 = baseFret, 0 open, −1 not played).
         */
        internal fun parse(text: String): Map<String, Map<String, List<Fingering>>> {
            val root = Json.parseToJsonElement(text).jsonObject
            return root.mapValues { (_, suffixes) ->
                (suffixes as JsonObject).mapValues { (_, positions) ->
                    positions.jsonArray.mapNotNull { p -> runCatching { position(p.jsonArray) }.getOrNull() }
                }
            }
        }

        private fun position(p: JsonArray): Fingering {
            val rel = p[0].jsonArray.map { it.jsonPrimitive.int }
            val fingers = p[1].jsonArray.map { it.jsonPrimitive.int }
            val base = p[2].jsonPrimitive.int
            val frets = rel.map { if (it <= 0) it else it + base - 1 }
            val barres = p[3].jsonArray.map { it.jsonPrimitive.int + base - 1 }.mapNotNull { fret ->
                val on = frets.indices.filter { frets[it] == fret }
                if (on.size < 2) return@mapNotNull null
                val finger = fingers[on.first()]
                val strings = on.filter { fingers[it] == finger }
                if (strings.size < 2) null else Barre(fret, strings.first(), strings.last())
            }
            require(frets.size == fingers.size)
            return Fingering(frets, fingers, barres)
        }
    }
}

/**
 * The shapes a teacher would show first, from the LauncherAI's fretboard table: for the common
 * chords the easiest shape is not always the usual one (a Bm is x24432, not x20402).
 */
internal object Canonical {
    private val UKULELE = mapOf(
        "C" to "0003", "Cm" to "0333", "C7" to "0001", "C7M" to "0002", "Cm7" to "3333", "Csus4" to "0013", "Csus2" to "0233", "C6" to "0000",
        "C#" to "1114", "C#m" to "1104", "C#7" to "1112", "C#m7" to "1102",
        "D" to "2220", "Dm" to "2210", "D7" to "2223", "Dm7" to "2213", "D7M" to "2224", "Dsus4" to "0230", "Dsus2" to "2200",
        "Eb" to "0331", "Ebm" to "3321", "Eb7" to "3334", "Eb7M" to "3335",
        "E" to "1402", "Em" to "0432", "E7" to "1202", "Em7" to "0202", "E7M" to "1302", "Esus4" to "2400",
        "F" to "2010", "Fm" to "1013", "F7" to "2313", "F7M" to "2413", "Fm7" to "1313",
        "F#" to "3121", "F#m" to "2120", "F#7" to "3424", "F#m7" to "2424",
        "G" to "0232", "Gm" to "0231", "G7" to "0212", "G7M" to "0222", "Gm7" to "0211", "Gsus4" to "0233", "Gsus2" to "0230",
        "Ab" to "5343", "Abm" to "4342", "Ab7" to "1323",
        "A" to "2100", "Am" to "2000", "A7" to "0100", "Am7" to "0000", "A7M" to "1100", "Asus4" to "2200", "Asus2" to "2452",
        "Bb" to "3211", "Bbm" to "3111", "Bb7" to "1211", "Bb7M" to "3210",
        "B" to "4322", "Bm" to "4222", "B7" to "2322", "Bm7" to "2222",
    )

    private val VIOLAO = mapOf(
        "C" to "x32010", "Cm" to "x35543", "C7" to "x32310", "C7M" to "x32000", "C#" to "x46664", "C#m" to "x46654",
        "D" to "xx0232", "Dm" to "xx0231", "D7" to "xx0212", "Dm7" to "xx0211", "D7M" to "xx0222", "Dsus4" to "xx0233", "Dsus2" to "xx0230",
        "Eb" to "x68886", "E" to "022100", "Em" to "022000", "E7" to "020100", "Em7" to "022030", "E7M" to "021100",
        "F" to "133211", "Fm" to "133111", "F7" to "131211", "F7M" to "xx3210", "F#" to "244322", "F#m" to "244222", "F#7" to "242322",
        "G" to "320003", "Gm" to "355333", "G7" to "320001", "G7M" to "320002", "Ab" to "466544", "Abm" to "466444",
        "A" to "x02220", "Am" to "x02210", "A7" to "x02020", "Am7" to "x02010", "A7M" to "x02120", "Asus4" to "x02230", "Asus2" to "x02200",
        "Bb" to "x13331", "Bbm" to "x13321", "B" to "x24442", "Bm" to "x24432", "B7" to "x21202", "Bm7" to "x24232",
    )

    private fun table(instrument: Instrument) = when (instrument) {
        Instrument.VIOLAO -> VIOLAO
        Instrument.UKULELE -> UKULELE
        else -> emptyMap()
    }

    private val parsed = HashMap<Instrument, Map<Chord, List<Int>>>()

    @Synchronized
    fun shape(chord: Chord, instrument: Instrument): List<Int>? = parsed.getOrPut(instrument) {
        table(instrument).entries.mapNotNull { (name, shape) ->
            Chord.parse(name)?.let { it to shape.map { c -> if (c == 'x') -1 else c.digitToInt() } }
        }.toMap()
    }[chord]
}

/** What a shape sounds and how a hand plays it. */
internal object FretMath {
    /**
     * Whether [f] plays [chord]: nothing outside the chord, every tone it can't do without (the
     * root, the third, the seventh…), and on a guitar the right note in the bass. [strict] adds what
     * a found shape must also respect (no string left out in the middle, the root in the bass).
     */
    fun plays(chord: Chord, f: Fingering, instrument: Instrument, strict: Boolean): Boolean {
        val strings = instrument.strings
        if (f.frets.size != strings.size) return false
        val played = strings.indices.filter { f.frets[it] >= 0 }
        // Players' own shapes may leave one more low string out (a triad on the top strings).
        val muted = if (strict || instrument.maxMuted == 0) instrument.maxMuted else instrument.maxMuted + 1
        if (played.size < strings.size - muted) return false
        val tones = chord.tones.toSet()
        val sounded = played.map { (strings[it] + f.frets[it]) % 12 }.toSet()
        if (!tones.containsAll(sounded)) return false
        val needed = chord.quality.essential.map { (chord.root + it) % 12 }.toMutableSet()
        // Five notes on four strings: the root is the one that goes (as players do with a 7(9)).
        if (needed.size > played.size) needed.remove(chord.root)
        chord.bass?.let { needed += it }
        if (!sounded.containsAll(needed)) return false
        if (strings.size >= 6) {
            val lowest = played.minBy { strings[it] + f.frets[it] }
            val bassPc = (strings[lowest] + f.frets[lowest]) % 12
            if (chord.bass != null && bassPc != chord.bass) return false
            if (strict) {
                if (chord.bass == null && bassPc != chord.root) return false
                // Strings left out only at the bass side.
                if (f.frets.drop(played.first()).any { it < 0 }) return false
            }
        }
        return true
    }

    /**
     * The fingers for [frets], or null when a hand can't. The index finger bars the lowest fret
     * when it holds three strings or more (or more notes than fingers are left) with nothing open
     * or silent in between; then one finger per fret as far as it goes, the next free finger when
     * two notes share a fret.
     */
    fun fingers(frets: List<Int>): Pair<List<Int>, List<Barre>>? {
        val pressed = frets.indices.filter { frets[it] > 0 }
        if (pressed.isEmpty()) return frets.map { 0 } to emptyList()
        val low = pressed.minOf { frets[it] }
        val atLow = pressed.filter { frets[it] == low }
        val canBarre = atLow.size >= 2 && (atLow.first()..atLow.last()).all { frets[it] >= low }
        val stretch = pressed.any { frets[it] - low >= 2 }
        val barre = canBarre && (pressed.size > 4 || (atLow.size >= 3 && (low >= 3 || stretch)))
        if (!barre && pressed.size > 4) return null
        val out = MutableList(frets.size) { 0 }
        val rest = if (barre) pressed.filter { frets[it] != low } else pressed
        if (barre) atLow.forEach { out[it] = 1 }
        var last = if (barre) 1 else 0
        for (s in rest.sortedWith(compareBy({ frets[it] }, { it }))) {
            val finger = maxOf(last + 1, frets[s] - low + 1)
            if (finger > 4) return null
            out[s] = finger
            last = finger
        }
        return out to if (barre) listOf(Barre(low, atLow.first(), atLow.last())) else emptyList()
    }
}

/** Fingerings worked out from the chord's notes, and how easy any shape is to play. */
internal object FretSearch {
    private const val HIGHEST_START = 12

    /** Every shape within four frets that plays [chord] properly, the [limit] easiest. */
    fun find(chord: Chord, instrument: Instrument, limit: Int): List<Fingering> {
        val strings = instrument.strings
        val tones = chord.tones.toSet()
        val found = HashMap<List<Int>, Double>()
        for (start in 1..HIGHEST_START) {
            val options = strings.map { open ->
                buildList {
                    if (instrument.maxMuted > 0) add(-1)
                    if (open % 12 in tones) add(0)
                    for (fret in start..start + 3) if ((open + fret) % 12 in tones) add(fret)
                }
            }
            val pick = IntArray(strings.size)
            fun walk(i: Int) {
                if (i == strings.size) {
                    val frets = pick.toList()
                    if (frets in found) return
                    val fingers = FretMath.fingers(frets) ?: return
                    if (!FretMath.plays(chord, Fingering(frets, fingers.first, fingers.second), instrument, strict = true)) return
                    val pressed = frets.filter { it > 0 }
                    if (pressed.isNotEmpty() && pressed.max() - pressed.min() > 3) return
                    found[frets] = ease(chord, frets, instrument) ?: return
                    return
                }
                for (f in options[i]) {
                    pick[i] = f
                    walk(i + 1)
                }
            }
            walk(0)
        }
        val ranked = found.entries.sortedBy { it.value }.map { it.key }
        return spread(ranked, limit) { it }.mapNotNull { frets ->
            FretMath.fingers(frets)?.let { (fingers, barres) -> Fingering(frets, fingers, barres) }
        }
    }

    /** The first [limit] of [ranked], preferring one per place on the neck, then filling up in order. */
    fun <T> spread(ranked: List<T>, limit: Int, frets: (T) -> List<Int>): List<T> {
        fun where(t: T) = frets(t).filter { it > 0 }.minOrNull() ?: 0
        val out = mutableListOf<T>()
        for (t in ranked) {
            if (out.size >= limit) break
            if (out.none { where(it) == where(t) }) out += t
        }
        for (t in ranked) {
            if (out.size >= limit) break
            if (t !in out) out += t
        }
        return out.sortedBy { ranked.indexOf(it) }
    }

    /**
     * How hard [frets] is: up the neck, a wide stretch, many fingers, a barre, strings to keep
     * silent (worst on the treble side), a missing fifth, another note than the root in the bass.
     * Null when a hand can't play it at all.
     */
    internal fun ease(chord: Chord, frets: List<Int>, instrument: Instrument): Double? {
        val strings = instrument.strings
        val played = strings.indices.filter { frets[it] >= 0 }
        if (played.isEmpty()) return null
        val pressed = frets.filter { it > 0 }
        val low = pressed.minOrNull() ?: 0
        val span = if (pressed.isEmpty()) 0 else pressed.max() - low
        if (span > 4) return null
        val hand = FretMath.fingers(frets)
        var cost = low * 0.55 + span * 1.0 + pressed.size * 0.35
        // A players' shape our simple hand can't explain uses a partial barre: about as hard as one.
        if (hand == null || hand.second.isNotEmpty()) cost += 1.4
        val first = played.first()
        cost += first * 1.2 + frets.drop(first).count { it < 0 } * 3.0
        val open = frets.count { it == 0 }
        cost += if (low <= 4) -0.15 * open else 0.4 * open
        val sounded = played.map { (strings[it] + frets[it]) % 12 }.toSet()
        if (chord.quality.intervals.contains(7) && (chord.root + 7) % 12 !in sounded) cost += 0.8
        val lowest = played.minBy { strings[it] + frets[it] }
        if ((strings[lowest] + frets[lowest]) % 12 != (chord.bass ?: chord.root)) cost += 1.0
        return cost
    }
}
