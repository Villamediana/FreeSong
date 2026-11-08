package com.villamediana.sound.chords

/**
 * From [Btc]'s frame-by-frame chances to the chords a player reads: the likeliest sequence when every
 * change has a price (Viterbi), so one frame's doubt doesn't flicker a chord in and out; stretches of
 * "no chord" and "unknown" left empty; the key from what rings longest.
 *
 * The price (8, in log-probability) was set on the benchmark: chords per minute then match the
 * musicians' annotations (24.6 against 24.4) and accuracy is at its best (77.3%, from 76.3% raw).
 */
internal object ChordReading {
    const val CHANGE_PRICE = 8f

    fun chords(logProbs: Array<FloatArray>, price: Float = CHANGE_PRICE): List<TimedChord> {
        val path = viterbi(logProbs, price)
        val out = ArrayList<TimedChord>()
        var start = 0
        for (f in 1..path.size) {
            if (f < path.size && path[f] == path[start]) continue
            Btc.chord(path[start])?.let { c ->
                out += TimedChord(ms(start), ms(f), c.root, c.quality)
            }
            start = f
        }
        return out
    }

    /** The key: the chords' notes weighed by how long each rings. */
    fun key(chords: List<TimedChord>): MusicKey? {
        val h = DoubleArray(12)
        for (c in chords) {
            val d = (c.endMs - c.startMs).toDouble()
            for (i in c.quality.intervals) h[(c.root + i) % 12] += d
        }
        return Keys.of(h)
    }

    private fun ms(frame: Int): Long = Math.round(frame * Cqt.FRAME_SECONDS * 1000)

    /** The best path when staying is free and moving costs [price]. */
    internal fun viterbi(logProbs: Array<FloatArray>, price: Float): IntArray {
        val n = logProbs.size
        if (n == 0) return IntArray(0)
        val k = logProbs[0].size
        var score = logProbs[0].copyOf()
        val back = Array(n) { IntArray(0) }
        for (t in 1 until n) {
            var best = 0
            for (c in 1 until k) if (score[c] > score[best]) best = c
            val move = score[best] - price
            val from = IntArray(k)
            val next = FloatArray(k)
            val row = logProbs[t]
            for (c in 0 until k) {
                if (score[c] >= move) { next[c] = score[c] + row[c]; from[c] = c } else { next[c] = move + row[c]; from[c] = best }
            }
            back[t] = from
            score = next
        }
        val path = IntArray(n)
        var last = 0
        for (c in 1 until k) if (score[c] > score[last]) last = c
        path[n - 1] = last
        for (t in n - 1 downTo 1) path[t - 1] = back[t][path[t]]
        return path
    }
}
