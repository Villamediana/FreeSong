package com.villamediana.sound.ui.search

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.shareUrl
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.playback.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What one tap on an online song does. */
sealed interface SongTapAction {
    /** Stream the visible songs from this one (the player uses the local copy when there is one). */
    data object PlayOnline : SongTapAction
    /** Play the downloaded copy (and the downloaded songs around it). */
    data object PlayDownloaded : SongTapAction
    data object Download : SongTapAction
    data class Retry(val taskId: Long) : SongTapAction
    /** Already queued/downloading: nothing to do. */
    data object Busy : SongTapAction
    /** Online play wanted, no network and no local copy: say so. */
    data object Offline : SongTapAction

    companion object {
        /**
         * [playOnline] = Settings.playOnlineByDefault. Streaming wins while there is network; without
         * it a downloaded song still plays, and with online play off taps download as before.
         */
        fun of(state: ItemDownload, playOnline: Boolean, online: Boolean): SongTapAction = when {
            playOnline && online -> PlayOnline
            state == ItemDownload.Done -> PlayDownloaded
            playOnline -> Offline
            state == ItemDownload.None -> Download
            state is ItemDownload.Failed -> Retry(state.taskId)
            else -> Busy
        }

        /** The trailing download button: always about keeping the song on the phone. */
        fun ofIndicator(state: ItemDownload): SongTapAction = when (state) {
            ItemDownload.None -> Download
            is ItemDownload.Failed -> Retry(state.taskId)
            ItemDownload.Done -> PlayDownloaded
            ItemDownload.Queued, is ItemDownload.Running -> Busy
        }
    }
}

/** The song the player is on, by YouTube id (rows of online lists light up with it). */
data class NowPlaying(val videoId: String, val playing: Boolean)

fun Flow<PlaybackState>.nowPlaying(): Flow<NowPlaying?> =
    map { s -> s.current?.videoId?.let { NowPlaying(it, s.isPlaying) } }.distinctUntilChanged()

/** Playing state of [videoId] for a row: null = not the current song. */
fun NowPlaying?.of(videoId: String): Boolean? = this?.takeIf { it.videoId == videoId }?.playing

/**
 * A song (streamed or downloaded) as the online side knows it, to download it. The source builds
 * every url with YouTubeLinks.watchUrl(id, music = fromMusicCatalog), so the host says where it
 * came from. Null for songs that never came from YouTube.
 */
fun Track.toRemoteTrack(): RemoteTrack? {
    val id = videoId ?: return null
    val url = shareUrl ?: return null
    return RemoteTrack(
        videoId = id,
        url = url,
        title = title,
        artist = artist,
        album = album?.takeIf { it.isNotBlank() },
        durationMs = durationMs.takeIf { it > 0 },
        thumbnailUrl = coverPath?.takeIf { it.startsWith("http") },
        fromMusicCatalog = url.contains("music.youtube.com"),
    )
}
