package com.villamediana.sound.smart

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** The four parts of the day the DNA tells apart. */
enum class DayPart(val hours: IntRange) {
    NIGHT_LATE(0..5),
    MORNING(6..11),
    AFTERNOON(12..17),
    EVENING(18..23);

    companion object {
        fun of(hour: Int): DayPart = entries.first { hour in it.hours }
    }
}

/**
 * The moment music is being chosen for: the part of the day, a weekday or the weekend, where the
 * phone is (a place the app learned by itself, when that is on) and who is listening along (the
 * friends in the jam).
 */
data class DnaMoment(
    val part: DayPart,
    val weekend: Boolean,
    val place: Long? = null,
    val people: Set<String> = emptySet(),
) {
    /** What the "Pra agora" mix is made for (it changes when the moment does). */
    val key: String get() = "${part.name}:${if (weekend) "we" else "wd"}:${place ?: "-"}:${people.sorted().joinToString(",")}"

    companion object {
        fun at(time: Long, zone: ZoneId = ZoneId.systemDefault(), place: Long? = null, people: Set<String> = emptySet()): DnaMoment {
            val t = Instant.ofEpochMilli(time).atZone(zone)
            return DnaMoment(DayPart.of(t.hour), t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY, place, people)
        }
    }
}

/**
 * The listener's musical DNA, learned from everything they did (pure): which artists and genres
 * belong to which part of the day, to weekdays or the weekend, to the places they listen at and to
 * the friends they listen with.
 *
 * - [lift]: how much more (or less) an artist fits a moment than usual (1 = as usual). Learned
 *   gently: a few listens move it a little, many move it more; a moment with little listening
 *   barely moves anything.
 * - Skips teach the moment, not the taste (Miguel: "pode ser uma música ou ritmo que já gosto, mas
 *   não nesse horário"): a skip counts in the part of the day and the kind of day it happened in,
 *   for the song, its artist and its genre (the "ritmo"). Heard through in that moment, it is
 *   forgiven; skips fade by half every [SKIP_HALF_LIFE_MS] (the truth comes back); a heart keeps a
 *   song from going far down. [songFactor] and [avoidNow] say it for a moment.
 */
