package com.villamediana.sound.ui.home

import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.smart.Taste
import com.villamediana.sound.ui.library.LibItem

enum class Greeting { MORNING, AFTERNOON, NIGHT }

/** Bom dia 5:00–11:59 · Boa tarde 12:00–17:59 · Boa noite 18:00–4:59. */
fun greetingFor(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting.MORNING
    in 12..17 -> Greeting.AFTERNOON
    else -> Greeting.NIGHT
}

/** An entry of the "Tocadas recentemente" carousel. */
sealed interface HomeItem {
    val key: String

    data class AlbumItem(val summary: AlbumSummary) : HomeItem {
        override val key get() = LibItem.albumKey(summary.key.normalized)
    }

    data class SongItem(val track: Track) : HomeItem {
        override val key get() = "t:${track.id}"
    }
}

/** Songs in order, each one shown as its album when it has one (once per album), else as itself. */
fun collapseToAlbums(tracks: List<Track>, albumsByKey: Map<String, AlbumSummary>, limit: Int): List<HomeItem> {
    val out = ArrayList<HomeItem>(limit)
    val seen = HashSet<String>()
    for (t in tracks) {
        if (out.size >= limit) break
        val album = t.albumKey?.let { albumsByKey[it.normalized] }
        val item = if (album != null) HomeItem.AlbumItem(album) else HomeItem.SongItem(t)
        if (seen.add(item.key)) out += item
    }
    return out
}

fun recentlyPlayedItems(tracks: List<Track>, albumsByKey: Map<String, AlbumSummary>, limit: Int = 12): List<HomeItem> =
    collapseToAlbums(tracks.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }, albumsByKey, limit)

const val TASTE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
const val JUST_PLAYED_WINDOW_MS = 3L * 60 * 60 * 1000

/** Taste for the mixes: plays of the last 30 days + what was played in the last few hours. */
fun buildTaste(recentPlays: Map<Long, Int>, events: List<PlayEvent>, now: Long): Taste =
    Taste(
        recentPlays = recentPlays,
        justPlayed = events.filter { now - it.playedAt in 0..JUST_PLAYED_WINDOW_MS }.mapTo(HashSet()) { it.trackId },
        now = now,
    )

/** 1990 → 90 ("Anos 90"); 2000 and later stay whole ("Anos 2010"). */
fun decadeShort(decade: Int): Int? = if (decade in 1900..1999) decade % 100 else null

/** Is the player currently playing from this collection? (drives the little live bars on tiles) */
fun PlaybackState.isFromPlaylist(id: Long): Boolean = source?.type == PlaySource.Type.PLAYLIST && source.id == id.toString()
fun PlaybackState.isFromAlbum(normalizedKey: String): Boolean = current?.albumKey?.normalized == normalizedKey
fun PlaybackState.isFromLiked(): Boolean = source?.type == PlaySource.Type.LIKED
fun PlaybackState.isFromMix(id: String): Boolean = source?.type == PlaySource.Type.MIX && source.id == id
fun PlaybackState.isSmartLibrary(): Boolean = source?.type == PlaySource.Type.SMART_SHUFFLE
