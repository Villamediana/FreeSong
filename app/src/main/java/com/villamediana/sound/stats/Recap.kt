package com.villamediana.sound.stats

import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.YouTubeLinks
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId


/** A song of the ranking: a library one ([track]) or one heard online ([remote]). */
data class RecapSong(
    val key: String,
    val title: String,
    val artist: String,
    val cover: String?,
    val plays: Int,
    val listenedMs: Long,
    val track: Track? = null,
    val remote: RemoteTrack? = null,
)

data class RecapArtist(val name: String, val cover: String?, val plays: Int, val listenedMs: Long)

data class Recap(
    /** The year it sums up. */
    val year: Int,
    val listenedMs: Long = 0,
    /** Listens that were not skipped. */
    val plays: Int = 0,
    val songs: Int = 0,
    val artists: Int = 0,
    val topSongs: List<RecapSong> = emptyList(),
    val topArtists: List<RecapArtist> = emptyList(),
    /** Only library songs carry a genre. */
    val topGenre: String? = null,
    /** 0–23, the hour with the most listening. */
    val favoriteHour: Int? = null,
    val favoriteDay: DayOfWeek? = null,
    /** Listening per month, January first (12 values). */
    val months: List<Long> = List(12) { 0L },
    /** Days with any listening. */
    val daysListened: Int = 0,
    /** The most days in a row with listening. */
    val longestStreak: Int = 0,
    /** The first song of the year (the first one not skipped) and when it played. */
    val firstSong: RecapSong? = null,
    val firstAt: Long? = null,
) {
    val empty: Boolean get() = listenedMs <= 0L
}

/**
 * Your year in music, once a year: all through January, the year that just ended. Library listens
 * (play_events) and online ones (online_plays) together; a song heard online and later downloaded
 * counts as one (same YouTube id).
 */
object Recaps {
    const val TOP = 5

    /** The year the retrospective shows at [now]: last year during January, none the rest of the year. */
    fun yearShown(now: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (today.month == Month.JANUARY) today.year - 1 else null
    }

