package com.villamediana.sound.social

import com.villamediana.sound.stats.ListeningStats

/** The phone's own numbers ([ListeningStats]) as the profile shows them and friends receive them. */
object NumbersOf {
    fun period(s: ListeningStats): NumPeriod {
        val r = s.recap
        return NumPeriod(
            minutes = r.listenedMs / 60_000L,
            songs = r.songs,
            artists = r.artists,
            hour = r.favoriteHour,
            weekdays = s.weekdays.map { it / 60_000L },
            // An artist's face is the cover of their most played song; a file on this phone means nothing elsewhere.
            top = r.topArtists.map { a -> NumArtist(a.name, a.listenedMs / 60_000L, a.cover?.takeIf { it.startsWith("https://") }) },
        )
    }
}
