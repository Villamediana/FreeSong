package com.villamediana.surco.smart

import com.villamediana.surco.data.db.Track

/** Builds the Home mixes from the offline library. (Stub — implemented by the playback module.) */
object SmartMixes {
    /** Strings are resolved by the caller ([labels]) so this stays pure and testable. */
    fun build(library: List<Track>, taste: Taste, labels: MixLabels): List<Mix> = emptyList()

    /** Rebuilds one mix by id (for opening/playing it later). */
    fun byId(id: String, library: List<Track>, taste: Taste, labels: MixLabels): Mix? = null
}

/** Localized titles for mixes. */
data class MixLabels(
    val genreMix: (genre: String) -> String,
    val genreMixSubtitle: (artists: String) -> String,
    val liked: String,
    val likedSubtitle: String,
    val onRepeat: String,
    val onRepeatSubtitle: String,
    val rediscover: String,
    val rediscoverSubtitle: String,
    val fresh: String,
    val freshSubtitle: String,
    val decade: (decade: Int) -> String,
    val decadeSubtitle: String,
    val artistMix: (artist: String) -> String,
    val daily: String,
    val dailySubtitle: String,
)
