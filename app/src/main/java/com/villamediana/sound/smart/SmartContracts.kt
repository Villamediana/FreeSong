package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Track

/** A generated mix shown on Home ("Seu Pop", "Redescubra", "No repeat"…). */
data class Mix(
    /** Stable id, e.g. "genre:pop", "liked", "on-repeat", "rediscover", "fresh", "decade:2010". */
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: Kind,
    val tracks: List<Track>,
    /** Up to 4 covers for the card art. */
    val covers: List<String>,
    /** ARGB tint for the card (from the covers), null = theme accent. */
    val tint: Int?,
) {
    enum class Kind { GENRE, LIKED, ON_REPEAT, REDISCOVER, FRESH, DECADE, ARTIST, DAILY, MOMENT }
}

/**
 * What the "Pra agora" mix is made of: the [moment], your songs that fit it ([own], library or
 * streamed) and new ones in their style ([new]); [people] are the names of the friends along.
 */
data class MomentPlan(
    val moment: DnaMoment,
    val own: List<com.villamediana.sound.data.db.Track>,
    val new: List<com.villamediana.sound.online.RemoteTrack>,
    val people: List<String> = emptyList(),
)

/** Library facts the smart algorithms read. Built from the repository; pure data, easy to test. */
data class Taste(
    /** Plays (not skipped) per track id in the last 30 days. */
    val recentPlays: Map<Long, Int> = emptyMap(),
    /** Track ids played in the last few hours (avoid repeating them). */
    val justPlayed: Set<Long> = emptySet(),
    val now: Long = System.currentTimeMillis(),
    /**
     * How well each track fits this moment (the hour, the day, the place, who is along), from the
     * DNA: 1 = as always (the absent ones); above, it is this moment's; below, it isn't.
     */
    val fit: Map<Long, Double> = emptyMap(),
)
