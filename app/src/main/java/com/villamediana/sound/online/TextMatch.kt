package com.villamediana.sound.online

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Accent/case-insensitive text comparison used to match songs across catalogs. */
internal object TextMatch {
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** "De Música Ligera!" → "de musica ligera" (no accents, lowercase, single spaces, letters/digits only). */
    fun key(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace("ß", "ss")
        return folded.replace(nonWord, " ").trim()
    }

    fun tokens(s: String): List<String> = key(s).split(' ').filter { it.isNotEmpty() }

    /** 0..1 — the best of an edit-distance ratio (order sensitive) and a token-set ratio (order insensitive). */
    fun similarity(a: String, b: String): Double {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        val compactA = ka.replace(" ", "")
        val compactB = kb.replace(" ", "")
        if (compactA == compactB) return 0.98
        val lev = levenshteinRatio(compactA, compactB)
        val ta = ka.split(' ').toSet()
        val tb = kb.split(' ').toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val tokenSet = if (inter == 0.0) 0.0 else 2 * inter / (ta.size + tb.size)
        return max(lev, tokenSet)
    }

    /** True when every word of the shorter text appears in the longer one ("Bad Bunny" ⊂ "Bad Bunny & Jhayco"). */
    fun contains(longer: String, shorter: String): Boolean {
        val tl = tokens(longer).toSet()
        val ts = tokens(shorter)
        return ts.isNotEmpty() && tl.containsAll(ts)
    }

    fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
