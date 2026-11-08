package com.villamediana.sound.chords

import com.villamediana.sound.data.LyricLine
import com.villamediana.sound.data.ParsedLyrics
import kotlinx.serialization.Serializable

/** A chord over a stretch of the song, in the recording's milliseconds. */
@Serializable
data class TimedChord(val startMs: Long, val endMs: Long, val root: Int, val quality: ChordQuality, val bass: Int? = null) {
    val chord: Chord get() = Chord(root, quality, bass)
}

/**
 * A song's chords with its key (which also says how to spell them): heard, in time ([chords]), or
 * written by a player ([sheet], [chords] empty). [looked] says the written sheet was asked for and
 * got an answer — a chart heard without that (no connection then) asks again later.
 */
@Serializable
data class ChordChart(
    val chords: List<TimedChord>,
    val key: MusicKey? = null,
    val bpm: Double = 0.0,
    val version: Int = VERSION,
    val sheet: WrittenSheet? = null,
    val looked: Boolean = false,
) {
    val spelling: Spelling get() = Spelling.forKey(key)

    val empty: Boolean get() = chords.isEmpty() && sheet?.names.isNullOrEmpty()

    /** Each chord once, in the order it first comes. */
    val distinct: List<Chord> get() = (sheet?.names?.mapNotNull { Chord.closest(it) } ?: chords.map { it.chord }).distinct()

    fun name(index: Int): String = chords[index].chord.name(spelling)

    /** What the rows' chord indices name: the heard chords in time, or the sheet's as written. */
    fun labels(): List<String> = sheet?.names ?: chords.indices.map { name(it) }

    /**
     * The same chords [semitones] up (down when negative), the key with them and the names spelled
     * for the new key; the rows don't move. A whole octave is the chart itself.
     */
    fun transposed(semitones: Int): ChordChart {
        val n = Math.floorMod(semitones, 12)
        if (n == 0) return this
        val moved = key?.let { MusicKey(Math.floorMod(it.tonic + n, 12), it.minor) }
        val spelling = Spelling.forKey(moved)
        return copy(
            chords = chords.map { c -> c.copy(root = (c.root + n) % 12, bass = c.bass?.let { (it + n) % 12 }) },
            key = moved,
            sheet = sheet?.let { s ->
                s.copy(lines = s.lines.map { l -> l.copy(chords = l.chords.map { it.copy(name = Chord.transposeName(it.name, n, spelling)) }) })
            },
        )
    }

    /** The chord a row's index plays (a written one by its closest shape). */
    fun chordAt(index: Int): Chord? = if (sheet != null) sheet.names.getOrNull(index)?.let { Chord.closest(it) } else chords.getOrNull(index)?.chord

    /** The chord ringing at [ms]: −1 before the first one. */
    fun indexAt(ms: Long): Int {
        var lo = 0
        var hi = chords.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chords[mid].startMs <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        /** Bumped when the hearing changes enough that charts saved before should be heard again (2: BTC). */
        const val VERSION = 2
    }
}

/** A chord placed in a lyric line: over character [at] ([at] = text length means after the words). */
data class PlacedChord(val at: Int, val index: Int)

/** What the chords view shows, top to bottom. */
sealed interface ChordRow {
    val startMs: Long

    /** A lyric line with its chords over the words. */
    data class Lyric(val line: Int, val text: String, override val startMs: Long, val chords: List<PlacedChord>) : ChordRow

    /** Chords alone: the intro, a solo, the space between verses, the end. */
    data class Instrumental(override val startMs: Long, val chords: List<Int>) : ChordRow
}

/**
 * Puts the chords over the lyrics. A synced line knows when it starts, not when each word is sung,
 * so a chord goes over the word it falls in proportionally to the time the line is sung — snapped
 * to the start of a word. What is played where nobody sings (intro, solos, the end) becomes a row
 * of chords alone. Every line shows the chord it starts on, so a player never has to look up.
 * Lyrics without times get guessed ones ([estimated]).
 */
object ChordLayout {
    /** Chords change a little before the voice comes in; that much early still counts for the line. */
    const val LEAD_MS = 450L

    /** Roughly how long a line takes to sing, from its length: generous, so slow songs fit. */
    internal fun sungMs(text: String): Long = maxOf(3_000L, text.length * 160L + 1_500L)

