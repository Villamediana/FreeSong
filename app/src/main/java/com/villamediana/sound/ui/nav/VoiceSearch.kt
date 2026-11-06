package com.villamediana.sound.ui.nav

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.LocalSearchResult
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlayerConnection
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.detail.Sources
import kotlinx.coroutines.flow.first

/** "Ok Google, tocar … no Sound": plays from the library, without moving the screen. */
internal object VoiceSearch {
    /** What in the library a spoken query plays. */
    sealed interface Pick {
        data class Songs(val tracks: List<Track>) : Pick
        data class Album(val key: AlbumKey) : Pick
        data class Artist(val name: String) : Pick
        data class Playlist(val id: Long, val name: String) : Pick
        data class Genre(val name: String) : Pick
    }

    /** Matching songs first; otherwise the first album, artist, playlist or genre that matches; null = nothing. */
    fun pick(local: LocalSearchResult): Pick? = when {
        local.tracks.isNotEmpty() -> Pick.Songs(local.tracks)
        local.albums.isNotEmpty() -> Pick.Album(local.albums.first().key)
        local.artists.isNotEmpty() -> Pick.Artist(local.artists.first().name)
        local.playlists.isNotEmpty() -> local.playlists.first().let { Pick.Playlist(it.id, it.name) }
        local.genres.isNotEmpty() -> Pick.Genre(local.genres.first().name)
        else -> null
    }

    /**
     * Plays [query] from the library; an empty query (Android's "play some music") is the smart
     * shuffle. False when the library has nothing for it (the caller searches online instead).
     * [searchTitle] names the queue of matching songs ("Tocando de …").
     */
    suspend fun play(query: String, library: LibraryRepository, player: PlayerConnection, searchTitle: (String) -> String): Boolean {
        if (query.isBlank()) {
            player.playSmart(SmartSeed.Library)
            return true
        }
        val (tracks, source) = when (val pick = pick(library.search(query).first())) {
            null -> return false
            is Pick.Songs -> pick.tracks to PlaySource(PlaySource.Type.SEARCH, searchTitle(query), query)
            is Pick.Album -> library.album(pick.key).first()?.let { it.tracks to Sources.album(it.summary.key.artist, it.summary.key.title, it.summary.title) }
                ?: return false
            is Pick.Artist -> library.artist(pick.name).first()?.let { it.allTracks to Sources.artist(it.summary.name) } ?: return false
            is Pick.Playlist -> library.playlist(pick.id).first()?.tracks.orEmpty() to Sources.playlist(pick.id, pick.name)
            is Pick.Genre -> {
                player.playSmart(SmartSeed.Genre(pick.name))
                return true
            }
        }
        if (tracks.isEmpty()) return false
        player.play(tracks, 0, source)
        return true
    }
}
