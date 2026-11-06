package com.villamediana.sound.smart

import com.villamediana.sound.data.LibraryRepository

/** How far back "recent plays" go for [Taste.recentPlays]. */
const val TASTE_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

/** Songs played within this window count as [Taste.justPlayed]. */
const val JUST_PLAYED_WINDOW_MS: Long = 3L * 60 * 60 * 1000

/** Builds the [Taste] the smart algorithms read: plays of the last 30 days and the last few hours. */
suspend fun LibraryRepository.loadTaste(now: Long = System.currentTimeMillis()): Taste {
    val recent = playCountsSince(now - TASTE_WINDOW_MS)
    val just = recentEvents(300).asSequence()
        .filter { it.playedAt >= now - JUST_PLAYED_WINDOW_MS }
        .map { it.trackId }
        .toSet()
    return Taste(recentPlays = recent, justPlayed = just, now = now)
}
