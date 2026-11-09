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
 * the friends they listen with; and how often each song is skipped, here and now and overall.
 *
 * - [lift]: how much more (or less) an artist fits a moment than usual (1 = as usual). Learned
 *   gently: a few listens move it a little, many move it more; a moment with little listening
 *   barely moves anything.
 * - [songFactor]: a song skipped again and again is suggested less, and less in the moment it is
 *   skipped in (skipped at night, still fine in the morning). One skip only nudges it; a song heard
 *   through comes back up. Hearts protect a song.
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
    /** For the DNA page. */
    val summary: DnaSummary,
) {
    /** A song's listening: decayed hears and skips, overall and per part of the day. */
    class SongStats(
        val videoId: String,
        val title: String,
        val artist: String,
        val hearted: Boolean,
    ) {
        var heard = 0.0
        var skips = 0.0
        val heardIn = DoubleArray(4)
        val skipsIn = DoubleArray(4)
        /** Listening together with each person (decayed). */
        val together = HashMap<String, Double>()
        var weight = 0.0
        val weightIn = DoubleArray(4)
        var lastAt = 0L
    }

    val isEmpty: Boolean get() = total <= 0.0

    /** How much more the artist fits [m] than usual: 1 = as usual; between [MIN_LIFT] and [MAX_LIFT]. */
    fun lift(artistKey: String, m: DnaMoment): Double {
        val all = artistTotal[artistKey] ?: return 1.0
        if (total <= 0 || all <= 0) return 1.0
        val share = all / total
        var log = 0.0
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
        val all = genreTotal[genre] ?: return 1.0
        if (total <= 0 || all <= 0) return 1.0
        val share = all / total
        val d = if (m.weekend) 1 else 0
        val log = PART_WEIGHT * ln(ratio(genreParts[genre]?.get(m.part.ordinal) ?: 0.0, partTotal[m.part.ordinal], share)) +
            DAY_WEIGHT * ln(ratio(genreDays[genre]?.get(d) ?: 0.0, dayTotal[d], share))
        return exp(log).coerceIn(MIN_LIFT, MAX_LIFT)
    }

    /**
     * 0..1: how much a song is wanted back, from its skips (1 = no doubt). Overall skips weigh a
     * little; the skips in this part of the day weigh more. A heart keeps it at 1.
     */
    fun songFactor(videoId: String, m: DnaMoment): Double {
        val s = songs[videoId] ?: return 1.0
        if (s.hearted) return 1.0
        val overall = s.skips / (s.skips + s.heard + SKIP_PRIOR)
        val here = s.skipsIn[m.part.ordinal] / (s.skipsIn[m.part.ordinal] + s.heardIn[m.part.ordinal] + SKIP_PRIOR)
        return ((1.0 - OVERALL_SKIP * overall) * (1.0 - MOMENT_SKIP * here)).coerceIn(0.0, 1.0)
    }

    /**
     * The songs that clearly don't fit [m]: skipped in this part of the day again and again and
     * hardly ever heard through in it. Left out of what plays now — they stay in other moments.
     */
    fun avoidNow(m: DnaMoment): Set<String> {
        val p = m.part.ordinal
        return songs.values.filter { s ->
            !s.hearted && s.skipsIn[p] >= AVOID_SKIPS && s.skipsIn[p] / (s.skipsIn[p] + s.heardIn[p] + SKIP_PRIOR) >= AVOID_RATE
        }.mapTo(HashSet()) { it.videoId }
    }

    /** Every song's factor below 1 in [m] (for a profile of the moment). */
    fun songFactors(m: DnaMoment): Map<String, Double> =
        songs.keys.mapNotNull { id -> songFactor(id, m).takeIf { it < 0.999 }?.let { id to it } }.toMap()

    /**
     * Your songs that fit [m] best: heard a lot in this part of the day (and with these people,
     * at this place), the ones that fit the moment's artists, without the ones you skip here.
     */
    fun songsFor(m: DnaMoment, count: Int): List<SongStats> {
        val part = m.part.ordinal
        return songs.values
            .filter { it.weight > 0 && songFactor(it.videoId, m) >= 0.5 }
            .map { s ->
                val here = s.weightIn[part] / (s.weight + 1.0)
                val people = if (m.people.isEmpty()) 0.0 else m.people.sumOf { s.together[it] ?: 0.0 }
                val score = (s.weight.pow(0.5) * (0.4 + 1.6 * here) + 1.5 * people) *
                    lift(artistKey(s.artist), m) * songFactor(s.videoId, m)
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
        /** Pseudo-hears: one skip alone doesn't say much. */
        const val SKIP_PRIOR = 1.5
        const val OVERALL_SKIP = 0.5
        const val MOMENT_SKIP = 0.6
        /** Skipped this often in a part of the day (and barely heard there): out of it. */
        const val AVOID_SKIPS = 3.0
        const val AVOID_RATE = 0.65

        /**
         * Builds the DNA from [listens] (each with its time, and where and with whom when known),
         * [hearts] (video ids: never pushed down) and [genreOf] (an artist key → home genre id).
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
            val names = HashMap<String, HashMap<String, Int>>()
            val sum = DnaSummary.Builder(zone)

            for (l in listens) {
                val a = artistKey(l.artist)
                if (a.isEmpty()) continue
                names.getOrPut(a) { HashMap() }.merge(l.artist.trim(), 1, Int::plus)
                val t = Instant.ofEpochMilli(l.at).atZone(zone)
                val part = DayPart.of(t.hour).ordinal
                val day = if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) 1 else 0
                val fade = 0.5.pow((now - l.at).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)
                val id = l.videoId?.takeIf { it.isNotBlank() }
                val s = id?.let { songs.getOrPut(it) { SongStats(it, l.title, l.artist, it in hearts) } }
                s?.lastAt = maxOf(s?.lastAt ?: 0L, l.at)
                if (l.skipped) {
                    s?.let {
                        it.skips += fade
                        it.skipsIn[part] += fade
                    }
                    continue
                }
                val value = when {
                    l.completed -> 1.0
                    l.listenedMs >= 60_000 || (l.durationMs > 0 && l.listenedMs * 2 >= l.durationMs) -> 0.6
                    else -> 0.25
                } * fade
                s?.let {
                    it.heard += fade
                    it.heardIn[part] += fade
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
                genreOf(a, l.genre)?.let { g ->
                    genreParts.getOrPut(g) { DoubleArray(4) }[part] += value
                    genreDays.getOrPut(g) { DoubleArray(2) }[day] += value
                    genreTotal.merge(g, value, Double::plus)
                }
                partTotal[part] += value
                dayTotal[day] += value
                total += value
                sum.add(l, t, a, genreOf(a, l.genre), value)
            }
            val display = names.mapValues { (_, n) -> n.maxBy { it.value }.key }
            return MusicDna(
                artistParts, artistDays, artistPlaces, artistPeople, artistTotal,
                genreParts, genreDays, genreTotal,
                partTotal, dayTotal, placeTotal, personTotal, total, songs,
                sum.build(display),
            )
        }
    }
}

/**
 * What the DNA page shows (pure numbers, worked out with the DNA): minutes per hour of the day and
 * per weekday, the artists and genre of each part of the day, the places (by when you are there and
 * what you hear there) and the friends you listen with.
 */
data class DnaSummary(
    /** Minutes listened in each hour (0–23), all time (decayed weight not used: real minutes). */
    val hourMinutes: List<Long>,
    /** Minutes per weekday, Monday first. */
    val weekdayMinutes: List<Long>,
    val parts: List<Part>,
    val weekdayTop: List<String>,
    val weekendTop: List<String>,
    val places: List<Place>,
    val people: List<Person>,
) {
    data class Part(val part: DayPart, val minutes: Long, val artists: List<String>, val genre: String?)
    data class Place(val id: Long, val minutes: Long, val part: DayPart?, val weekend: Boolean?, val artists: List<String>)
    data class Person(val id: String, val minutes: Long, val artists: List<String>, val songs: List<Pair<String, String>>)

    val isEmpty: Boolean get() = hourMinutes.all { it == 0L }

    /** The part of the day with the most listening. */
    val mainPart: DayPart? get() = parts.maxByOrNull { it.minutes }?.takeIf { it.minutes > 0 }?.part

    internal class Builder(private val zone: ZoneId) {
        private val hours = LongArray(24)
        private val weekdays = LongArray(7)
        private val partMs = LongArray(4)
        private val partArtists = Array(4) { HashMap<String, Double>() }
        private val partGenres = Array(4) { HashMap<String, Double>() }
        private val dayArtists = Array(2) { HashMap<String, Double>() }
        private val placeMs = HashMap<Long, Long>()
        private val placeParts = HashMap<Long, LongArray>()
        private val placeDays = HashMap<Long, LongArray>()
        private val placeArtists = HashMap<Long, HashMap<String, Double>>()
        private val personMs = HashMap<String, Long>()
        private val personArtists = HashMap<String, HashMap<String, Double>>()
        private val personSongs = HashMap<String, HashMap<Pair<String, String>, Double>>()

        fun add(l: Listen, t: java.time.ZonedDateTime, artist: String, genre: String?, value: Double) {
            val ms = l.listenedMs.coerceAtLeast(0)
            val part = DayPart.of(t.hour).ordinal
            val day = if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) 1 else 0
            hours[t.hour] += ms
            weekdays[t.dayOfWeek.value - 1] += ms
            partMs[part] += ms
            partArtists[part].merge(artist, value, Double::plus)
            genre?.let { partGenres[part].merge(it, value, Double::plus) }
            dayArtists[day].merge(artist, value, Double::plus)
            l.place?.let { p ->
                placeMs.merge(p, ms, Long::plus)
                placeParts.getOrPut(p) { LongArray(4) }[part] += ms
                placeDays.getOrPut(p) { LongArray(2) }[day] += ms
                placeArtists.getOrPut(p) { HashMap() }.merge(artist, value, Double::plus)
            }
            for (p in l.people) {
                personMs.merge(p, ms, Long::plus)
                personArtists.getOrPut(p) { HashMap() }.merge(artist, value, Double::plus)
                personSongs.getOrPut(p) { HashMap() }.merge(l.title to l.artist, value, Double::plus)
            }
        }

        private fun top(m: Map<String, Double>, names: Map<String, String>, n: Int = 3) =
            m.entries.sortedByDescending { it.value }.take(n).map { names[it.key] ?: it.key }

        fun build(names: Map<String, String>): DnaSummary {
            // Two artists that differ the most between weekdays and the weekend (what makes each its own).
            fun distinct(mine: Map<String, Double>, other: Map<String, Double>): List<String> {
                val a = mine.values.sum().takeIf { it > 0 } ?: return emptyList()
                val b = other.values.sum().takeIf { it > 0 } ?: 1.0
                return mine.entries.sortedByDescending { (k, v) -> (v / a) * ((v / a + 0.02) / ((other[k] ?: 0.0) / b + 0.02)) }
                    .take(3).map { names[it.key] ?: it.key }
            }
            return DnaSummary(
                hourMinutes = hours.map { it / 60_000 },
                weekdayMinutes = weekdays.map { it / 60_000 },
                parts = DayPart.entries.map { p ->
                    Part(p, partMs[p.ordinal] / 60_000, top(partArtists[p.ordinal], names), partGenres[p.ordinal].maxByOrNull { it.value }?.key)
                },
                weekdayTop = distinct(dayArtists[0], dayArtists[1]),
                weekendTop = distinct(dayArtists[1], dayArtists[0]),
                places = placeMs.entries.filter { it.value >= MIN_PLACE_MS }.sortedByDescending { it.value }.take(MAX_PLACES).map { (id, ms) ->
                    val parts = placeParts.getValue(id)
                    val days = placeDays.getValue(id)
                    val mainPart = parts.indices.maxBy { parts[it] }
                    Place(
                        id = id,
                        minutes = ms / 60_000,
                        part = DayPart.entries[mainPart].takeIf { parts[mainPart] * 2 >= ms },
                        weekend = when {
                            days[1] * 2 >= ms * 1 && days[1] > days[0] * 1.2 -> true
                            days[0] > days[1] * 2.5 -> false
                            else -> null
                        },
                        artists = top(placeArtists.getValue(id), names),
                    )
                },
                people = personMs.entries.sortedByDescending { it.value }.take(MAX_PEOPLE).map { (id, ms) ->
                    Person(
                        id = id,
                        minutes = ms / 60_000,
                        artists = top(personArtists.getValue(id), names),
                        songs = personSongs.getValue(id).entries.sortedByDescending { it.value }.take(3).map { it.key },
                    )
                },
            )
        }

        private companion object {
            /** A place shows once there are 30 minutes of music heard there. */
            const val MIN_PLACE_MS = 30L * 60_000
            const val MAX_PLACES = 4
            const val MAX_PEOPLE = 6
        }
    }
}
