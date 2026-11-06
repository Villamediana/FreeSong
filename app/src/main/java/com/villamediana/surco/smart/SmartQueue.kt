package com.villamediana.surco.smart

import com.villamediana.surco.data.db.Track

/** Offline queue intelligence. (Stub — implemented by the playback module.) */
object SmartQueue {
    /** Shuffle that feels random to humans: no same artist back to back, favourites a bit earlier. */
    fun smartShuffle(tracks: List<Track>, taste: Taste, seed: Long): List<Track> = tracks.shuffled(java.util.Random(seed))

    /** Radio from a seed track: what sounds alike in the library, never the seed twice. */
    fun radio(seed: Track, library: List<Track>, taste: Taste, limit: Int = 50, seedValue: Long = 0): List<Track> = listOf(seed)

    /** Genre session: the genre smart-shuffled, flowing into neighbour genres when it runs out. */
    fun genre(genre: String, library: List<Track>, taste: Taste, limit: Int = 80, seedValue: Long = 0): List<Track> = emptyList()

    /** Songs to append when the queue ends and "autoplay similar" is on. */
    fun continuation(queue: List<Track>, library: List<Track>, taste: Taste, limit: Int = 20, seedValue: Long = 0): List<Track> = emptyList()
}
