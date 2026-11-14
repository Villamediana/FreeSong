package com.villamediana.sound.chords

/** One chord on the play-along lane: rings from [startMs] until [endMs]; [index] is its label in the chart. */
data class LaneChord(val startMs: Long, val endMs: Long, val index: Int)

/**
 * The song's chords in time, for playing along while singing ("Tocar junto"): what rings now,
 * what comes next and when it comes.
 *
 * [chords] are in order and never overlap. [run] groups a chord with the ones right after it that
 * have the same name (a sheet writes a chord again on the next line; nothing changes for the hand),
 * so "next" is always a change.
 */
class PlayAlongLane(val chords: List<LaneChord>, labels: List<String>) {
    val run: IntArray = IntArray(chords.size).also { run ->
        var id = 0
        for (i in chords.indices) {
            if (i > 0 && labels.getOrNull(chords[i].index) != labels.getOrNull(chords[i - 1].index)) id++
            run[i] = id
        }
    }

    /** The chord ringing at [ms]: −1 before the first one. */
    fun at(ms: Long): Int {
        var lo = 0
        var hi = chords.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chords[mid].startMs <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** The first chord of the run [i] is in (the change the hand made). */
    fun runStart(i: Int): Int {
        var k = i
        while (k > 0 && run[k - 1] == run[i]) k--
        return k
    }

    /** Where the run [i] is in ends: when the hand changes next. */
    fun runEnd(i: Int): Long {
        var k = i
        while (k + 1 < chords.size && run[k + 1] == run[i]) k++
        return chords[k].endMs
    }

    /** The first chord after [i] with another name; −1 when [i]'s is the last. Before the song's first chord (−1): the first. */
    fun next(i: Int): Int {
        if (i < 0) return if (chords.isEmpty()) -1 else 0
        var k = i + 1
        while (k < chords.size && run[k] == run[i]) k++
        return if (k < chords.size) k else -1
    }
}

/**
 * Puts a song's chords in time for playing along. Heard chords already are. A player's sheet over
 * synced lyrics is put in time from the rows it was laid out in: a line is taken as sung evenly from
 * its start, so a chord over a word comes when that word is reached; chords alone (intro, solo, the
 * end) share the time until the next line evenly. Lyrics without times, or a sheet shown as it is
 * written, can't be followed: no lane then.
 */
object PlayAlong {
    /** The chord shows this much before it rings: the hand moves a little before the beat. */
    const val LEAD_MS = 220L

    /** A pause shorter than this after a line is still the line (as [ChordLayout] reads it). */
    private const val MIN_GAP_MS = 3_000L

    /** Room each chord written after the words wants (about two beats). */
    private const val AFTER_WORDS_MS = 900L

    /** Chords closer than this would flash by: they share the time to the next one instead. */
    private const val CROWDED_MS = 300L

    /** How long the last chord is held when the song's length isn't known. */
    private const val LAST_HOLD_MS = 4_000L

    fun lane(view: ChordLayout.View, chart: ChordChart, durationMs: Long): PlayAlongLane? {
        val labels = chart.labels()
        if (chart.sheet == null) {
            val heard = chart.chords
            if (heard.isEmpty()) return null
            val lane = heard.mapIndexed { k, c ->
                // Each one rings until the next comes in (a gap of silence belongs to the chord before it).
                val end = heard.getOrNull(k + 1)?.startMs ?: maxOf(c.endMs, c.startMs + 1)
                LaneChord(c.startMs, maxOf(end, c.startMs + 1), k)
            }
            return PlayAlongLane(lane, labels)
        }
        if (!view.follows) return null
        val starts = fromRows(view.rows, durationMs)
        if (starts.isEmpty()) return null
        val end = maxOf(durationMs, starts.last().first + LAST_HOLD_MS)
        val lane = starts.mapIndexed { k, (start, index) ->
            LaneChord(start, starts.getOrNull(k + 1)?.first ?: end, index)
        }
        return PlayAlongLane(lane, labels)
    }

    /**
     * When the chords of a sung line come, the line given [from]–[to]. Over the words: as far into
     * the singing as their word is into the line. After the words ("borrar  G D G D"): one after the
     * other in the time left, the singing made shorter if it would leave them too little. Chords
     * written close together never come at once: a crowded run shares the time up to what's next.
     */
    internal fun lyricTimes(row: ChordRow.Lyric, from: Long, to: Long): List<Pair<Long, Int>> {
        if (row.chords.isEmpty()) return emptyList()
        val length = row.text.trimEnd().length
        val window = (to - from).coerceAtLeast(1)
        val inside = row.chords.filter { length > 0 && it.at < length }
        val after = row.chords.filter { length == 0 || it.at >= length }
        val natural = minOf(window, ChordLayout.sungMs(row.text))
        val sung = when {
            after.isEmpty() -> if (window - natural < MIN_GAP_MS) window else natural
            inside.isEmpty() -> 0L
            // The chords after the words need room of their own (half the line at most).
            else -> minOf(natural, window - minOf(after.size * AFTER_WORDS_MS, window / 2))
        }
        val times = LongArray(row.chords.size)
        var k = 0
        for (c in inside) times[k++] = from + sung * c.at / length
        for ((n, c) in after.withIndex()) times[k++] = from + sung + (window - sung) * n / after.size
        // A run of chords closer than a quick change shares the time up to the next one (or the line's end).
        var a = 0
        while (a < times.size) {
            var b = a
            while (b + 1 < times.size && times[b + 1] - times[b] < CROWDED_MS) b++
            if (b > a) {
                val end = times.getOrNull(b + 1) ?: to
                val span = (end - times[a]).coerceAtLeast((b - a + 1).toLong())
                for (n in a..b) times[n] = times[a] + span * (n - a) / (b - a + 1)
            }
            a = b + 1
        }
        return (inside + after).mapIndexed { n, c -> times[n] to c.index }
    }

    /** When each written chord comes ([Pair] of start and label index), in order, from rows with known times. */
    internal fun fromRows(rows: List<ChordRow>, durationMs: Long): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        var i = 0
        while (i < rows.size) {
            val t = rows[i].startMs
            // Rows that start together (a sheet's chord lines put in one break) share their time.
            var j = i
            while (j + 1 < rows.size && rows[j + 1].startMs <= t) j++
            val next = (j + 1 until rows.size).map { rows[it].startMs }.firstOrNull { it > t }
                ?: maxOf(durationMs, t + LAST_HOLD_MS)
            val group = rows.subList(i, j + 1)
            val share = (next - t).toDouble() / group.size
            group.forEachIndexed { g, row ->
                val from = t + (share * g).toLong()
                val to = t + (share * (g + 1)).toLong()
                when (row) {
                    is ChordRow.Instrumental -> {
                        val n = row.chords.size
                        row.chords.forEachIndexed { k, index -> out += from + (to - from) * k / n to index }
                    }
                    is ChordRow.Lyric -> out += lyricTimes(row, from, to)
                }
            }
            i = j + 1
        }
        // In order, one after the other: two chords at the same moment keep their written order
        // (each row has spread its own; rows that start together could still meet).
        out.sortBy { it.first }
        for (k in 1 until out.size) {
            if (out[k].first <= out[k - 1].first) out[k] = out[k - 1].first + 1 to out[k].second
        }
        return out
    }
}
