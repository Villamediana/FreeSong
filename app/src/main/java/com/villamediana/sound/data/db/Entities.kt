package com.villamediana.sound.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A song that lives on the phone. This is also the domain model used by the UI and the player
 * (no separate mapping layer — the library is small and the fields are the same).
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["videoId"], unique = true),
        Index("artist"),
        Index("album"),
        Index("genre"),
        Index("addedAt"),
    ],
)
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** YouTube video id this file came from (null for files imported some other way). */
    val videoId: String? = null,
    val title: String,
    val artist: String,
    /** null/blank = single (no album). */
    val album: String? = null,
    /** Artist credited on the album; falls back to [artist] when null. */
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long = 0,
    /** Absolute path of the audio file. */
    val filePath: String,
    val fileSize: Long = 0,
    /** e.g. "audio/mp4" or "audio/webm". */
    val mimeType: String = "audio/mp4",
    val bitrate: Int? = null,
    /** Absolute path of a square JPEG cover, if any. */
    val coverPath: String? = null,
    /** Absolute path of a .lrc (synced) or .txt (plain) lyrics file, if any. */
    val lyricsPath: String? = null,
    val lyricsSynced: Boolean = false,
    /** Page the user downloaded it from (youtube / music.youtube url). */
    val sourceUrl: String? = null,
    val liked: Boolean = false,
    val likedAt: Long? = null,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** ARGB dominant color extracted from the cover (cached so lists don't recompute it). */
    val accentColor: Int? = null,
) {
    val albumArtistOrArtist: String get() = albumArtist?.takeIf { it.isNotBlank() } ?: artist
    val hasAlbum: Boolean get() = !album.isNullOrBlank()
    val albumKey: AlbumKey? get() = if (hasAlbum) AlbumKey(albumArtistOrArtist, album!!) else null
}

/** Identity of an album = (album artist, album title), compared case-insensitively. */
data class AlbumKey(val artist: String, val title: String) {
    val normalized: String get() = artist.trim().lowercase() + "\u0001" + title.trim().lowercase()
}

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    /** Custom cover chosen/downloaded for the playlist; null = mosaic of its tracks' covers. */
    val coverPath: String? = null,
    /** YouTube playlist url it was imported from, if any. */
    val sourceUrl: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistTrack(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    /** Sort key inside the playlist. Gaps are fine (imports reserve slots in source order). */
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Albums the user saved to "Fixados". Keyed by [AlbumKey.normalized]. */
@Entity(tableName = "album_pins")
data class AlbumPin(
    @PrimaryKey val albumKey: String,
    val artist: String,
    val title: String,
    val pinnedAt: Long = System.currentTimeMillis(),
)

/**
 * A streamed song liked without downloading it: it shows in Curtidas and plays online. When the
 * song is downloaded, the like moves to its library row ([com.villamediana.sound.data.LibraryRepository.insertTrack]).
 */
@Entity(tableName = "liked_online")
data class LikedOnline(
    @PrimaryKey val videoId: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val likedAt: Long = System.currentTimeMillis(),
) {
    companion object
}

/** One listening session of one track — feeds "mais tocadas", smart shuffle and radio. */
@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId"), Index("playedAt")],
)
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val playedAt: Long,
    val listenedMs: Long,
    /** Listened to (almost) the end. */
    val completed: Boolean,
    /** Skipped early (< ~30 s or < 1/3 of the track). */
    val skipped: Boolean,
    /** PlaySource.type name, for stats. */
    val context: String? = null,
    /** The place it was heard at ([Place]), when the app learns places. */
    val placeId: Long? = null,
    /** Who listened along (jam friends' ids, comma-separated). */
    val people: String? = null,
)

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/** A song waiting to be / being / already downloaded. */
@Entity(tableName = "downloads", indices = [Index("videoId"), Index("state"), Index("batchId")])
data class DownloadTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    /** Cover of the album this was downloaded as part of (preferred over the video thumbnail). */
    val albumCoverUrl: String? = null,
    /** True when it came from the YouTube Music catalog (clean title/artist, no "(Official Video)"). */
    val fromMusicCatalog: Boolean = false,
    /** Local playlist the finished track must be added to (playlist downloads). */
    val targetPlaylistId: Long? = null,
    /** Position inside [targetPlaylistId] — keeps the source order even if downloads finish out of order. */
    val targetPosition: Int? = null,
    /** Groups the songs of one album/playlist download in the downloads screen. */
    val batchId: String? = null,
    val batchTitle: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    /** 0f..1f */
    val progress: Float = 0f,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
    val attempts: Int = 0,
    /** Track row created when the download finished. */
    val trackId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Row of a playlist joined with its track. */
