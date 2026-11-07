package com.villamediana.sound.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.ONLINE_MEDIA_PREFIX
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.playback.stream.StreamUris
import java.io.File

/**
 * Track → Media3 [MediaItem]. Library songs: mediaId = Track.id, uri = the local file. Online songs
 * (transient tracks): mediaId = "yt:<videoId>", uri = a [StreamUris] placeholder the data source
 * resolves when it plays, artwork = the thumbnail url, watch url in the metadata extras.
 */
internal object MediaItems {
    private const val EXTRA_URL = "sound.url"
    private const val EXTRA_SUGGESTED = "sound.suggested"

    /** True for a song the app added to keep the queue going (not one the listener put there). */
    fun isSuggested(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_SUGGESTED, false) == true

    /** [item] marked as added by the app to keep the queue going. */
    fun suggested(item: MediaItem): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putBoolean(EXTRA_SUGGESTED, true) }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** [replacement] (another source or fresh metadata for the same song) keeps [old]'s mark. */
    fun keepingMark(replacement: MediaItem, old: MediaItem): MediaItem = if (isSuggested(old)) suggested(replacement) else replacement

    fun of(track: Track, quality: AudioQuality = AudioQuality.BEST): MediaItem {
        val videoId = track.videoId
        if (track.isOnline && !videoId.isNullOrBlank()) return online(track, videoId, quality)
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(Uri.fromFile(File(track.filePath)))
            .setMediaMetadata(metadata(track).build())
            .build()
    }

    private fun online(track: Track, videoId: String, quality: AudioQuality): MediaItem {
        val extras = Bundle().apply { (track.sourceUrl ?: track.filePath).takeIf { it.startsWith("http") }?.let { putString(EXTRA_URL, it) } }
        return MediaItem.Builder()
            .setMediaId(ONLINE_MEDIA_PREFIX + videoId)
            .setUri(StreamUris.audio(videoId, quality))
            .setCustomCacheKey("yt-audio:$videoId:${quality.name}")
            .setMediaMetadata(metadata(track).setExtras(extras).build())
            .build()
    }

    /** Same metadata under another id (browse tree entries carry their context in the id). */
    fun browsable(mediaId: String, track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata(track).build())
            .build()

    fun metadata(track: Track): MediaMetadata.Builder =
        MediaMetadata.Builder()
            .setTitle(track.title)
            .setDisplayTitle(track.title)
            .setArtist(track.artist)
            .setSubtitle(track.artist)
            .setAlbumTitle(track.album?.takeIf { it.isNotBlank() })
            .setAlbumArtist(track.albumArtistOrArtist)
            .setGenre(track.genre?.takeIf { it.isNotBlank() })
            .setTrackNumber(track.trackNumber)
            .setRecordingYear(track.year)
            .setReleaseYear(track.year)
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(coverUri(track.coverPath))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)

    /** A cover file path → file uri; an online thumbnail (http url) stays as it is. */
    fun coverUri(path: String?): Uri? = path?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("https://") || it.startsWith("http://")) Uri.parse(it) else Uri.fromFile(File(it))
    }

    /**
     * True when [item] already shows what [track] says (title edits, new cover…). [artworkUri] is
     * the cover uri the item is expected to carry (the service hands out content:// ones).
     */
    fun matches(item: MediaItem, track: Track, artworkUri: Uri? = coverUri(track.coverPath)): Boolean {
        val m = item.mediaMetadata
        return m.title?.toString() == track.title &&
            m.artist?.toString() == track.artist &&
            m.albumTitle?.toString() == track.album?.takeIf { it.isNotBlank() } &&
            m.albumArtist?.toString() == track.albumArtistOrArtist &&
            m.genre?.toString() == track.genre?.takeIf { it.isNotBlank() } &&
            m.trackNumber == track.trackNumber &&
            m.recordingYear == track.year &&
            m.artworkUri == artworkUri &&
            item.localConfiguration?.uri == Uri.fromFile(File(track.filePath))
    }

    /** Library track id of [item]; null for online songs (and anything else). */
    fun trackId(item: MediaItem?): Long? = item?.mediaId?.toLongOrNull()

    /** Video id of an online [item]; null for library songs. */
    fun videoId(item: MediaItem?): String? =
        item?.mediaId?.takeIf { it.startsWith(ONLINE_MEDIA_PREFIX) }?.removePrefix(ONLINE_MEDIA_PREFIX)?.takeIf { it.isNotBlank() }

    fun isOnline(item: MediaItem?): Boolean = videoId(item) != null

    /** What an online [item] carries (also on the controller side, where the uri isn't visible). */
    fun onlineItem(item: MediaItem?): OnlineItem? {
        val videoId = videoId(item) ?: return null
        val m = item!!.mediaMetadata
        return OnlineItem(
            videoId = videoId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            durationMs = m.durationMs?.takeIf { it > 0 },
            thumbnailUrl = m.artworkUri?.toString()?.takeIf { it.startsWith("http") },
            url = m.extras?.getString(EXTRA_URL),
        )
    }

    /** The transient [Track] of an online [item] (see data/OnlineTrack.kt). */
    fun onlineTrack(item: MediaItem?): Track? = onlineItem(item)?.toTrack()
}
