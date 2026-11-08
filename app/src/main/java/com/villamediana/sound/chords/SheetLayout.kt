package com.villamediana.sound.chords

import com.villamediana.sound.data.ParsedLyrics
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Puts a written sheet's chords over the app's own lyrics, word for word. The sheet and the lyrics
 * rarely agree line for line — a sheet writes the chorus once, breaks lines elsewhere, spells words
 * its own way — so each lyric line is matched to the sheet line (or two lines) it sings, a best
 * path over the whole song where going on to the next line is free, staying on one line or skipping
 * ahead costs a little and going back (a repeated chorus) a bit more; a line found nowhere carries no
 * chords. Inside a line, the words are aligned and every chord goes over the word, and the letter,
 * it was written over. Lines of chords alone come in where the sheet has them: the intro, a solo
 * (on the lyrics' blank line when there is one), the end.
 *
 * Lyrics that don't match the sheet — or no lyrics — show the sheet as it is written.
 */
internal object SheetLayout {
    data class Result(val rows: List<ChordRow>, val follows: Boolean)

    /** Whether [sheet] is of these [lyrics]: most of their lines are sung on its lines. */
    fun fits(lyrics: ParsedLyrics, sheet: WrittenSheet): Boolean {
        val ourWords = lyrics.lines.mapIndexed { i, l -> words(l.text, i) }.filter { it.isNotEmpty() }
        val lyricLines = sheet.lines.indices.filter { sheet.lines[it].lyric && words(sheet.lines[it].text).isNotEmpty() }
        if (ourWords.isEmpty() || lyricLines.isEmpty()) return false
        val states = states(sheet, lyricLines, sheet.firstIndex())
        return path(ourWords, states, Matcher()).count { it != states.size } >= ourWords.size * MIN_COVER
    }

    fun layout(lyrics: ParsedLyrics, sheet: WrittenSheet, durationMs: Long): Result {
        val ours = lyrics.lines
        val ourWords = ours.mapIndexed { i, l -> words(l.text, i) }
        val content = ours.indices.filter { ourWords[it].isNotEmpty() }
        val first = sheet.firstIndex()
        val lyricLines = sheet.lines.indices.filter { sheet.lines[it].lyric && words(sheet.lines[it].text).isNotEmpty() }
        if (content.isEmpty() || lyricLines.isEmpty()) return Result(asWritten(sheet), follows = false)

        val states = states(sheet, lyricLines, first)
        val none = states.size
        val match = Matcher()
        val path = path(content.map { ourWords[it] }, states, match)
        if (path.count { it != none } < content.size * MIN_MATCHED) return Result(asWritten(sheet), follows = false)

        // Chords per lyric line, block by block (a block: lines in a row sung on the same sheet line).
        val placed = HashMap<Int, MutableList<PlacedChord>>()
        val before = HashMap<Int, List<Int>>() // our line → chord-only sheet lines played before it
        var lastSheet = -1
        var k = 0
        while (k < content.size) {
            val s = path[k]
            var end = k
            while (end + 1 < content.size && path[end + 1] == s) end++
            if (s != none) {
                val state = states[s]
                val lines = content.subList(k, end + 1)
                place(lines.flatMap { ourWords[it] }, state, lines.last(), ours[lines.last()].text.length, match) { line, at, index ->
                    placed.getOrPut(line) { mutableListOf() } += PlacedChord(at.coerceIn(0, ours[line].text.length), index)
                }
                if (state.firstLine > lastSheet) {
                    val alone = (lastSheet + 1 until state.firstLine).filter { chordsAlone(sheet, it) }
                    if (alone.isNotEmpty()) before[lines.first()] = alone
                }
                lastSheet = maxOf(lastSheet, state.lastLine)
            }
            k = end + 1
        }
        val after = (lastSheet + 1 until sheet.lines.size).filter { chordsAlone(sheet, it) }

        val rows = mutableListOf<ChordRow>()
        fun alone(lines: List<Int>, startMs: Long) {
            for (j in lines) rows += ChordRow.Instrumental(startMs, sheet.lines[j].chords.indices.map { first[j] + it })
        }
        val firstContent = content.first()
        for ((i, line) in ours.withIndex()) {
            before[i]?.let { lines ->
                // On the blank line before it, if the lyrics have one; the intro from the start.
                val blank = (i - 1 downTo 0).takeWhile { ourWords[it].isEmpty() }.lastOrNull()
                if (blank == null) {
                    alone(lines, if (i == firstContent) 0L else gapStart(lyrics, i))
                } else {
                    // Inserted where the blank line is (its row then holds them).
                    val at = rows.indexOfFirst { it is ChordRow.Lyric && it.line == blank }.takeIf { it >= 0 } ?: rows.size
                    val startMs = if (i == firstContent) 0L else ours[blank].timeMs
                    val solo = lines.map { j -> ChordRow.Instrumental(startMs, sheet.lines[j].chords.indices.map { first[j] + it }) }
                    rows.removeAll { it is ChordRow.Lyric && it.line == blank }
                    rows.addAll(at.coerceAtMost(rows.size), solo)
                }
            }
            val chords = placed[i].orEmpty().sortedBy { it.at }
            rows += ChordRow.Lyric(i, line.text, line.timeMs, chords)
        }
        if (after.isNotEmpty()) {
            val last = ours.last()
            val endMs = if (lyrics.synced) maxOf(last.timeMs, minOf(last.timeMs + ChordLayout.sungMs(last.text), durationMs)) else last.timeMs
            alone(after, endMs)
        }
        return Result(rows, follows = lyrics.synced)
    }

    /** Where the band plays alone before line [i]: after the line before has been sung, when there's room. */
    private fun gapStart(lyrics: ParsedLyrics, i: Int): Long {
        val line = lyrics.lines[i]
        val prev = lyrics.lines.getOrNull(i - 1) ?: return line.timeMs
        if (!lyrics.synced) return line.timeMs
        val sung = prev.timeMs + ChordLayout.sungMs(prev.text)
        return if (sung < line.timeMs - 1_000) maxOf(prev.timeMs, sung) else line.timeMs
    }

    private fun chordsAlone(sheet: WrittenSheet, j: Int) = !sheet.lines[j].lyric && sheet.lines[j].chords.isNotEmpty()

    /** The sheet as written: for lyrics that aren't its own (or none). */
    internal fun asWritten(sheet: WrittenSheet): List<ChordRow> {
        val first = sheet.firstIndex()
        val rows = mutableListOf<ChordRow>()
        for ((j, l) in sheet.lines.withIndex()) {
            when {
                l.lyric -> rows += ChordRow.Lyric(-1 - j, l.text, 0L, l.chords.mapIndexed { n, c -> PlacedChord(c.column.coerceIn(0, l.text.length), first[j] + n) }.sortedBy { it.at })
                l.chords.isNotEmpty() -> rows += ChordRow.Instrumental(0L, l.chords.indices.map { first[j] + it })
                rows.isNotEmpty() -> rows += ChordRow.Lyric(-1 - j, "", 0L, emptyList())
            }
        }
        return rows
    }

    // ---- The sheet lines a lyric line can be ----

    /** One sheet line, or two in a row read as one; its words and chords in the joined text. */
    private class State(val firstLine: Int, val lastLine: Int, val order: Int, val lastOrder: Int, val text: String, val words: List<Word>, val chords: List<Pair<Int, Int>>)

    private fun states(sheet: WrittenSheet, lyricLines: List<Int>, first: IntArray): List<State> {
        fun chordsOf(j: Int, offset: Int, max: Int) = sheet.lines[j].chords.mapIndexed { n, c -> minOf(c.column, max) + offset to first[j] + n }
        val singles = lyricLines.mapIndexed { o, j ->
            val text = sheet.lines[j].text
            State(j, j, o, o, text, words(text), chordsOf(j, 0, Int.MAX_VALUE))
        }
        val pairs = (0 until lyricLines.size - 1).filter { lyricLines[it + 1] == lyricLines[it] + 1 }.map { o ->
            val a = lyricLines[o]
            val b = lyricLines[o + 1]
            val ta = sheet.lines[a].text
            val text = ta + " " + sheet.lines[b].text
            State(a, b, o, o + 1, text, words(text), chordsOf(a, 0, ta.length) + chordsOf(b, ta.length + 1, Int.MAX_VALUE))
        }
        return singles + pairs
    }

    /** The likeliest sheet state for each lyric line (states.size = none). */
    private fun path(lines: List<List<Word>>, states: List<State>, match: Matcher): IntArray {
        val n = lines.size
        val none = states.size
        val count = states.size + 1
        val emit = Array(n) { i ->
            DoubleArray(count) { s ->
                if (s == none) return@DoubleArray NONE_SCORE
                val matched = match.align(lines[i], states[s].words).size
                val found = matched.toDouble() / lines[i].size
                if (matched == 0 || found < MIN_FOUND) -2.0 else found - UNUSED_COST * (1 - matched.toDouble() / states[s].words.size)
            }
        }
        val move = Array(count) { p -> DoubleArray(count) { s -> move(states, p, s) } }
        var score = DoubleArray(count) { s -> emit[0][s] + if (s == none) -LEAVE_COST else -minOf(0.5, 0.03 * states[s].order) }
        val back = Array(n) { IntArray(count) }
        for (i in 1 until n) {
            val next = DoubleArray(count)
            for (s in 0 until count) {
                var best = 0
                var bestScore = Double.NEGATIVE_INFINITY
                for (p in 0 until count) {
                    val v = score[p] + move[p][s]
                    if (v > bestScore) { bestScore = v; best = p }
                }
                next[s] = bestScore + emit[i][s]
                back[i][s] = best
            }
            score = next
        }
        val path = IntArray(n)
        path[n - 1] = score.indices.maxBy { score[it] }
        for (i in n - 1 downTo 1) path[i - 1] = back[i][path[i]]
        return path
    }

    private fun move(states: List<State>, p: Int, s: Int): Double {
        val none = states.size
        if (p == none && s == none) return 0.0
        if (p == none || s == none) return -LEAVE_COST
        val from = states[p]
        val to = states[s]
        return when {
            to.order == from.lastOrder + 1 -> 0.0
            p == s -> -STAY_COST
            to.order == from.lastOrder && to.lastOrder == to.order -> -STAY_COST
            to.order > from.lastOrder + 1 -> -minOf(0.6, 0.15 + 0.05 * (to.order - from.lastOrder - 2))
            else -> -BACK_COST
        }
    }

    /**
     * Each of [state]'s chords over the lyric word it was written over ([words] are the block's,
     * aligned with the state's): on the same letter when the word is found, between its neighbours
     * when it isn't; after the words when it was written after them.
     */
    private fun place(words: List<Word>, state: State, lastLine: Int, lastLength: Int, match: Matcher, put: (line: Int, at: Int, index: Int) -> Unit) {
        val pairs = match.align(words, state.words)
        if (pairs.isEmpty()) return
        val byState = pairs.associate { it.second to it.first }
        for ((column, index) in state.chords) {
            if (column >= state.text.trimEnd().length) {
                put(lastLine, lastLength, index)
                continue
            }
            val w = state.words.indexOfFirst { it.end > column }
            if (w < 0) {
                put(lastLine, lastLength, index)
                continue
            }
            val exact = byState[w]
            if (exact != null) {
                val o = words[exact]
                put(o.line, o.start + minOf(maxOf(0, column - state.words[w].start), o.end - o.start - 1), index)
                continue
            }
            val prev = pairs.lastOrNull { it.second < w }
            val next = pairs.firstOrNull { it.second > w }
            val o = when {
                prev != null && next != null -> {
                    val frac = (w - prev.second).toDouble() / (next.second - prev.second)
                    (prev.first + frac * (next.first - prev.first)).roundToInt().coerceIn(minOf(prev.first + 1, next.first), next.first)
                }
                next != null -> maxOf(0, next.first - (next.second - w))
                else -> minOf(words.lastIndex, prev!!.first + (w - prev.second))
            }
            put(words[o].line, words[o].start, index)
        }
    }

    // ---- Words ----

    internal class Word(val start: Int, val end: Int, val norm: String, val line: Int)

    /** The words of [text] (letters and digits, an apostrophe inside), compared without case or accents. */
    internal fun words(text: String, line: Int = 0): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetterOrDigit()) { i++; continue }
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || (text[i] in "'’" && i + 1 < text.length && text[i + 1].isLetterOrDigit()))) i++
            val norm = Normalizer.normalize(text.substring(start, i).lowercase(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && it != '\'' && it != '’' }
            if (norm.isNotEmpty()) out += Word(start, i, norm, line)
        }
        return out
    }

    /** Word comparisons for one layout, each pair of spellings compared once. */
    private class Matcher {
        private val known = HashMap<String, Boolean>()

        /** The most words of [a] and [b] matched in order (longest common subsequence): (index in a, index in b). */
        fun align(a: List<Word>, b: List<Word>): List<Pair<Int, Int>> {
            if (a.isEmpty() || b.isEmpty()) return emptyList()
            val eq = Array(a.size) { i -> BooleanArray(b.size) { j -> same(a[i].norm, b[j].norm) } }
            val dp = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
                dp[i][j] = if (eq[i][j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            val out = mutableListOf<Pair<Int, Int>>()
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                when {
                    eq[i][j] && dp[i][j] == dp[i + 1][j + 1] + 1 -> { out += i to j; i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> i++
                    else -> j++
                }
            }
            return out
        }

        /** The same word, give or take a letter (two in a long word): spellings differ between sites. */
        fun same(a: String, b: String): Boolean {
            if (a == b) return true
            val n = minOf(a.length, b.length)
            if (n < 4) return false
            val limit = if (n >= 8) 2 else 1
            if (abs(a.length - b.length) > limit) return false
            return known.getOrPut(if (a < b) "$a|$b" else "$b|$a") { distance(a, b, limit) <= limit }
        }

        private fun distance(a: String, b: String, limit: Int): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    rowMin = minOf(rowMin, cur[j])
                }
                if (rowMin > limit) return limit + 1
                prev = cur
            }
            return prev[b.length]
        }
    }

    /** At least this share of a lyric line's words must be in a sheet line for them to be the same. */
    private const val MIN_FOUND = 0.5

    /** Fewer lines than this matched: the sheet isn't of these lyrics. */
    private const val MIN_MATCHED = 0.3

    /** Another artist's sheet is taken for the song only when this much of its lyrics is in it. */
    private const val MIN_COVER = 0.6

    private const val NONE_SCORE = 0.3
    private const val UNUSED_COST = 0.2
    private const val LEAVE_COST = 0.1
    private const val STAY_COST = 0.1
    private const val BACK_COST = 0.5
}
