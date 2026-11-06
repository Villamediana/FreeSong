package com.villamediana.surco.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.villamediana.surco.data.db.Track
import java.io.File

/** Track → Media3 [MediaItem] (mediaId = Track.id, uri = the local file, full metadata). */
internal object MediaItems {
    fun of(track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(Uri.fromFile(File(track.filePath)))
            .setMediaMetadata(metadata(track).build())
            .build()

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

    fun coverUri(path: String?): Uri? = path?.takeIf { it.isNotBlank() }?.let { Uri.fromFile(File(it)) }

    /** True when [item] already shows what [track] says (title edits, new cover…). */
    fun matches(item: MediaItem, track: Track): Boolean {
        val m = item.mediaMetadata
        return m.title?.toString() == track.title &&
            m.artist?.toString() == track.artist &&
            m.albumTitle?.toString() == track.album?.takeIf { it.isNotBlank() } &&
            m.albumArtist?.toString() == track.albumArtistOrArtist &&
            m.genre?.toString() == track.genre?.takeIf { it.isNotBlank() } &&
            m.trackNumber == track.trackNumber &&
            m.recordingYear == track.year &&
            m.artworkUri == coverUri(track.coverPath) &&
            item.localConfiguration?.uri == Uri.fromFile(File(track.filePath))
    }

    fun trackId(item: MediaItem?): Long? = item?.mediaId?.toLongOrNull()
}
