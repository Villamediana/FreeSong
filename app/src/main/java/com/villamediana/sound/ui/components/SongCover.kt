package com.villamediana.sound.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.SongCovers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The app's [SongCovers] (none in tests and previews: thumbnails as they come). */
val LocalSongCovers = staticCompositionLocalOf<SongCovers?> { null }

/**
 * The picture to show for a song: its own thumbnail when it is from the music catalog (album art
 * already), else — a video upload, whose thumbnail is a frame of the clip — the album cover found
 * for it, once found (the frame meanwhile).
 */
@Composable
fun rememberSongCover(videoId: String?, title: String, artist: String, thumbnail: String?, catalog: Boolean): String? {
    val covers = LocalSongCovers.current
    if (catalog || videoId.isNullOrBlank() || covers == null) return thumbnail
    LaunchedEffect(videoId) { covers.request(videoId, title, artist) }
    val found by remember(covers, videoId) { covers.found.map { it[videoId] }.distinctUntilChanged() }
        .collectAsState(initial = covers.found.value[videoId])
    return found?.takeIf { it.isNotEmpty() } ?: thumbnail
}

@Composable
fun rememberSongCover(track: RemoteTrack): String? =
    rememberSongCover(track.videoId, track.title, track.artist, track.thumbnailUrl, track.fromMusicCatalog)

/** A streamed song in the player (transient track): its url says whether it is from the catalog. */
@Composable
fun rememberSongCover(track: Track): String? {
    if (!track.isOnline) return track.coverPath
    val catalog = (track.sourceUrl ?: track.filePath).contains("music.youtube.com")
    return rememberSongCover(track.videoId, track.title, track.artist, track.coverPath, catalog)
}
