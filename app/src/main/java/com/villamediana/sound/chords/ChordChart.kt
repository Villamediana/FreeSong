package com.villamediana.sound.chords

import com.villamediana.sound.data.ParsedLyrics
import kotlinx.serialization.Serializable

/** A chord over a stretch of the song, in the recording's milliseconds. */
@Serializable
data class TimedChord(val startMs: Long, val endMs: Long, val root: Int, val quality: ChordQuality, val bass: Int? = null) {
    val chord: Chord get() = Chord(root, quality, bass)
}

/** The chords heard in a song, in order, with its key (which also says how to spell them). */
@Serializable
data class ChordChart(
    val chords: List<TimedChord>,
    val key: MusicKey? = null,
    val bpm: Double = 0.0,
    val version: Int = VERSION,
) {
    val spelling: Spelling get() = Spelling.forKey(key)

    /** Each chord once, in the order it first comes. */
    val distinct: List<Chord> get() = chords.map { it.chord }.distinct()

    fun name(index: Int): String = chords[index].chord.name(spelling)

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
        /** Bumped when the hearing changes enough that charts saved before should be heard again. */
        const val VERSION = 1
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
 */
object ChordLayout {
    /** Chords change a little before the voice comes in; that much early still counts for the line. */
    const val LEAD_MS = 450L

    /** Roughly how long a line takes to sing, from its length: generous, so slow songs fit. */
    internal fun sungMs(text: String): Long = maxOf(3_000L, text.length * 160L + 1_500L)

    /** A pause shorter than this after a line is not a row of its own. */
    private const val MIN_GAP_ROW_MS = 3_000L

    fun rows(lyrics: ParsedLyrics, chart: ChordChart, durationMs: Long): List<ChordRow> {
        if (!lyrics.synced || lyrics.lines.isEmpty()) return timeline(chart)
        val chords = chart.chords
        if (chords.isEmpty()) return lyrics.lines.mapIndexed { i, l -> ChordRow.Lyric(i, l.text, l.timeMs, emptyList()) }
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
