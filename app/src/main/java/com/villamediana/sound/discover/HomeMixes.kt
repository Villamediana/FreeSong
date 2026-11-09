package com.villamediana.sound.discover

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.smart.Mix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Home's mixes made from the listener's taste — one per genre they listen to ("Latino hoje")
 * and one per artist they love right now ("Porque você curte") — kept here so the card, the mix
 * page and the player all have the very same list: the card opens it, its play button plays it.
 */
class HomeMixes {
    private val _mixes = MutableStateFlow<Map<String, Mix>>(emptyMap())
    val mixes: StateFlow<Map<String, Mix>> = _mixes.asStateFlow()

    /** Puts [list] in place of the mixes whose id starts with [prefix] (a section loaded again). */
    fun replace(prefix: String, list: List<Mix>) {
        _mixes.update { m -> m.filterKeys { !it.startsWith(prefix) } + list.associateBy { it.id } }
    }

    companion object {
        const val PREFIX = "home:"
        const val GENRE = "home:genre:"
        const val BECAUSE = "home:because:"

        /**
         * A mix of [tracks] for a card: covers of four different artists for the art, and the main
         * artists in [subtitle] (minus [except], the one the card is named after).
         */
        fun build(
            id: String,
            title: String,
            tracks: List<RemoteTrack>,
            kind: Mix.Kind,
            subtitle: (artists: String) -> String,
            except: String? = null,
        ): Mix {
            val artists = tracks.map { it.artist.trim() }.filter { it.isNotEmpty() && !it.equals(except, ignoreCase = true) }
                .distinctBy { it.lowercase() }.take(SUBTITLE_ARTISTS)
            val covers = tracks.distinctBy { it.artist.lowercase() }.mapNotNull { it.thumbnailUrl }.take(4)
            return Mix(
                id = id,
                title = title,
                subtitle = subtitle(artists.joinToString(", ")),
                kind = kind,
                tracks = tracks.map { it.asTransientTrack() },
                covers = covers,
                tint = null,
            )
        }

        private const val SUBTITLE_ARTISTS = 3
    }
}
