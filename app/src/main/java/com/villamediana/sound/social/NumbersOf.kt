package com.villamediana.sound.social

import com.villamediana.sound.stats.ListeningStats
import com.villamediana.sound.stats.Recap

/**
 * The phone's own numbers ([ListeningStats]) as the profile shows them ([forFriends] = false) and
 * friends receive them ([forFriends] = true).
 */
object NumbersOf {
    fun period(s: ListeningStats, forFriends: Boolean = true): NumPeriod {
        val r = s.recap
        return NumPeriod(
            minutes = r.listenedMs / 60_000L,
            songs = r.songs,
            artists = r.artists,
            hour = r.favoriteHour,
            weekdays = s.weekdays.map { it / 60_000L },
            // An artist's face is the cover of their most played song; a file on this phone means nothing elsewhere.
            top = r.topArtists.map { a -> NumArtist(a.name, a.listenedMs / 60_000L, a.cover?.takeIf { !forFriends || it.startsWith("https://") }) },
        )
    }

    /** "Mais ouvidas": the songs of [r]'s ranking that other phones can play (they have a YouTube id). */
    fun top(r: Recap): List<SongRef> = r.topSongs.mapNotNull { s -> s.track?.let(SongRef::of) ?: s.remote?.let(SongRef::of) }
}