data class PlaylistEntry(
    val rowId: Long,
    val position: Int,
    @ColumnInfo(name = "entryAddedAt") val entryAddedAt: Long,
    @Embedded val track: Track,
)

data class TrackPlays(val trackId: Long, val plays: Int)

/**
 * A playlist brought from Spotify: its songs as found on YouTube Music (JSON, in the Spotify order).
 * It streams like any online playlist; nothing is downloaded until the listener asks.
 */
@Entity(tableName = "imported_playlists")
data class ImportedPlaylist(
    /** The Spotify playlist url. */
    @PrimaryKey val url: String,
    val name: String,
    val owner: String? = null,
    val coverUrl: String? = null,
    val tracksJson: String,
    val trackCount: Int,
    val importedAt: Long = System.currentTimeMillis(),
)

/**
 * One listening session of a song played online (not in the library): the history of streamed
 * listening — the retrospective and the smart downloads read it.
 */
@Entity(tableName = "online_plays", indices = [Index("videoId"), Index("playedAt")])
data class OnlinePlay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val url: String? = null,
    val durationMs: Long = 0,
    val playedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val context: String? = null,
    val placeId: Long? = null,
    val people: String? = null,
)

/**
 * A place where music is heard, learned by itself from the phone's coarse location (only when
 * "Lembrar lugares" is on): the middle of the fixes taken there and how much was heard there. No
 * name, no address — it stays on this phone (not even in the vault).
 */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    /** Fixes that made the middle (the next one moves it less). */
    val fixes: Int,
    val firstAt: Long,
    val lastAt: Long,
)

/**
 * What a song sounds like (smart/SoundPrint: its mood and rhythm), measured from its own audio
 * while it played on this phone. About the song, not about who heard it; it stays on this phone.
 */
@Entity(tableName = "song_sounds")
data class SongSound(
    @PrimaryKey val videoId: String,
    val loud: Float,
    val bright: Float,
    val drive: Float,
    val pulse: Float,
    val tempo: Float,
    /** How much of the song was measured. */
    val seconds: Float,
    val at: Long,
)

/**
 * A streamed song in one of your playlists (not on the phone): it plays online, like an imported
 * playlist's. Shares the playlist's positions with [PlaylistTrack]; downloaded later, it becomes a
 * [PlaylistTrack] at the same position.
 */
@Entity(
    tableName = "playlist_online",
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("videoId")],
)
data class PlaylistOnline(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val playlistId: Long,
    val videoId: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val catalog: Boolean = true,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
) {
    companion object
}

/**
 * A message in the conversation with a friend: a song ([SONG]) or a text ([TEXT]), with each
 * side's reaction. Kept only on the phone — the relay delivers it and forgets it. A text keeps the
 * song fields empty. [deliveryId] is the relay's id, the same on both phones: reactions point at it.
 */
@Entity(
    tableName = "shared_songs",
    indices = [Index("friendId"), Index(value = ["direction", "deliveryId"], unique = true)],
)
data class SharedSong(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val friendId: String,
    /** [IN] (the friend sent it) or [OUT] (sent to the friend). */
    val direction: String,
    /** The relay's id for it: the same delivery arriving twice is kept once. */
    val deliveryId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val cover: String? = null,
    val url: String? = null,
    val at: Long,
    /** An incoming message seen in the conversation (the outgoing ones are seen by definition). */
    val seen: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "song") val kind: String = SONG,
    val text: String? = null,
    /** This phone's person's reaction (an emoji), and when it was set (the newest wins across phones). */
    val myReaction: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val myReactionAt: Long = 0,
    /** The friend's reaction. */
    val theirReaction: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val theirReactionAt: Long = 0,
    /** The message this one answers (its [deliveryId]), or null. */
    val replyTo: Long? = null,
    /** When the text was last corrected (0: never; the newest correction wins across phones). */
    @androidx.room.ColumnInfo(defaultValue = "0") val editedAt: Long = 0,
    /** A voice message: how long it is, its waves (one level 0–f per bar) and its audio in filesDir/voice. */
    val voiceMs: Long? = null,
    val voiceWave: String? = null,
    val voiceFile: String? = null,
) {
    val isText: Boolean get() = kind == TEXT
    val isVoice: Boolean get() = kind == VOICE
    val isSong: Boolean get() = kind == SONG
    val mine: Boolean get() = direction == OUT
    val edited: Boolean get() = editedAt > 0

    companion object {
        const val IN = "in"
        const val OUT = "out"
        const val SONG = "song"
        const val TEXT = "text"
        const val VOICE = "voice"
    }
}

