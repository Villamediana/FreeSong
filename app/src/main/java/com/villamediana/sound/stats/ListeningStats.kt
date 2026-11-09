package com.villamediana.sound.stats

import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Track
import java.time.Instant
import java.time.ZoneId

/**
 * "Seus números" on the profile: the period's [recap] (time, songs, artists, the top artists, the
 * favourite hour) and the listening per weekday. Worked out on the phone from its own history;
 * friends get only these totals.
 */
data class ListeningStats(
    val recap: Recap,
    /** Listening per weekday, Monday first (7 values). */
    val weekdays: List<Long>,
) {
    val empty: Boolean get() = recap.empty
}

enum class StatsPeriod { MONTH, YEAR }

object ListeningStatsMaker {
    /** Where [period] starts, at [now]: the 1st of the month or of the year (local time). */
    fun start(period: StatsPeriod, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = when (period) {
            StatsPeriod.MONTH -> today.withDayOfMonth(1)
            StatsPeriod.YEAR -> today.withDayOfYear(1)
        }
        return first.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The numbers of [period] at [now] from the history (library [events] and [online] plays). */
    fun build(
        period: StatsPeriod,
        events: List<PlayEvent>,
        tracksById: Map<Long, Track>,
        online: List<OnlinePlay>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ListeningStats {
        val from = start(period, now, zone)
        val year = Instant.ofEpochMilli(now).atZone(zone).year
        val recap = Recaps.build(year, events, tracksById, online, zone, span = from..now)
        val weekdays = LongArray(7)
        fun heard(at: Long, ms: Long) {
            if (ms <= 0 || at !in from..now) return
            weekdays[Instant.ofEpochMilli(at).atZone(zone).dayOfWeek.value - 1] += ms
        }
        events.forEach { if (tracksById.containsKey(it.trackId)) heard(it.playedAt, it.listenedMs) }
        online.forEach { heard(it.playedAt, it.listenedMs) }
        return ListeningStats(recap, weekdays.toList())
    }
}