class MusicDna private constructor(
    private val artistParts: Map<String, DoubleArray>,
    private val artistDays: Map<String, DoubleArray>,
    private val artistPlaces: Map<String, Map<Long, Double>>,
    private val artistPeople: Map<String, Map<String, Double>>,
    private val artistTotal: Map<String, Double>,
    private val genreParts: Map<String, DoubleArray>,
    private val genreDays: Map<String, DoubleArray>,
    private val genreTotal: Map<String, Double>,
    private val partTotal: DoubleArray,
    private val dayTotal: DoubleArray,
    private val placeTotal: Map<Long, Double>,
    private val personTotal: Map<String, Double>,
    private val total: Double,
    private val songs: Map<String, SongStats>,
    private val songSkips: Map<String, Skips>,
    private val artistSkips: Map<String, Skips>,
    private val genreSkips: Map<String, Skips>,
) {
    /**
     * Skips and listens heard through (fading with [SKIP_HALF_LIFE_MS]) in each moment: the part of
     * the day × weekday or weekend (8 cells).
     */
    class Skips {
        val skipped = DoubleArray(CELLS)
        val heard = DoubleArray(CELLS)

        /**
         * 0..1, how much this is skipped in [m]: its cell (that part of that kind of day) and the
         * whole part of the day weigh together; [prior] pseudo-listens keep a skip or two from
         * saying much.
         */
        fun rate(m: DnaMoment, prior: Double): Double {
            val c = cellOf(m.part, m.weekend)
            val p0 = cellOf(m.part, false)
            val p1 = cellOf(m.part, true)
            val inCell = skipped[c] / (skipped[c] + heard[c] + prior)
            val sp = skipped[p0] + skipped[p1]
            val inPart = sp / (sp + heard[p0] + heard[p1] + prior)
            return CELL_SHARE * inCell + (1 - CELL_SHARE) * inPart
        }

        /** Skips in [m]'s part of the day (any kind of day). */
        fun skipsInPart(m: DnaMoment): Double = skipped[cellOf(m.part, false)] + skipped[cellOf(m.part, true)]

        /** 0..1 over every moment. */
        fun overall(prior: Double): Double {
            val sk = skipped.sum()
            return sk / (sk + heard.sum() + prior)
        }
    }
    /** A song's listening (decayed), overall and per part of the day, and with whom. */
    class SongStats(
        val videoId: String,
        val title: String,
        val artist: String,
        val hearted: Boolean,
    ) {
        /** Listening together with each person (decayed). */
        val together = HashMap<String, Double>()
        var weight = 0.0
        val weightIn = DoubleArray(4)
        var lastAt = 0L
    }

    val isEmpty: Boolean get() = total <= 0.0

    /** How much more the artist fits [m] than usual: 1 = as usual; between [MIN_LIFT] and [MAX_LIFT]. */
    fun lift(artistKey: String, m: DnaMoment): Double {
        // Skipped in this moment (the artist, all songs): it fits it less, wherever it stands otherwise.
        val skipped = artistSkips[artistKey]?.rate(m, ARTIST_PRIOR) ?: 0.0
        val skipLog = ln((1.0 - ARTIST_SKIP * skipped).coerceAtLeast(0.05))
        val all = artistTotal[artistKey] ?: return exp(skipLog).coerceIn(MIN_LIFT, MAX_LIFT)
        if (total <= 0 || all <= 0) return exp(skipLog).coerceIn(MIN_LIFT, MAX_LIFT)
        val share = all / total
        var log = skipLog
        log += PART_WEIGHT * ln(ratio(artistParts[artistKey]?.get(m.part.ordinal) ?: 0.0, partTotal[m.part.ordinal], share))
        val d = if (m.weekend) 1 else 0
        log += DAY_WEIGHT * ln(ratio(artistDays[artistKey]?.get(d) ?: 0.0, dayTotal[d], share))
        val place = m.place
        if (place != null && (placeTotal[place] ?: 0.0) >= MIN_CONTEXT) {
            log += PLACE_WEIGHT * ln(ratio(artistPlaces[artistKey]?.get(place) ?: 0.0, placeTotal.getValue(place), share))
        }
        val known = m.people.filter { (personTotal[it] ?: 0.0) >= MIN_CONTEXT }
        if (known.isNotEmpty()) {
            val each = known.map { p -> ln(ratio(artistPeople[artistKey]?.get(p) ?: 0.0, personTotal.getValue(p), share)) }
            log += PEOPLE_WEIGHT * each.average()
        }
        return exp(log).coerceIn(MIN_LIFT, MAX_LIFT)
    }

    /** How much more a genre (home genre id) fits [m] than usual (part of the day and the week only). */
    fun genreLift(genre: String, m: DnaMoment): Double {
        // The "ritmo" skipped in this moment fits it less.
        val skipped = genreSkips[genre]?.rate(m, GENRE_PRIOR) ?: 0.0
        val skipLog = ln((1.0 - GENRE_SKIP * skipped).coerceAtLeast(0.05))
        val all = genreTotal[genre] ?: return exp(skipLog).coerceIn(MIN_LIFT, MAX_LIFT)
        if (total <= 0 || all <= 0) return exp(skipLog).coerceIn(MIN_LIFT, MAX_LIFT)
        val share = all / total
        val d = if (m.weekend) 1 else 0
        val log = skipLog + PART_WEIGHT * ln(ratio(genreParts[genre]?.get(m.part.ordinal) ?: 0.0, partTotal[m.part.ordinal], share)) +
            DAY_WEIGHT * ln(ratio(genreDays[genre]?.get(d) ?: 0.0, dayTotal[d], share))
        return exp(log).coerceIn(MIN_LIFT, MAX_LIFT)
    }

    /**
     * 0..1: how much a song is wanted in [m] (1 = no doubt). Its skips in this moment weigh most,
     * its skips anywhere a little; heard through here, they are forgiven. A heart keeps it from
     * going below [HEART_FLOOR].
     */
    fun songFactor(videoId: String, m: DnaMoment): Double {
        val sk = songSkips[videoId] ?: return 1.0
        val f = (1.0 - SONG_SKIP * sk.rate(m, SONG_PRIOR)) * (1.0 - SONG_SKIP_ANYWHERE * sk.overall(SONG_PRIOR * 2))
        val hearted = songs[videoId]?.hearted == true
        return (if (hearted) maxOf(f, HEART_FLOOR) else f).coerceIn(0.0, 1.0)
    }

    /** Every song's factor below 1 in [m] (for a profile of the moment). */
    fun songFactors(m: DnaMoment): Map<String, Double> =
        songSkips.keys.mapNotNull { id -> songFactor(id, m).takeIf { it < 0.999 }?.let { id to it } }.toMap()

    /**
     * The songs that clearly don't fit [m]: skipped in this part of the day again and again and
     * hardly heard through in it. Left out of what plays now — they stay in other moments, and come
     * back as the skips fade. Never a hearted one.
     */
    fun avoidNow(m: DnaMoment): Set<String> = songSkips.entries.filter { (id, sk) ->
        songs[id]?.hearted != true && sk.skipsInPart(m) >= AVOID_SKIPS && sk.rate(m, SONG_PRIOR) >= AVOID_RATE
    }.mapTo(HashSet()) { it.key }

    /**
     * Your songs that fit [m] best: heard a lot in this part of the day (and with these people,
     * at this place), the ones that fit the moment's artists.
     */
    fun songsFor(m: DnaMoment, count: Int): List<SongStats> {
        val part = m.part.ordinal
        return songs.values
            .filter { it.weight > 0 && songFactor(it.videoId, m) >= SONGS_FOR_FLOOR }
            .map { s ->
                val here = s.weightIn[part] / (s.weight + 1.0)
                val people = if (m.people.isEmpty()) 0.0 else m.people.sumOf { s.together[it] ?: 0.0 }
                val score = (s.weight.pow(0.5) * (0.4 + 1.6 * here) + 1.5 * people) * lift(artistKey(s.artist), m) * songFactor(s.videoId, m)
                s to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { artistKey(it.artist) + "|" + normalizeText(it.title) }
            .take(count)
    }

    /** The songs heard together with [people] (in jams), most heard first. */
    fun together(people: Set<String>, count: Int): List<SongStats> =
        songs.values.map { s -> s to people.sumOf { s.together[it] ?: 0.0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(count)

    /** The smoothed share of an artist in a context, against its usual share. */
    private fun ratio(inContext: Double, contextTotal: Double, share: Double): Double =
        ((inContext + PRIOR * share) / (contextTotal + PRIOR)) / share

    companion object {
        /** Listening older than this fades to half (the DNA is a long memory). */
        const val HALF_LIFE_MS = 60L * 24 * 3_600_000L
        /** Pseudo-listening that keeps a few plays from moving everything. */
        const val PRIOR = 6.0
        const val MIN_LIFT = 0.35
        const val MAX_LIFT = 3.0
        const val PART_WEIGHT = 1.0
        const val DAY_WEIGHT = 0.5
        const val PLACE_WEIGHT = 0.8
        const val PEOPLE_WEIGHT = 1.0
        /** A place or a friend counts once there is this much listening with it. */
        const val MIN_CONTEXT = 3.0

        /** Skips (and what forgives them) fade by half in this time: what was skipped comes back. */
        const val SKIP_HALF_LIFE_MS = 30L * 24 * 3_600_000L
        /** Moments: part of the day × weekday/weekend. */
        const val CELLS = 8
        /** In a skip rate, the moment's own cell against its whole part of the day. */
        const val CELL_SHARE = 0.6
        /** Pseudo-listens (a song, an artist, a genre): a skip or two only nudge. */
        const val SONG_PRIOR = 1.5
        const val ARTIST_PRIOR = 3.0
        const val GENRE_PRIOR = 5.0
        /** How far skips in the moment push down a song, an artist, a genre. */
        const val SONG_SKIP = 0.7
        const val SONG_SKIP_ANYWHERE = 0.2
        const val ARTIST_SKIP = 0.5
        const val GENRE_SKIP = 0.4
        /** A hearted song never goes below this in a moment. */
        const val HEART_FLOOR = 0.65
        /** Out of the moment: this many (fading) skips in its part of the day, and skipped this much there. */
        const val AVOID_SKIPS = 2.5
        const val AVOID_RATE = 0.58
        /** Not among the moment's own songs below this. */
        const val SONGS_FOR_FLOOR = 0.45

        fun cellOf(part: DayPart, weekend: Boolean): Int = part.ordinal * 2 + (if (weekend) 1 else 0)
        /**
         * Builds the DNA from [listens] (each with its time, and where and with whom when known),
         * [hearts] (video ids) and [genreOf] (an artist key → home genre id).
         */
        fun build(
            listens: List<Listen>,
            hearts: Set<String>,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            genreOf: (artistKey: String, songGenre: String?) -> String? = { _, g -> g },
        ): MusicDna {
            val artistParts = HashMap<String, DoubleArray>()
            val artistDays = HashMap<String, DoubleArray>()
            val artistPlaces = HashMap<String, HashMap<Long, Double>>()
            val artistPeople = HashMap<String, HashMap<String, Double>>()
            val artistTotal = HashMap<String, Double>()
            val genreParts = HashMap<String, DoubleArray>()
            val genreDays = HashMap<String, DoubleArray>()
            val genreTotal = HashMap<String, Double>()
            val partTotal = DoubleArray(4)
            val dayTotal = DoubleArray(2)
            val placeTotal = HashMap<Long, Double>()
            val personTotal = HashMap<String, Double>()
            var total = 0.0
            val songs = HashMap<String, SongStats>()
            val songSkips = HashMap<String, Skips>()
            val artistSkips = HashMap<String, Skips>()
            val genreSkips = HashMap<String, Skips>()

            for (l in listens) {
                val a = artistKey(l.artist)
                if (a.isEmpty()) continue
                val t = Instant.ofEpochMilli(l.at).atZone(zone)
                val part = DayPart.of(t.hour).ordinal
                val day = if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) 1 else 0
                val fade = 0.5.pow((now - l.at).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)
                val id = l.videoId?.takeIf { it.isNotBlank() }
                val s = id?.let { songs.getOrPut(it) { SongStats(it, l.title, l.artist, it in hearts) } }
                s?.lastAt = maxOf(s?.lastAt ?: 0L, l.at)
                // Skips (and listens heard through, which forgive them) are kept per moment.
                val cell = part * 2 + day
                val skipFade = 0.5.pow((now - l.at).coerceAtLeast(0).toDouble() / SKIP_HALF_LIFE_MS)
                val genre = genreOf(a, l.genre)
                val through = if (l.skipped) 0.0 else if (l.completed) 1.0 else 0.6
                fun mark(sk: Skips) {
                    if (l.skipped) sk.skipped[cell] += skipFade else sk.heard[cell] += through * skipFade
                }
                id?.let { mark(songSkips.getOrPut(it) { Skips() }) }
                mark(artistSkips.getOrPut(a) { Skips() })
                genre?.let { mark(genreSkips.getOrPut(it) { Skips() }) }
                // The taste of the moment learns from what is heard; a skip only told the moment.
                if (l.skipped) continue
                val value = when {
                    l.completed -> 1.0
                    l.listenedMs >= 60_000 || (l.durationMs > 0 && l.listenedMs * 2 >= l.durationMs) -> 0.6
                    else -> 0.25
                } * fade
                s?.let {
                    it.weight += value
                    it.weightIn[part] += value
                    for (p in l.people) it.together.merge(p, value, Double::plus)
                }
                artistParts.getOrPut(a) { DoubleArray(4) }[part] += value
                artistDays.getOrPut(a) { DoubleArray(2) }[day] += value
                artistTotal.merge(a, value, Double::plus)
                l.place?.let { p ->
                    artistPlaces.getOrPut(a) { HashMap() }.merge(p, value, Double::plus)
                    placeTotal.merge(p, value, Double::plus)
                }
                for (p in l.people) {
                    artistPeople.getOrPut(a) { HashMap() }.merge(p, value, Double::plus)
                    personTotal.merge(p, value, Double::plus)
                }
                genre?.let { g ->
                    genreParts.getOrPut(g) { DoubleArray(4) }[part] += value
                    genreDays.getOrPut(g) { DoubleArray(2) }[day] += value
                    genreTotal.merge(g, value, Double::plus)
                }
                partTotal[part] += value
                dayTotal[day] += value
                total += value
            }
            return MusicDna(
                artistParts, artistDays, artistPlaces, artistPeople, artistTotal,
                genreParts, genreDays, genreTotal,
                partTotal, dayTotal, placeTotal, personTotal, total, songs,
                songSkips.filterValues { it.skipped.any { v -> v > 0 } },
                artistSkips.filterValues { it.skipped.any { v -> v > 0 } },
                genreSkips.filterValues { it.skipped.any { v -> v > 0 } },
            )
        }
    }
}
