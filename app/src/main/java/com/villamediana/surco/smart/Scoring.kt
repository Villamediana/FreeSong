package com.villamediana.surco.smart

import com.villamediana.surco.data.db.Track
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

internal const val HOUR_MS = 3_600_000L
internal const val DAY_MS = 24 * HOUR_MS

private val FEATURING = Regex("\\b(?:feat|ft|featuring|part|participacao especial|con|with)\\b")
private val VARIOUS = setOf("various artists", "varios artistas", "varios", "various", "va", "artistas varios")

/** Main artist of a credit, normalized: "Anitta feat. Maluma" → "anitta". */
internal fun artistKey(name: String?): String {
    val n = normalizeText(name)
    val cut = FEATURING.find(n)?.range?.first ?: -1
    return if (cut > 0) n.substring(0, cut).trim() else n
}

/** A track with its comparison keys computed once (the algorithms compare them many times). */
internal class Feat(val track: Track) {
    val id: Long get() = track.id
    val artist: String = artistKey(track.artist)
    val albumArtist: String = artistKey(track.albumArtistOrArtist).takeIf { it !in VARIOUS }.orEmpty()
    val album: String? = track.albumKey?.normalized
    val genre: String = Genres.key(track.genre)
    val family: GenreFamily? = Genres.familyOfKey(genre)
    val year: Int? = track.year
    val song: String = normalizeText(track.title) + "\u0001" + artist
    /** Whole credit, padded, to spot featured artists (" anitta feat maluma "). */
    val credit: String = " " + normalizeText(track.artist) + " "
}

internal fun List<Track>.feats(): List<Feat> = map(::Feat)

/**
 * How much the listener likes a track right now. 1.0 = neutral; liked and often-finished songs go
 * up, often-skipped and just-played ones go down. Always > 0.
 */
internal fun tasteWeight(t: Track, taste: Taste): Double {
    var w = 1.0
    if (t.liked) w *= 2.0
    val recent = taste.recentPlays[t.id] ?: 0
    w *= 1.0 + 0.35 * ln(1.0 + recent)
    w *= 1.0 + 0.10 * ln(1.0 + t.playCount)
    val total = t.playCount + t.skipCount
    if (total > 0) w *= 1.0 - 0.7 * (t.skipCount.toDouble() / (total + 1))
    val last = t.lastPlayedAt
    w *= when {
        t.id in taste.justPlayed -> 0.12
        last != null && taste.now - last in 0 until 3 * HOUR_MS -> 0.25
        else -> 1.0
    }
    return w.coerceAtLeast(0.02)
}

/**
 * Weighted random order (Efraimidis–Spirakis): every element exactly once, heavier ones tend to
 * come first. Sorted by id before drawing so the result depends only on the set and the seed.
 */
internal fun <T> weightedOrder(items: List<T>, id: (T) -> Long, weight: (T) -> Double, rnd: Random): List<T> {
    val stable = items.sortedBy(id)
    val keys = DoubleArray(stable.size) { i -> ln(1.0 - rnd.nextDouble()) / weight(stable[i]) }
    return stable.indices.sortedWith(compareByDescending<Int> { keys[it] }.thenBy { id(stable[it]) }).map { stable[it] }
}

internal fun weightedOrder(items: List<Feat>, taste: Taste, rnd: Random): List<Feat> =
    weightedOrder(items, { it.id }, { tasteWeight(it.track, taste) }, rnd)

/** Artist + genre affinity between two tracks; 0 = unrelated. */
internal fun affinity(a: Feat, b: Feat): Double {
    var s = 0.0
    when {
        a.artist.isNotEmpty() && a.artist == b.artist -> s += 3.0
        a.albumArtist.isNotEmpty() && (a.albumArtist == b.albumArtist || a.albumArtist == b.artist || a.artist == b.albumArtist) -> s += 1.5
        featured(a, b) -> s += 1.0
    }
    s += 2.0 * Genres.relationOfKeys(a.genre, a.family, b.genre, b.family)
    return s
}

private fun featured(a: Feat, b: Feat): Boolean =
    (a.artist.length >= 3 && b.credit.contains(" ${a.artist} ")) || (b.artist.length >= 3 && a.credit.contains(" ${b.artist} "))

internal fun yearBonus(a: Int?, b: Int?): Double {
    if (a == null || b == null || a <= 0 || b <= 0) return 0.0
    val d = abs(a - b)
    return when {
        d <= 2 -> 0.8
        d <= 5 -> 0.5
        d <= 10 -> 0.2
        else -> 0.0
    }
}

/** Similarity used by radio and continuation: affinity plus era, only when there is some affinity. */
internal fun similarity(a: Feat, b: Feat): Double {
    val aff = affinity(a, b)
    return if (aff <= 0.0) 0.0 else aff + yearBonus(a.year, b.year)
}