    /** [year]'s span in local time: Jan 1st (inclusive) to the next Jan 1st (exclusive). */
    fun span(year: Int, zone: ZoneId = ZoneId.systemDefault()): LongRange {
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /** All the history at once: the profile's "Mais ouvidas" and hours (yours and what friends see). */
    fun allTime(events: List<PlayEvent>, tracksById: Map<Long, Track>, online: List<OnlinePlay>, zone: ZoneId = ZoneId.systemDefault()): Recap =
        build(0, events, tracksById, online, zone, span = Long.MIN_VALUE until Long.MAX_VALUE)

    /** "A, B & C feat. D" → "A": the artist a tap opens (a shared credit opens the main artist). */
    fun mainArtist(artist: String): String =
        artist.split(",", " & ", " feat.", " feat ", " ft.", " x ", " e ", " y ").first().trim().ifEmpty { artist.trim() }

    /**
     * "A, B feat. C" → "A": the artist the numbers credit. "&", "e" and "y" stay — they name duos
     * ("Henrique & Juliano", "Jesse y Joy"), whose time would otherwise go to "Henrique".
     */
    fun creditedArtist(artist: String): String =
        artist.split(",", " feat.", " feat ", " ft.", " ft ", " x ").first().trim().ifEmpty { artist.trim() }

    /** The same artist however the source writes it: "Henrique & Juliano" = "Henrique e Juliano" = "henrique and juliano". */
    fun artistKey(name: String): String =
        java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(JOINS, " & ")
            .replace(SPACES, " ")
            .trim()

    private val MARKS = Regex("\\p{Mn}+")
    private val JOINS = Regex("\\s+(?:&|e|and|y|\\+)\\s+")
    private val SPACES = Regex("\\s+")

    private class Acc(var title: String, var artist: String, var cover: String?, var track: Track?, var remote: RemoteTrack?) {
        var plays = 0
        var listened = 0L
    }

    private class ArtistAcc {
        var plays = 0
        var listened = 0L
        var cover: String? = null
        var coverPlays = -1
        /** How each spelling was heard: the artist is named by the most heard one. */
        val names = HashMap<String, Long>()
        val name: String get() = names.maxByOrNull { it.value }!!.key
    }

    fun build(
        year: Int,
        events: List<PlayEvent>,
        tracksById: Map<Long, Track>,
        online: List<OnlinePlay>,
        zone: ZoneId = ZoneId.systemDefault(),
        /** The time it sums up: [year] by default (the profile's stats sum up all time). */
        span: LongRange = span(year, zone),
    ): Recap {
        val songs = LinkedHashMap<String, Acc>()
        val hours = LongArray(24)
        val days = HashMap<DayOfWeek, Long>()
        val genres = HashMap<String, Int>()
        val months = LongArray(12)
        val heardOn = HashSet<LocalDate>()
        var first: Pair<Long, String>? = null
        var total = 0L
        var plays = 0

        fun moment(at: Long, listened: Long) {
            val t = Instant.ofEpochMilli(at).atZone(zone)
            hours[t.hour] += listened
            days.merge(t.dayOfWeek, listened, Long::plus)
            months[t.monthValue - 1] += listened
            if (listened > 0) heardOn += t.toLocalDate()
        }

        fun firstCandidate(at: Long, key: String, skipped: Boolean) {
            if (!skipped && (first == null || at < first!!.first)) first = at to key
        }

        for (e in events) {
            if (e.playedAt !in span) continue
            val t = tracksById[e.trackId] ?: continue
            val key = t.videoId ?: "t:${t.id}"
            val acc = songs.getOrPut(key) { Acc(t.title, t.artist, t.coverPath, t, null) }
            // The library copy wins: it plays offline and has the cover on the phone.
            acc.track = t
            acc.title = t.title
            acc.artist = t.artist
            acc.cover = t.coverPath ?: acc.cover
            acc.listened += e.listenedMs
            total += e.listenedMs
            moment(e.playedAt, e.listenedMs)
            firstCandidate(e.playedAt, key, e.skipped)
            if (!e.skipped) {
                acc.plays++
                plays++
                t.genre?.trim()?.takeIf { it.isNotEmpty() }?.let { genres.merge(it, 1, Int::plus) }
            }
        }
        for (p in online) {
            if (p.playedAt !in span) continue
            val acc = songs.getOrPut(p.videoId) {
                Acc(p.title, p.artist, p.thumbnailUrl, null, null)
            }
            if (acc.track == null && acc.remote == null) {
                acc.remote = RemoteTrack(
                    videoId = p.videoId,
                    url = p.url?.takeIf { it.startsWith("http") } ?: YouTubeLinks.watchUrl(p.videoId, music = true),
                    title = p.title,
                    artist = p.artist,
                    album = p.album,
                    durationMs = p.durationMs.takeIf { it > 0 },
                    thumbnailUrl = p.thumbnailUrl,
                    fromMusicCatalog = p.url?.contains("music.youtube.com") == true,
                )
            }
            if (acc.cover == null) acc.cover = p.thumbnailUrl
            acc.listened += p.listenedMs
            total += p.listenedMs
            moment(p.playedAt, p.listenedMs)
            firstCandidate(p.playedAt, p.videoId, p.skipped)
            if (!p.skipped) {
                acc.plays++
                plays++
            }
        }

        val artists = HashMap<String, ArtistAcc>()
        for (a in songs.values) {
            val name = creditedArtist(a.artist)
            if (name.isEmpty()) continue
            val acc = artists.getOrPut(artistKey(name)) { ArtistAcc() }
            acc.plays += a.plays
            acc.listened += a.listened
            acc.names.merge(name, a.listened, Long::plus)
            // The artist's face: the cover of their most played song.
            if (a.cover != null && a.plays > acc.coverPlays) {
                acc.cover = a.cover
                acc.coverPlays = a.plays
            }
        }

        val ranked = songs.entries
            .filter { it.value.plays > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Acc>> { it.value.plays }.thenByDescending { it.value.listened })
            .take(TOP)
            .map { (key, a) -> RecapSong(key, a.title, a.artist, a.cover, a.plays, a.listened, a.track, a.remote) }
        // Artists show their time, so time ranks them; songs show how many times, so plays rank them.
        val topArtists = artists.values
            .filter { it.plays > 0 }
            .sortedWith(compareByDescending<ArtistAcc> { it.listened }.thenByDescending { it.plays })
            .take(TOP)
            .map { RecapArtist(it.name, it.cover, it.plays, it.listened) }

        return Recap(
            year = year,
            listenedMs = total,
            plays = plays,
            // Heard, not skipped past: a song (or an artist) counts once it was listened to for real.
            songs = songs.values.count { it.plays > 0 },
            artists = artists.values.count { it.plays > 0 },
            topSongs = ranked,
            topArtists = topArtists,
            topGenre = genres.maxByOrNull { it.value }?.key,
            favoriteHour = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 },
            favoriteDay = days.maxByOrNull { it.value }?.key,
            months = months.toList(),
            daysListened = heardOn.size,
            longestStreak = longestRun(heardOn),
            firstSong = first?.let { (_, key) -> songs[key]?.let { a -> RecapSong(key, a.title, a.artist, a.cover, a.plays, a.listened, a.track, a.remote) } },
            firstAt = first?.first,
        )
    }

    /** The most consecutive days in [days]. */
    internal fun longestRun(days: Set<LocalDate>): Int {
        var best = 0
        for (d in days) {
            if (d.minusDays(1) in days) continue // not the start of a run
            var n = 1
            while (d.plusDays(n.toLong()) in days) n++
            best = maxOf(best, n)
        }
        return best
    }
}
