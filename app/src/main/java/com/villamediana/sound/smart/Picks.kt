package com.villamediana.sound.smart

import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.TitleCleaner
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The last step of every list of suggested songs (radios, mixes, shelves): the ranked songs cut to
 * size with variety in it — a few per artist, no song twice, songs the listener already knows only
 * up to a share while there are new ones, never the same artist twice in a row. Pure.
 */
internal object Picks {
    /**
     * How much a song suggested in the last days counts ([seen]: 0..1, 1 = today): the same songs
     * don't come back day after day, they take turns with the ones not shown yet.
     */
    const val SEEN = 0.7

    /** What is left of a song's score after it was shown ([seen] 0..1). */
    fun fresh(seen: Double?): Double = 1.0 - SEEN * (seen ?: 0.0).coerceIn(0.0, 1.0)

    /** A song suggested is remembered this many days, a little less each day. */
    const val SHOWN_DAYS = 5L

    /** How much less the last of a ranked list comes up in a [draw] than the first. */
    private const val RANK = 0.75

    /**
     * What was suggested lately, from the day each song was last [shown] (song → day number), as
     * the lists take it: song → 1 for [day] itself, less for each day before, nothing after
     * [SHOWN_DAYS]. With [today] off, only the days before: for what has to stay the same all day
     * (made again today, it must not run from what it showed this morning).
     */
    fun seen(shown: Map<String, Long>, day: Long, today: Boolean = true): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((id, at) in shown) {
            val age = (day - at).coerceAtLeast(0)
            if (age >= SHOWN_DAYS || (age == 0L && !today)) continue
            out[id] = 1.0 - age.toDouble() / SHOWN_DAYS
        }
        return out
    }

    /** [shown] with [ids] suggested on [day], and what is too old to count forgotten. */
    fun mark(shown: Map<String, Long>, ids: Collection<String>, day: Long): Map<String, Long> =
        shown.filterValues { day - it < SHOWN_DAYS } + ids.filter { it.isNotBlank() }.associateWith { day }

    /**
     * [count] of [ranked] (best first), drawn: the first ones come up most and the ones [seen] in
     * the last days least, so a list made from the same songs every day is not the same list every
     * day. All of them, as they are, when there are no more than [count].
     */
    fun <T> draw(ranked: List<T>, count: Int, rnd: Random, id: (T) -> String, seen: (T) -> Double? = { null }): List<T> {
        if (count <= 0) return emptyList()
        if (ranked.size <= count) return ranked
        val place = HashMap<String, Int>()
        ranked.forEachIndexed { i, t -> place.putIfAbsent(id(t), i) }
        return weightedOrder(
            ranked.distinctBy(id),
            id = { id(it).hashCode().toLong() },
            weight = { t -> (1.0 - RANK * place.getValue(id(t)) / ranked.size) * fresh(seen(t)) },
            rnd = rnd,
        ).take(count)
    }

    /**
     * The first [count] of [ranked]: at most [perArtist] per artist, no song twice (another upload
     * of it either), and at most [knownShare] of them [known] to the listener — the others known
     * wait at the end, and come in only when nothing new is left.
     */
    fun cut(
        ranked: List<RemoteTrack>,
        count: Int,
        perArtist: Int,
        known: (RemoteTrack) -> Boolean = { false },
        knownShare: Double = 1.0,
    ): List<RemoteTrack> {
        if (count <= 0) return emptyList()
        val maxKnown = if (knownShare >= 1.0) Int.MAX_VALUE else ceil(count * knownShare).toInt()
        val perA = HashMap<String, Int>()
        val songs = HashSet<String>()
        val out = ArrayList<RemoteTrack>(count)
        val waiting = ArrayList<RemoteTrack>()
        var knownIn = 0
        fun take(t: RemoteTrack): Boolean {
            val a = artistKey(t.artist)
            if ((perA[a] ?: 0) >= perArtist) return false
            // Another upload of the same song ("… (Official Video)", "… - Ao Vivo" of it) is the same song.
            if (!songs.add(a + "|" + normalizeText(TitleCleaner.baseTitle(t.title)))) return false
            perA.merge(a, 1, Int::plus)
            out += t
            return true
        }
        for (t in ranked) {
            if (out.size >= count) break
            if (known(t)) {
                if (knownIn >= maxKnown) {
                    waiting += t
                    continue
                }
                if (take(t)) knownIn++
            } else take(t)
        }
        for (t in waiting) {
            if (out.size >= count) break
            take(t)
        }
        return apart(out)
    }

    /** Same order, but never the same artist twice in a row when it can be helped. */
    fun apart(order: List<RemoteTrack>): List<RemoteTrack> {
        val left = order.toMutableList()
        val out = ArrayList<RemoteTrack>(order.size)
        while (left.isNotEmpty()) {
            val prev = out.lastOrNull()?.let { artistKey(it.artist) }
            val at = left.indices.take(4).firstOrNull { artistKey(left[it].artist) != prev } ?: 0
            out += left.removeAt(at)
        }
        return out
    }
}
