package com.villamediana.surco.playback

import android.content.Context
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.smart.MixLabels
import com.villamediana.surco.smart.SmartMixes
import com.villamediana.surco.smart.SmartQueue
import com.villamediana.surco.smart.Taste

/** A queue ready to play: songs, where to start, where it came from, and whether it keeps extending itself. */
internal data class QueuePlan(val tracks: List<Track>, val startIndex: Int, val source: PlaySource?, val smart: Boolean)

/** Localized texts the player needs (mix titles, "Rádio de …"), resolved once from resources. */
internal class PlaybackStrings(
    val mixLabels: MixLabels,
    val smartShuffle: String,
    val radio: (String) -> String,
    val liked: String,
    val recent: String,
    val search: (String) -> String,
) {
    companion object {
        fun from(context: Context): PlaybackStrings {
            val res = context.resources
            return PlaybackStrings(
                mixLabels = mixLabels(context),
                smartShuffle = res.getString(R.string.playback_source_smart),
                radio = { res.getString(R.string.playback_source_radio, it) },
                liked = res.getString(R.string.playback_source_liked),
                recent = res.getString(R.string.playback_source_recent),
                search = { res.getString(R.string.playback_source_search, it) },
            )
        }
    }
}

/**
 * [MixLabels] from Surco's resources (pt-BR / es). Any screen that builds mixes with
 * [SmartMixes] can use it so titles match what the player shows in "Tocando de …".
 */
fun mixLabels(context: Context): MixLabels = com.villamediana.surco.smart.surcoMixLabels(context.resources)

/** 1990 → "90", 2010 → "2010" (how decades are said in pt/es: "anos 90", "años 2010"). */
internal fun decadeLabel(decade: Int): String = if (decade in 1900..1999) "%02d".format(decade % 100) else decade.toString()

/** Turns a [SmartSeed] into a queue, and decides how smart queues keep going. Pure. */
internal object SmartPlans {
    /** The whole-library smart shuffle starts with this many and extends itself as it goes. */
    const val LIBRARY_LIMIT = 200
    const val GENRE_LIMIT = 100
    const val RADIO_LIMIT = 50
    /** Songs appended each time the queue reaches its last song. */
    const val EXTEND = 25

    fun plan(seed: SmartSeed, library: List<Track>, taste: Taste, strings: PlaybackStrings, seedValue: Long): QueuePlan? {
        val plan = when (seed) {
            SmartSeed.Library -> QueuePlan(
                SmartQueue.smartShuffle(library, taste, seedValue).take(LIBRARY_LIMIT),
                0,
                PlaySource(PlaySource.Type.SMART_SHUFFLE, strings.smartShuffle),
                smart = true,
            )
            is SmartSeed.Genre -> QueuePlan(
                SmartQueue.genre(seed.name, library, taste, GENRE_LIMIT, seedValue),
                0,
                PlaySource(PlaySource.Type.GENRE, seed.name, seed.name),
                smart = true,
            )
            is SmartSeed.Radio -> QueuePlan(
                SmartQueue.radio(seed.track, library, taste, RADIO_LIMIT, seedValue),
                0,
                PlaySource(PlaySource.Type.RADIO, strings.radio(seed.track.title), seed.track.id.toString()),
                smart = true,
            )
            is SmartSeed.Mix -> SmartMixes.byId(seed.id, library, taste, strings.mixLabels)?.let { mix ->
                QueuePlan(mix.tracks, 0, PlaySource(PlaySource.Type.MIX, mix.title, mix.id), smart = true)
            }
        }
        return plan?.takeIf { it.tracks.isNotEmpty() }
    }

    /**
     * What to append when the queue reaches its last song: more of the library for the smart
     * shuffle, more of the genre for a genre session, otherwise what fits the end of the queue.
     */
    fun extension(source: PlaySource?, queue: List<Track>, library: List<Track>, taste: Taste, seedValue: Long): List<Track> {
        val queued = queue.mapTo(HashSet()) { it.id }
        val primary = when (source?.type) {
            PlaySource.Type.SMART_SHUFFLE ->
                SmartQueue.smartShuffle(library.filter { it.id !in queued }, taste, seedValue).take(EXTEND * 2)
            PlaySource.Type.GENRE -> source.id?.let { name ->
                SmartQueue.genre(name, library, taste, limit = queued.size + EXTEND, seedValue = seedValue)
                    .filter { it.id !in queued }
                    .take(EXTEND)
            }.orEmpty()
            else -> emptyList()
        }
        return primary.ifEmpty { SmartQueue.continuation(queue, library, taste, EXTEND, seedValue) }
    }
}