    /** A pause shorter than this after a line is not a row of its own. */
    private const val MIN_GAP_ROW_MS = 3_000L

    /** The rows, and whether they follow the song (known times) or stay still (guessed, or a sheet as written). */
    data class View(val rows: List<ChordRow>, val follows: Boolean)

    fun view(lyrics: ParsedLyrics, chart: ChordChart, durationMs: Long): View {
        chart.sheet?.let { sheet ->
            val (rows, follows) = SheetLayout.layout(lyrics, sheet, durationMs)
            return View(rows, follows)
        }
        val hasWords = lyrics.lines.any { it.text.isNotBlank() }
        return View(rows(lyrics, chart, durationMs), follows = lyrics.synced || !hasWords)
    }

    fun rows(lyrics: ParsedLyrics, chart: ChordChart, durationMs: Long): List<ChordRow> {
        chart.sheet?.let { return SheetLayout.layout(lyrics, it, durationMs).rows }
        if (lyrics.lines.none { it.text.isNotBlank() }) return timeline(chart)
        val chords = chart.chords
        if (chords.isEmpty()) return lyrics.lines.mapIndexed { i, l -> ChordRow.Lyric(i, l.text, l.timeMs, emptyList()) }
        // Lyrics without times still get their chords over the words, at a guessed place.
        if (!lyrics.synced) return rows(estimated(lyrics, chart, durationMs), chart, durationMs)
        val songEnd = maxOf(durationMs, chords.last().endMs)
        val lines = lyrics.lines
        val out = mutableListOf<ChordRow>()

        // The intro: everything before the first line, the chord it starts on included.
        val firstStart = lines.first().timeMs - LEAD_MS
        val intro = chords.indices.filter { chords[it].startMs < firstStart }
        if (intro.isNotEmpty() && firstStart > 0) out += ChordRow.Instrumental(chords[intro.first()].startMs, intro)

        for ((i, line) in lines.withIndex()) {
            val t = line.timeMs
            val next = lines.getOrNull(i + 1)?.timeMs?.minus(LEAD_MS) ?: songEnd
            // The chord ringing as the line starts (a change just before the voice is already it).
            val startIdx = chart.indexAt(t).coerceAtLeast(0)
            if (line.text.isBlank()) {
                val inside = (startIdx until chords.size).takeWhile { k -> k == startIdx || chords[k].startMs < next }
                out += ChordRow.Instrumental(t, inside)
                continue
            }
            val sungEnd = minOf(next, t + sungMs(line.text)).let { if (next - it < MIN_GAP_ROW_MS) next else it }
            val span = (sungEnd - t).coerceAtLeast(1)
            val placed = mutableListOf(PlacedChord(0, startIdx))
            var k = startIdx + 1
            while (k < chords.size && chords[k].startMs < sungEnd) {
                val frac = ((chords[k].startMs - t).toDouble() / span).coerceIn(0.0, 0.999)
                placed += PlacedChord(slot(line.text, (frac * line.text.length).toInt(), placed), k)
                k++
            }
            out += ChordRow.Lyric(i, line.text, t, placed)
            // Played after the words, before the next line: a row of its own.
            val gap = mutableListOf<Int>()
            while (k < chords.size && chords[k].startMs < next) gap += k++
            if (gap.isNotEmpty()) out += ChordRow.Instrumental(chords[gap.first()].startMs, gap)
        }
        return out
    }

    /** A blank line between verses in lyrics without times: about two bars of the band alone. */
    private const val VERSE_GAP_MS = 4_000L

    /** The most a break between verses is given when there is time to spare: a solo's eight bars or so. */
    private const val MAX_BREAK_MS = 20_000L

