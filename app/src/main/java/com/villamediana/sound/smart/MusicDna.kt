package com.villamediana.sound.smart

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** The four parts of the day (what the moment's mix is named after). */
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
 * The moment music is being chosen for: the hour, the day of the week (a night belongs to the day
 * it started in: until [DAY_STARTS] it is still the day before), where the phone is (a place the
 * app learned by itself, when that is on) and who is listening along (the friends in the jam).
 */
data class DnaMoment(
    val hour: Int,
    val weekday: DayOfWeek,
    val place: Long? = null,
    val people: Set<String> = emptySet(),
) {
    val part: DayPart get() = DayPart.of(hour)
    val weekend: Boolean get() = weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY

    /** What the "Pra agora" mix is made for (it changes when the moment does: every [KEY_HOURS] hours). */
    val key: String get() = "${hour / KEY_HOURS}:${if (weekend) "we" else "wd"}:${place ?: "-"}:${people.sorted().joinToString(",")}"

    companion object {
        /** Before this hour the night still belongs to the day before (Saturday 2 a.m. is Friday night). */
        const val DAY_STARTS = 5
        const val KEY_HOURS = 3

        fun at(time: Long, zone: ZoneId = ZoneId.systemDefault(), place: Long? = null, people: Set<String> = emptySet()): DnaMoment {
            val t = Instant.ofEpochMilli(time).atZone(zone)
            return DnaMoment(t.hour, DayOfWeek.of(dayOf(t.dayOfWeek.ordinal, t.hour) + 1), place, people)
        }

        /** The listening day (0 = Monday) of an hour of a calendar day. */
        internal fun dayOf(calendarDay: Int, hour: Int): Int = if (hour < DAY_STARTS) (calendarDay + 6) % 7 else calendarDay
    }
}

/**
 * The listener's musical DNA, learned from everything they did (pure): which artists, genres and
 * songs belong to which hours, to which days of the week, to the places they listen at and to the
 * friends they listen with.
 *
 * - [lift]: how much more (or less) an artist fits a moment than usual (1 = as usual). The clock is
 *   smooth — listening an hour away counts almost whole, six hours away nothing — so there is no
 *   wall between "afternoon" and "evening". Three looks at the time weigh together: the hour on any
 *   day, the kind of day (weekday or weekend) and this very day of the week around this hour
 *   ("Friday nights"). Learned gently: a few listens move it a little, many move it more; a moment
 *   with little listening barely moves anything.
 * - Skips teach the moment, not the taste (Miguel: "pode ser uma música ou ritmo que já gosto, mas
 *   não nesse horário"): a skip counts around the hour, in the kind of day and at the place it
 *   happened, for the song, its artist and its genre (the "ritmo"). Heard through in that moment,
 *   it is forgiven; skips fade by half every [SKIP_HALF_LIFE_MS] (the truth comes back); a heart
 *   keeps a song from going far down. [songFactor] and [avoidNow] say it for a moment.
 * - The sound itself (smart/SoundPrint: mood and rhythm, measured on the phone while a song plays)
 *   is learned the same way: which sound — calm or intense, still or moving — belongs to which
 *   hour, day, place and company, and which is skipped there. [soundLift] says it for a song, by
 *   its own sound or else by what its artist usually sounds like; so a song never heard in the
 *   morning still fits it when it sounds like the mornings.
 * - It only speaks of a moment it knows ([songsFor]): while nothing was heard in the mornings
 *   there are no "songs of the morning" — never the favourites of other hours in their place.
 *   Each moment fills up by itself as it is lived.
 */
class MusicDna private constructor(
    private val all: Stat,
    private val artists: Map<String, Stat>,
    private val genres: Map<String, Stat>,
    private val songs: Map<String, SongStats>,
    private val songSkips: Map<String, Skips>,
    private val artistSkips: Map<String, Skips>,
    private val genreSkips: Map<String, Skips>,
    /** The listening that has a sound, and each corner of the sound map ([VIBES] of them) in it. */
    private val sounded: Stat,
    private val vibes: Array<Stat?>,
    private val vibeSkips: Array<Skips?>,
    private val songSpots: Map<String, Spot>,
    private val artistSpots: Map<String, Spot>,
) {
    /**
     * Listening (fading with [HALF_LIFE_MS]) of an artist, a genre or of everything: in each hour
     * of each day of the week (kept only where there is some), at each place, with each person.
     */
    private class Stat {
        var total = 0.0
        val places = HashMap<Long, Double>(2)
        val people = HashMap<String, Double>(2)
        private var slots = IntArray(4)
        private var values = DoubleArray(4)
        private var size = 0

        fun add(slot: Int, value: Double, place: Long?, along: List<String>) {
            total += value
            var i = 0
            while (i < size && slots[i] != slot) i++
            if (i == size) {
                if (size == slots.size) {
                    slots = slots.copyOf(size * 2)
                    values = values.copyOf(size * 2)
                }
                slots[size++] = slot
            }
            values[i] += value
            if (place != null) places.merge(place, value, Double::plus)
            for (p in along) people.merge(p, value, Double::plus)
        }

        /** The listening in the hours of the week [weights] counts, each by its weight. */
        fun sum(weights: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until size) s += weights[slots[i]] * values[i]
            return s
        }
    }

    /**
     * A moment's clock laid over the week: how much each hour of each day counts for it (near the
     * hour on any day; the same kind of day; near the hour on this day of the week) and how much
     * was heard there in all.
     */
    private class Clock(val hour: Int, val day: Int, all: Stat) {
        val nearHour = DoubleArray(SLOTS)
        val sameKind = DoubleArray(SLOTS)
        val sameDay = DoubleArray(SLOTS)
        val nearHourTotal: Double
        val sameKindTotal: Double
        val sameDayTotal: Double

        init {
            val weekend = day >= WEEKEND
            for (d in 0 until 7) for (h in 0 until 24) {
                val slot = d * 24 + h
                val k = near(h, hour)
                nearHour[slot] = k
                sameKind[slot] = if ((d >= WEEKEND) == weekend) 1.0 else 0.0
                sameDay[slot] = if (d == day) k else 0.0
            }
            nearHourTotal = all.sum(nearHour)
            sameKindTotal = all.sum(sameKind)
            sameDayTotal = all.sum(sameDay)
        }
    }

    /**
     * Skips and listens heard through (fading with [SKIP_HALF_LIFE_MS]) where they happened: the
     * hour, a weekday or the weekend, the place (kept only where there is some).
     */
    private class Skips {
        private var cells = LongArray(2)
        private var skipped = DoubleArray(2)
        private var heard = DoubleArray(2)
        private var size = 0
        var any = false
            private set

        fun mark(hour: Int, weekend: Boolean, place: Long?, skip: Double, through: Double) {
            val cell = ((place ?: -1L) + 1) * 48 + (if (weekend) 24 else 0) + hour
            var i = 0
            while (i < size && cells[i] != cell) i++
            if (i == size) {
                if (size == cells.size) {
                    cells = cells.copyOf(size * 2)
                    skipped = skipped.copyOf(size * 2)
                    heard = heard.copyOf(size * 2)
                }
                cells[size++] = cell
            }
            skipped[i] += skip
            heard[i] += through
            if (skip > 0) any = true
        }

        /**
         * 0..1, how much this is skipped in [m]: around its hour in its kind of day, and around its
         * hour on any day, weigh together; [prior] pseudo-listens keep a skip or two from saying much.
         */
        fun rate(m: DnaMoment, prior: Double): Double {
            var skKind = 0.0
            var hdKind = 0.0
            var skAny = 0.0
            var hdAny = 0.0
            for (i in 0 until size) {
                val w = weight(cells[i], m)
                if (w <= 0.0) continue
                skAny += w * skipped[i]
                hdAny += w * heard[i]
                if ((cells[i] % 48 >= 24) == m.weekend) {
                    skKind += w * skipped[i]
                    hdKind += w * heard[i]
                }
            }
            return CELL_SHARE * (skKind / (skKind + hdKind + prior)) + (1 - CELL_SHARE) * (skAny / (skAny + hdAny + prior))
        }

        /** Skips around [m]'s hour (any kind of day). */
        fun skipsNear(m: DnaMoment): Double {
            var s = 0.0
            for (i in 0 until size) s += weight(cells[i], m) * skipped[i]
            return s
        }

        /** 0..1 over every moment. */
        fun overall(prior: Double): Double {
            var sk = 0.0
            var hd = 0.0
            for (i in 0 until size) {
                sk += skipped[i]
                hd += heard[i]
            }
            return sk / (sk + hd + prior)
        }

        /** How much what happened in [cell] says about [m]: by the hour, and less from another place. */
        private fun weight(cell: Long, m: DnaMoment): Double {
            val k = near((cell % 24).toInt(), m.hour)
            val place = cell / 48 - 1
            val here = m.place
            return if (here == null || place < 0 || place == here) k else k * OTHER_PLACE
        }
    }

    /** A song's listening (decayed): overall, by hour, by place and with whom. */
    class SongStats(
        val videoId: String,
        val title: String,
        val artist: String,
        val hearted: Boolean,
    ) {
        /** Listening together with each person (decayed). */
        val together = HashMap<String, Double>(2)
        var weight = 0.0
        var lastAt = 0L
        internal val byHour = DoubleArray(24)
        internal val places = HashMap<Long, Double>(2)

        /** 0..1: how much of this song's listening is around [hour]. */
        internal fun around(hour: Int): Double {
            var s = 0.0
            for (h in 0 until 24) if (byHour[h] > 0) s += near(h, hour) * byHour[h]
            return s / (weight + 1.0)
        }

        /** The listening of this song up to [AROUND_HOURS] hours from [hour] (around the clock). */
        internal fun heardAround(hour: Int): Double {
            var s = 0.0
            for (h in 0 until 24) if (byHour[h] > 0 && hoursApart(h, hour) <= AROUND_HOURS) s += byHour[h]
            return s
        }
    }

    @Volatile private var clock: Clock? = null
    @Volatile private var soundClock: Clock? = null
    @Volatile private var vibeLifts: Pair<DnaMoment, DoubleArray>? = null

    private fun clockOf(m: DnaMoment): Clock {
        val day = m.weekday.ordinal
        clock?.let { if (it.hour == m.hour && it.day == day) return it }
        return Clock(m.hour, day, all).also { clock = it }
    }

    private fun soundClockOf(m: DnaMoment): Clock {
        val day = m.weekday.ordinal
        soundClock?.let { if (it.hour == m.hour && it.day == day) return it }
        return Clock(m.hour, day, sounded).also { soundClock = it }
    }

    val isEmpty: Boolean get() = all.total <= 0.0

    /** The ones of [people] the DNA has heard enough music with to know what plays with them. */
    fun known(people: Set<String>): Set<String> = people.filterTo(LinkedHashSet()) { (all.people[it] ?: 0.0) >= MIN_CONTEXT }

    /** How much more the artist fits [m] than usual: 1 = as usual; between [MIN_LIFT] and [MAX_LIFT]. */
    fun lift(artistKey: String, m: DnaMoment): Double =
        liftOf(artists[artistKey], artistSkips[artistKey], ARTIST_SKIP, ARTIST_PRIOR, m, all, clockOf(m))

    /** How much more a genre (home genre id) fits [m] than usual. */
    fun genreLift(genre: String, m: DnaMoment): Double =
        liftOf(genres[genre], genreSkips[genre], GENRE_SKIP, GENRE_PRIOR, m, all, clockOf(m))

    /**
     * How much more a song's sound fits [m] than usual (1 = as usual, or nothing known about its
     * sound): its own when it was measured, else what [artistKey] usually sounds like. Between
     * [SOUND_MIN] and [SOUND_MAX].
     */
    fun soundLift(videoId: String?, artistKey: String, m: DnaMoment): Double {
        val spot = videoId?.let(songSpots::get) ?: artistSpots[artistKey] ?: return 1.0
        return soundLift(spot, m)
    }

    private fun soundLift(spot: Spot, m: DnaMoment): Double {
        val cached = vibeLifts
        val lifts = if (cached != null && cached.first == m) cached.second else {
            val c = soundClockOf(m)
            DoubleArray(VIBES) { liftOf(vibes[it], vibeSkips[it], SOUND_SKIP, SOUND_PRIOR, m, sounded, c) }.also { vibeLifts = m to it }
        }
        var lift = 0.0
        spread(spot) { cell, w -> lift += w * lifts[cell] }
        return lift.coerceIn(SOUND_MIN, SOUND_MAX)
    }

    /**
     * For the songs whose sound was measured: how their own sound fits [m] against what their
     * artist usually sounds like (1 = the same, left out): the quiet song of a loud band.
     */
    fun soundFits(m: DnaMoment): Map<String, Double> {
        if (songSpots.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        for ((id, spot) in songSpots) {
            val usual = songs[id]?.let { artistSpots[artistKey(it.artist)] } ?: continue
            val fit = (soundLift(spot, m) / soundLift(usual, m)).coerceIn(SOUND_MIN, SOUND_MAX)
            if (abs(fit - 1.0) > 0.03) out[id] = fit
        }
        return out
    }

    /** [stat]'s lift in [m] among the listening [of] ([c]: [m]'s clock over it). */
    private fun liftOf(stat: Stat?, skips: Skips?, skipWeight: Double, skipPrior: Double, m: DnaMoment, of: Stat, c: Clock): Double {
        // Skipped in this moment: it fits it less, wherever it stands otherwise.
        var log = ln((1.0 - skipWeight * (skips?.rate(m, skipPrior) ?: 0.0)).coerceAtLeast(0.05))
        if (stat != null && stat.total > 0 && of.total > 0) {
            val share = stat.total / of.total
            log += HOUR_WEIGHT * ln(ratio(stat.sum(c.nearHour), c.nearHourTotal, share))
            log += DAY_WEIGHT * ln(ratio(stat.sum(c.sameKind), c.sameKindTotal, share))
            log += SLOT_WEIGHT * ln(ratio(stat.sum(c.sameDay), c.sameDayTotal, share))
            val place = m.place
            val atPlace = if (place != null) of.places[place] ?: 0.0 else 0.0
            if (place != null && atPlace >= MIN_CONTEXT) {
                log += PLACE_WEIGHT * ln(ratio(stat.places[place] ?: 0.0, atPlace, share))
            }
            var along = 0.0
            var known = 0
            for (p in m.people) {
                val with = of.people[p] ?: continue
                if (with < MIN_CONTEXT) continue
                along += ln(ratio(stat.people[p] ?: 0.0, with, share))
                known++
            }
            if (known > 0) log += PEOPLE_WEIGHT * along / known
        }
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

    /**
     * How well a song fits [m] against any other moment (1 = as always): its artist's hours, days,
     * place and people, its sound, and its skips here. For the library's own order.
     */
    fun fit(videoId: String?, artistKey: String, m: DnaMoment): Double {
        val id = videoId?.takeIf { it.isNotBlank() }
        return (lift(artistKey, m) * soundLift(id, artistKey, m) * (id?.let { songFactor(it, m) } ?: 1.0)).coerceIn(FIT_MIN, FIT_MAX)
    }

    /** Every song's factor below 1 in [m] (for a profile of the moment). */
    fun songFactors(m: DnaMoment): Map<String, Double> =
        songSkips.keys.mapNotNull { id -> songFactor(id, m).takeIf { it < 0.999 }?.let { id to it } }.toMap()

    /**
     * The songs that clearly don't fit [m]: skipped around this hour again and again and hardly
     * heard through in it. Left out of what plays now — they stay in other moments, and come back
     * as the skips fade. Never a hearted one.
     */
    fun avoidNow(m: DnaMoment): Set<String> = songSkips.entries.filter { (id, sk) ->
        songs[id]?.hearted != true && sk.skipsNear(m) >= AVOID_SKIPS && sk.rate(m, SONG_PRIOR) >= AVOID_RATE
    }.mapTo(HashSet()) { it.key }

    /**
     * Your songs that fit [m] best: heard a lot around this hour (and at this place, with these
     * people), the ones that fit the moment's artists.
     *
     * Only what the moment itself knows: the songs really heard around this hour at least once
     * ([heardAround], [HEARD_AROUND]) and the ones heard with the people along ([known]). A moment
     * never lived has none — never the favourites of other hours under its name — and gets its
     * songs as it is lived.
     */
    fun songsFor(m: DnaMoment, count: Int): List<SongStats> {
        val along = known(m.people)
        val place = m.place?.takeIf { (all.places[it] ?: 0.0) >= MIN_CONTEXT }
        return songs.values
            .filter { s ->
                s.weight > 0 && songFactor(s.videoId, m) >= SONGS_FOR_FLOOR &&
                    (s.heardAround(m.hour) >= HEARD_AROUND || along.any { (s.together[it] ?: 0.0) > 0.0 })
            }
            .map { s ->
                val here = s.around(m.hour)
                val there = if (place == null) 1.0 else PLACE_BASE + PLACE_SPAN * ((s.places[place] ?: 0.0) / (s.weight + 1.0))
                val people = if (m.people.isEmpty()) 0.0 else m.people.sumOf { s.together[it] ?: 0.0 }
                val artist = artistKey(s.artist)
                val score = (s.weight.pow(0.5) * (0.4 + 1.6 * here) * there + 1.5 * people) *
                    lift(artist, m) * soundLift(s.videoId, artist, m) * songFactor(s.videoId, m)
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
        /** The three looks at the time: the hour on any day, the kind of day, this weekday around this hour. */
        const val HOUR_WEIGHT = 0.75
        const val DAY_WEIGHT = 0.35
        const val SLOT_WEIGHT = 0.45
        const val PLACE_WEIGHT = 0.8
        const val PEOPLE_WEIGHT = 1.0
        /** A place or a friend counts once there is this much listening with it. */
        const val MIN_CONTEXT = 3.0
        /** A song at a known place: from this (never heard here) up to this plus [PLACE_SPAN] (only heard here). */
        const val PLACE_BASE = 0.7
        const val PLACE_SPAN = 0.8

        /** Skips (and what forgives them) fade by half in this time: what was skipped comes back. */
        const val SKIP_HALF_LIFE_MS = 30L * 24 * 3_600_000L
        /** In a skip rate, the moment's own kind of day against any day (both around its hour). */
        const val CELL_SHARE = 0.6
        /** What happened at another place says this much about here. */
        const val OTHER_PLACE = 0.5
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
        /** Out of the moment: this many (fading) skips around its hour, and skipped this much there. */
        const val AVOID_SKIPS = 2.5
        const val AVOID_RATE = 0.58
        /** Not among the moment's own songs below this. */
        const val SONGS_FOR_FLOOR = 0.45
        /** "Around" an hour, for what a moment knows by itself: up to this many hours from it. */
        const val AROUND_HOURS = 2
        /**
         * Really heard around an hour: this much listening as it weighs today (once, through or
         * most of it; a listen of months ago fades out of it). The bar for a song to be one of
         * the moment's.
         */
        const val HEARD_AROUND = 0.5

        /** How far a sound's fit to the moment moves a song, and how far skips push a sound down in it. */
        /** How far the moment can move a song of the library ([fit]). */
        const val FIT_MIN = 0.2
        const val FIT_MAX = 4.0
        const val SOUND_MIN = 0.5
        const val SOUND_MAX = 2.0
        const val SOUND_SKIP = 0.4
        const val SOUND_PRIOR = 5.0
        /** The sound map: this many steps from calm to intense, and from still to moving. */
        private const val SIDE = 4
        private const val VIBES = SIDE * SIDE

        /** The hours of the week. */
        private const val SLOTS = 7 * 24
        /** The first weekend day (0 = Monday). */
        private const val WEEKEND = 5
        /**
         * How much listening counts for an hour 0, 1, 2… hours away: whole beside it, fading out
         * over six hours (about as wide as a part of the day, without its walls).
         */
        private val HOUR_KERNEL = doubleArrayOf(1.0, 0.95, 0.75, 0.48, 0.24, 0.1, 0.03)

        /** How much hour [a] counts for hour [b] (around the clock: 23 h is beside 0 h). */
        internal fun near(a: Int, b: Int): Double {
            val around = hoursApart(a, b)
            return if (around < HOUR_KERNEL.size) HOUR_KERNEL[around] else 0.0
        }

        /** How many hours between two hours of the day, around the clock (23 h and 1 h: two). */
        internal fun hoursApart(a: Int, b: Int): Int {
            val d = abs(a - b)
            return min(d, 24 - d)
        }

        /** Shares [spot] among the (up to four) corners of the sound map around it. */
        private inline fun spread(spot: Spot, to: (cell: Int, weight: Double) -> Unit) {
            val x = (spot.energy * SIDE - 0.5f).coerceIn(0f, SIDE - 1f)
            val y = (spot.motion * SIDE - 0.5f).coerceIn(0f, SIDE - 1f)
            val x0 = min(x.toInt(), SIDE - 2)
            val y0 = min(y.toInt(), SIDE - 2)
            val fx = (x - x0).toDouble()
            val fy = (y - y0).toDouble()
            val at = y0 * SIDE + x0
            if ((1 - fx) * (1 - fy) > 0) to(at, (1 - fx) * (1 - fy))
            if (fx * (1 - fy) > 0) to(at + 1, fx * (1 - fy))
            if ((1 - fx) * fy > 0) to(at + SIDE, (1 - fx) * fy)
            if (fx * fy > 0) to(at + SIDE + 1, fx * fy)
        }

        /**
         * Builds the DNA from [listens] (each with its time, and where and with whom when known),
         * [hearts] (video ids), [genreOf] (an artist key → home genre id) and [sounds] (what the
         * songs measured so far sound like, by video id).
         */
        fun build(
            listens: List<Listen>,
            hearts: Set<String>,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            genreOf: (artistKey: String, songGenre: String?) -> String? = { _, g -> g },
            sounds: Map<String, SoundPrint> = emptyMap(),
        ): MusicDna {
            val all = Stat()
            val artists = HashMap<String, Stat>()
            val genres = HashMap<String, Stat>()
            val songs = HashMap<String, SongStats>()
            val songSkips = HashMap<String, Skips>()
            val artistSkips = HashMap<String, Skips>()
            val genreSkips = HashMap<String, Skips>()

            // Each measured song's place on this listener's own scale, and each artist's usual one.
            val map = SoundMap.of(sounds.values)
            val songSpots = if (map == null) emptyMap() else sounds.mapValues { map.spot(it.value) }
            val artistSpots = HashMap<String, Spot>()
            if (songSpots.isNotEmpty()) {
                val seen = HashSet<String>()
                val sums = HashMap<String, FloatArray>()
                for (l in listens) {
                    val id = l.videoId ?: continue
                    val spot = songSpots[id] ?: continue
                    val a = artistKey(l.artist)
                    if (a.isEmpty() || !seen.add(id)) continue
                    val sum = sums.getOrPut(a) { FloatArray(3) }
                    sum[0] += spot.energy
                    sum[1] += spot.motion
                    sum[2] += 1f
                }
                for ((a, sum) in sums) artistSpots[a] = Spot(sum[0] / sum[2], sum[1] / sum[2])
            }
            val sounded = Stat()
            val vibes = arrayOfNulls<Stat>(VIBES)
            val vibeSkips = arrayOfNulls<Skips>(VIBES)

            for (l in listens) {
                val a = artistKey(l.artist)
                if (a.isEmpty()) continue
                val t = Instant.ofEpochMilli(l.at).atZone(zone)
                val hour = t.hour
                val day = DnaMoment.dayOf(t.dayOfWeek.ordinal, hour)
                val age = (now - l.at).coerceAtLeast(0).toDouble()
                val id = l.videoId?.takeIf { it.isNotBlank() }
                val s = id?.let { songs.getOrPut(it) { SongStats(it, l.title, l.artist, it in hearts) } }
                if (s != null) s.lastAt = maxOf(s.lastAt, l.at)
                val genre = genreOf(a, l.genre)
                // Skips (and listens heard through, which forgive them) are kept where they happened.
                val skipFade = 0.5.pow(age / SKIP_HALF_LIFE_MS)
                val skip = if (l.skipped) skipFade else 0.0
                val through = if (l.skipped) 0.0 else (if (l.completed) 1.0 else 0.6) * skipFade
                val weekend = day >= WEEKEND
                if (id != null) songSkips.getOrPut(id) { Skips() }.mark(hour, weekend, l.place, skip, through)
                artistSkips.getOrPut(a) { Skips() }.mark(hour, weekend, l.place, skip, through)
                if (genre != null) genreSkips.getOrPut(genre) { Skips() }.mark(hour, weekend, l.place, skip, through)
                val spot = id?.let(songSpots::get) ?: artistSpots[a]
                if (spot != null) spread(spot) { cell, w ->
                    (vibeSkips[cell] ?: Skips().also { vibeSkips[cell] = it }).mark(hour, weekend, l.place, skip * w, through * w)
                }
                // The taste of the moment learns from what is heard; a skip only told the moment.
                if (l.skipped) continue
                val value = when {
                    l.completed -> 1.0
                    l.listenedMs >= 60_000 || (l.durationMs > 0 && l.listenedMs * 2 >= l.durationMs) -> 0.6
                    else -> 0.25
                } * 0.5.pow(age / HALF_LIFE_MS)
                if (s != null) {
                    s.weight += value
                    s.byHour[hour] += value
                    l.place?.let { s.places.merge(it, value, Double::plus) }
                    for (p in l.people) s.together.merge(p, value, Double::plus)
                }
                val slot = day * 24 + hour
                all.add(slot, value, l.place, l.people)
                artists.getOrPut(a) { Stat() }.add(slot, value, l.place, l.people)
                if (genre != null) genres.getOrPut(genre) { Stat() }.add(slot, value, l.place, l.people)
                if (spot != null) {
                    sounded.add(slot, value, l.place, l.people)
                    spread(spot) { cell, w ->
                        (vibes[cell] ?: Stat().also { vibes[cell] = it }).add(slot, value * w, l.place, l.people)
                    }
                }
            }
            return MusicDna(
                all, artists, genres, songs,
                songSkips.filterValues { it.any },
                artistSkips.filterValues { it.any },
                genreSkips.filterValues { it.any },
                sounded, vibes, vibeSkips, songSpots, artistSpots,
            )
        }
    }
}
