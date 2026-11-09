package com.villamediana.sound.stats

/**
 * The retrospective told as a story: chapters one after another, each a full screen with its own
 * animation and [seconds] on screen (the last one stays). A chapter only enters when there is
 * something to tell (no genre, no chapter of genre).
 */
enum class RecapChapter(val seconds: Float) {
    INTRO(6.5f),
    FIRST(8f),
    TIME(8.5f),
    MONTHS(8.5f),
    RHYTHM(9f),
    GENRE(6.5f),
    ARTIST(10f),
    ARTISTS(8f),
    SONG(10.5f),
    SONGS(9f),
    OUTRO(0f),
    ;

    /** Moves on by itself when its time is up (the last chapter waits). */
    val advances: Boolean get() = seconds > 0f
}

/** The time of day the listening peaked. */
enum class PartOfDay { DAWN, MORNING, AFTERNOON, NIGHT }

object RecapStoryPlan {
    fun chapters(r: Recap): List<RecapChapter> = buildList {
        add(RecapChapter.INTRO)
        if (r.firstSong != null) add(RecapChapter.FIRST)
        add(RecapChapter.TIME)
        if (r.months.count { it > 0 } >= 2) add(RecapChapter.MONTHS)
        if (r.favoriteHour != null) add(RecapChapter.RHYTHM)
        if (r.topGenre != null) add(RecapChapter.GENRE)
        if (r.topArtists.isNotEmpty()) add(RecapChapter.ARTIST)
        if (r.topArtists.size >= 2) add(RecapChapter.ARTISTS)
        if (r.topSongs.isNotEmpty()) add(RecapChapter.SONG)
        if (r.topSongs.size >= 2) add(RecapChapter.SONGS)
        add(RecapChapter.OUTRO)
    }

    /**
     * Which of the year's top songs plays behind the story at chapter [index]: the #1 is kept for its
     * own chapter (the #2 plays until then). Null with no songs.
     */
    fun soundtrack(r: Recap, chapters: List<RecapChapter>, index: Int): Int? {
        if (r.topSongs.isEmpty()) return null
        if (r.topSongs.size == 1) return 0
        val crown = chapters.indexOf(RecapChapter.SONG)
        return if (crown in 0..index) 0 else 1
    }

    fun partOfDay(hour: Int): PartOfDay = when (hour) {
        in 0..4 -> PartOfDay.DAWN
        in 5..11 -> PartOfDay.MORNING
        in 12..17 -> PartOfDay.AFTERNOON
        else -> PartOfDay.NIGHT
    }

    /** The month that listened the most (0 = January), null when nothing was heard. */
    fun peakMonth(r: Recap): Int? = r.months.indices.maxByOrNull { r.months[it] }?.takeIf { r.months[it] > 0 }
}
