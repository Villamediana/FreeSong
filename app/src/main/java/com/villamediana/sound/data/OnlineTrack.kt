package com.villamediana.sound.data

import com.villamediana.sound.data.db.LikedOnline
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import kotlin.math.abs

/*
 * Online (streamed, not downloaded) songs travel through the player and the UI as transient
 * [Track] objects that are never stored: negative id, filePath = the watch url, coverPath = the
 * thumbnail url (Cover() loads http urls). In the media session their mediaId is "yt:<videoId>".
 */

const val ONLINE_MEDIA_PREFIX = "yt:"

/** True for a streamed song that is not in the library. */
val Track.isOnline: Boolean get() = id < 0

fun onlineTrackId(videoId: String): Long = -(abs(videoId.hashCode().toLong()) + 1)

fun RemoteTrack.asTransientTrack(): Track = Track(
    id = onlineTrackId(videoId),
    videoId = videoId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs ?: 0,
    filePath = url,
    mimeType = "",
    coverPath = thumbnailUrl,
    sourceUrl = url,
    addedAt = 0,
)

/** Public link to share a song: the page it came from, else its YouTube Music url. */
val Track.shareUrl: String?
    get() = sourceUrl?.takeIf { it.startsWith("http") }
        ?: videoId?.let { "https://music.youtube.com/watch?v=$it" }

fun LikedOnline.Companion.of(remote: RemoteTrack): LikedOnline = LikedOnline(
    videoId = remote.videoId,
    url = remote.url,
    title = remote.title,
    artist = remote.artist,
    album = remote.album,
    durationMs = remote.durationMs,
    thumbnailUrl = remote.thumbnailUrl,
)

fun LikedOnline.toRemote(): RemoteTrack = RemoteTrack(videoId, url, title, artist, album, durationMs, thumbnailUrl, fromMusicCatalog = "music.youtube.com" in url)

/** A streamed like as the transient track Curtidas lists and plays. */
fun LikedOnline.toTrack(): Track = toRemote().asTransientTrack()
