package com.villamediana.sound.data

/** One timed line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/** Parsed lyrics: [lines] sorted by time when [synced], otherwise one entry per text line (time 0). */
data class ParsedLyrics(val synced: Boolean, val lines: List<LyricLine>) {
    /** Index of the line being sung at [positionMs] (-1 before the first line / when not synced). */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** Minimal LRC parser: [mm:ss.xx] tags (several per line allowed), [offset:±ms], metadata tags ignored. */
object Lrc {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun isSynced(text: String): Boolean = text.lineSequence().any { timeTag.containsMatchIn(it) }

    fun parse(text: String, synced: Boolean = isSynced(text)): ParsedLyrics {
        if (!synced) {
            return ParsedLyrics(false, text.lines().map { LyricLine(0, it.trim()) }.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() })
        }
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val lyric = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                val t = (min * 60_000 + sec * 1_000 + frac - offset).coerceAtLeast(0)
                out += LyricLine(t, lyric)
            }
        }
        out.sortBy { it.timeMs }
        return ParsedLyrics(true, out)
    }
}