    /**
     * Times for lyrics that have none, guessed so the chords can go over the words: the lines spread
     * over the song between an intro and an ending of the usual length, each taking the time its
     * length needs to be sung (a blank line between verses, a stretch of the band alone), and
     * each pulled onto a chord change close by (a line starts where the harmony moves, as a rule).
     * A guess: it can be off by a line or more.
     */
    internal fun estimated(lyrics: ParsedLyrics, chart: ChordChart, durationMs: Long): ParsedLyrics {
        val chords = chart.chords
        // Runs of blank lines are one break; none at either end.
        val texts = lyrics.lines.map { it.text.trim() }
            .filterIndexed { i, t -> t.isNotEmpty() || (i > 0 && lyrics.lines[i - 1].text.isNotBlank()) }
            .dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (texts.isEmpty()) return ParsedLyrics(true, emptyList())
        val end = maxOf(durationMs, chords.lastOrNull()?.endMs ?: 0L)
        val nominal = texts.map { if (it.isEmpty()) VERSE_GAP_MS else sungMs(it) }
        var from = (end * 0.07).toLong().coerceIn(4_000L, 18_000L)
        var to = end - (end * 0.08).toLong().coerceIn(5_000L, 25_000L)
        // A short song (or lyrics far longer than it) uses all of it.
        if (to - from < nominal.sum() / 2) { from = 0; to = end }
        // Time to spare (few lines for a long song) goes to the breaks between verses first — that's
        // where the band plays alone — then to the lines; too little squeezes everything alike.
        val span = (to - from).toDouble()
        val breaks = texts.count { it.isEmpty() }
        val sung = nominal.sum().toDouble() - breaks * VERSE_GAP_MS
        val spare = span - nominal.sum()
        val breakMs = if (spare > 0 && breaks > 0) VERSE_GAP_MS + minOf(spare / breaks, (MAX_BREAK_MS - VERSE_GAP_MS).toDouble()) else VERSE_GAP_MS.toDouble()
        val lineScale = if (spare > 0) (span - breaks * breakMs) / sung else span / nominal.sum()
        val length = texts.indices.map { i -> if (texts[i].isEmpty()) (if (spare > 0) breakMs else breakMs * lineScale) else nominal[i] * lineScale }
        val pull = (length.filterIndexed { i, _ -> texts[i].isNotEmpty() }.average() * 0.35).toLong().coerceIn(400L, 2_000L)
        val out = ArrayList<LyricLine>(texts.size)
        var t = from.toDouble()
        var last = -1L
        for ((i, text) in texts.withIndex()) {
            val guess = t.toLong()
            val change = chords.minByOrNull { kotlin.math.abs(it.startMs - guess) }?.startMs
            var at = if (change != null && kotlin.math.abs(change - guess) <= pull) change else guess
            if (at <= last) at = maxOf(guess, last + 1)
            out += LyricLine(at, text)
            last = at
            t += length[i]
        }
        return ParsedLyrics(true, out)
    }

    /**
     * Where a chord falling at character [at] goes: the start of the word it falls in, or the next
     * word when that one already has a chord; after the words when none is left.
     */
    internal fun slot(text: String, at: Int, taken: List<PlacedChord>): Int {
        val starts = text.indices.filter { (it == 0 || text[it - 1] == ' ') && text[it] != ' ' }
        val used = taken.map { it.at }.toSet()
        val word = starts.lastOrNull { it <= at } ?: starts.firstOrNull() ?: 0
        return starts.firstOrNull { it >= word && it !in used } ?: text.length
    }

    /** No synced lyrics: the chords in rows of four bars (or eight chords), in time. */
    fun timeline(chart: ChordChart): List<ChordRow> {
        val chords = chart.chords
        if (chords.isEmpty()) return emptyList()
        val barMs = if (chart.bpm > 0) 4 * 60_000.0 / chart.bpm else 0.0
        val rows = mutableListOf<ChordRow.Instrumental>()
        var current = mutableListOf<Int>()
        var rowStart = chords.first().startMs
        for (k in chords.indices) {
            val full = if (barMs > 0) chords[k].startMs - rowStart >= 4 * barMs else current.size >= 8
            if (current.isNotEmpty() && (full || current.size >= 8)) {
                rows += ChordRow.Instrumental(chords[current.first()].startMs, current)
                current = mutableListOf()
                rowStart = chords[k].startMs
            }
            current += k
        }
        if (current.isNotEmpty()) rows += ChordRow.Instrumental(chords[current.first()].startMs, current)
        return rows
    }

    /** The row being played at [ms]: the last one that has started (−1 before any). */
    fun rowAt(rows: List<ChordRow>, ms: Long): Int {
        var ans = -1
        for (i in rows.indices) if (rows[i].startMs <= ms) ans = i else break
        return ans
    }
}
