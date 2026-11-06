package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Track

/** Tiny DSL to build libraries for the smart tests. */
class TestLibrary {
    private var nextId = 1L
    val tracks = mutableListOf<Track>()

    fun add(
        artist: String,
        genre: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        year: Int? = null,
        title: String? = null,
        liked: Boolean = false,
        playCount: Int = 0,
        skipCount: Int = 0,
        lastPlayedAt: Long? = null,
        addedAt: Long = BASE_TIME - 60 * DAY_MS,
        cover: String? = album?.let { "/covers/${albumArtist ?: artist}-$it.jpg" },
        accent: Int? = cover?.let { it.hashCode() or 0xFF000000.toInt() },
    ): Track {
        val id = nextId++
        val t = Track(
            id = id,
            videoId = "v$id",
            title = title ?: "Song $id",
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = null,
            durationMs = 200_000,
            filePath = "/music/$id.m4a",
            coverPath = cover,
            liked = liked,
            likedAt = if (liked) addedAt else null,
            playCount = playCount,
            skipCount = skipCount,
            lastPlayedAt = lastPlayedAt,
            addedAt = addedAt,
            accentColor = accent,
        )
        tracks += t
        return t
    }

    fun repeat(n: Int, block: (Int) -> Unit) = (0 until n).forEach(block)

    companion object {
        /** 2026-03-10 12:00 UTC. */
        const val BASE_TIME = 1_773_144_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun library(block: TestLibrary.() -> Unit): List<Track> = TestLibrary().apply(block).tracks.toList()
    }
}

fun neutralTaste(now: Long = TestLibrary.BASE_TIME) = Taste(now = now)

/** Number of places where the same main artist plays twice in a row. */
fun List<Track>.artistRepeats(): Int = zipWithNext().count { (a, b) -> artistKey(a.artist) == artistKey(b.artist) }

fun List<Track>.albumRepeats(): Int =
    zipWithNext().count { (a, b) -> a.albumKey != null && a.albumKey?.normalized == b.albumKey?.normalized }