/**
 * Picks up to [limit] tracks from [pool] that fit [score] (> 0 = related), favouring taste and
 * keeping it varied: at most ~a quarter of the picks per artist while there is choice, no artist
 * twice in a row when avoidable. Unrelated tracks only fill what related ones cannot.
 */
internal fun pickSimilar(
    pool: List<Feat>,
    score: (Feat) -> Double,
    taste: Taste,
    limit: Int,
    rnd: Random,
    previous: Feat?,
): List<Feat> {
    if (limit <= 0 || pool.isEmpty()) return emptyList()
    val stable = pool.sortedBy { it.id }
    val scores = stable.map(score)
    val related = stable.indices.filter { scores[it] > 0.0 }
        .map { i -> i to scores[i] + 0.6 * ln(tasteWeight(stable[i].track, taste)) + rnd.nextDouble() * 1.2 }
        .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { stable[it.first].id })
        .map { stable[it.first] }

    val maxPerArtist = max(2, ceil(limit * 0.25).toInt())
    val perArtist = HashMap<String, Int>()
    val chosen = ArrayList<Feat>(limit)
    val deferred = ArrayList<Feat>()
    for (f in related) {
        if (chosen.size >= limit) break
        val c = perArtist[f.artist] ?: 0
        if (c >= maxPerArtist) deferred += f else {
            chosen += f
            perArtist[f.artist] = c + 1
        }
    }
    if (chosen.size < limit) chosen += deferred.take(limit - chosen.size)
    if (chosen.size < limit) {
        val unrelated = stable.indices.filter { scores[it] <= 0.0 }.map { stable[it] }
        chosen += weightedOrder(unrelated, taste, rnd).take(limit - chosen.size)
    }
    return spread(chosen, previous)
}

/**
 * Re-orders [ordered] so the same artist never plays twice in a row when that is avoidable and
 * songs of one album are spread out, staying as close as possible to the given order (which carries
 * the preference). When one artist has more than half of the songs, it is interleaved as evenly as
 * possible — the minimum number of repeats is unavoidable then.
 */
internal fun spread(ordered: List<Feat>, previous: Feat? = null): List<Feat> {
    val n = ordered.size
    if (n <= 1) return ordered
    val counts = HashMap<String, Int>()
    ordered.forEach { counts.merge(it.artist, 1, Int::plus) }
    val distinctAlbums = ordered.mapNotNull { it.album }.distinct().size
    val albumWindow = minOf(3, distinctAlbums - 1).coerceAtLeast(0)
    val recentAlbums = ArrayDeque<String>()

    val remaining = ArrayList(ordered)
    val out = ArrayList<Feat>(n)
    var prev: String? = previous?.artist
    while (remaining.isNotEmpty()) {
        val left = remaining.size
        var top1Artist: String? = null
        var top1 = 0
        var top2 = 0
        for ((a, c) in counts) {
            if (c > top1) {
                top2 = top1; top1 = c; top1Artist = a
            } else if (c > top2) top2 = c
        }
        val feasibleNow = (prev == null || (counts[prev] ?: 0) <= left / 2) && top1 <= (left + 1) / 2

        fun feasibleAfter(artist: String): Boolean {
            val after = left - 1
            val own = (counts[artist] ?: 0) - 1
            val maxOther = if (artist == top1Artist) top2 else top1
            return own <= after / 2 && maxOther <= (after + 1) / 2
        }

        var pick = -1
        if (feasibleNow) {
            for (strict in booleanArrayOf(true, false)) {
                for (i in remaining.indices) {
                    val f = remaining[i]
                    if (f.artist == prev || !feasibleAfter(f.artist)) continue
                    if (strict && f.album != null && f.album in recentAlbums) continue
                    pick = i
                    break
                }
                if (pick >= 0) break
            }
        } else {
            // Repeats are unavoidable: alternate the dominant artist with everyone else.
            pick = if (prev != top1Artist) remaining.indexOfFirst { it.artist == top1Artist }
            else remaining.indexOfFirst { it.artist != prev }
        }
        if (pick < 0) pick = 0
        val f = remaining.removeAt(pick)
        out += f
        counts[f.artist] = (counts[f.artist] ?: 1) - 1
        if (counts[f.artist] == 0) counts.remove(f.artist)
        prev = f.artist
        if (albumWindow > 0 && f.album != null) {
            recentAlbums.addLast(f.album)
            while (recentAlbums.size > albumWindow) recentAlbums.removeFirst()
        }
    }
    return out
}

/** Removes repeated songs (same title + main artist), keeping the first. */
internal fun List<Feat>.distinctSongs(exclude: Set<String> = emptySet()): List<Feat> {
    val seen = HashSet(exclude)
    return filter { seen.add(it.song) }
}
