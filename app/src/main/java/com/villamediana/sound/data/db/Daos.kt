package com.villamediana.sound.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    suspend fun all(): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: Long): Flow<Track?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<Track>

    @Query("SELECT * FROM tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun byVideoId(videoId: String): Track?

    @Query("SELECT videoId FROM tracks WHERE videoId IS NOT NULL")
    fun observeVideoIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Delete
    suspend fun delete(track: Track)

    @Query("UPDATE tracks SET liked = :liked, likedAt = :at WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean, at: Long?)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("UPDATE tracks SET skipCount = skipCount + 1 WHERE id = :id")
    suspend fun markSkipped(id: Long)

    @Query("UPDATE tracks SET accentColor = :color WHERE id = :id")
    suspend fun setAccentColor(id: Long, color: Int?)

    @Query("UPDATE tracks SET lyricsPath = :path, lyricsSynced = :synced WHERE id = :id")
    suspend fun setLyrics(id: Long, path: String?, synced: Boolean)

    @Query("UPDATE tracks SET coverPath = :path, accentColor = NULL WHERE id = :id")
    suspend fun setCover(id: Long, path: String?)

    @Query("UPDATE tracks SET coverPath = :path, accentColor = :color WHERE id IN (:ids)")
    suspend fun setCovers(ids: List<Long>, path: String?, color: Int?)

    @Query("UPDATE tracks SET album = :album, albumArtist = :albumArtist, trackNumber = :trackNumber, year = :year WHERE id = :id")
    suspend fun setAlbumInfo(id: Long, album: String?, albumArtist: String?, trackNumber: Int?, year: Int?)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE sourceUrl = :url LIMIT 1")
    suspend fun bySourceUrl(url: String): Playlist?

    @Query("SELECT * FROM playlists")
    suspend fun all(): List<Playlist>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearRows(playlistId: Long)

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("SELECT * FROM playlist_tracks")
    fun observeAllEntries(): Flow<List<PlaylistTrack>>

    @Query(
        "SELECT pt.rowId AS rowId, pt.position AS position, pt.addedAt AS entryAddedAt, t.* " +
            "FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId " +
            "WHERE pt.playlistId = :playlistId ORDER BY pt.position, pt.rowId",
    )
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntry>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position, rowId")
    suspend fun rows(playlistId: Long): List<PlaylistTrack>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun countOf(playlistId: Long, trackId: Long): Int

    @Insert
    suspend fun insertRows(rows: List<PlaylistTrack>)

    @Query("DELETE FROM playlist_tracks WHERE rowId = :rowId")
    suspend fun deleteRow(rowId: Long)

    @Query("UPDATE playlist_tracks SET position = :position WHERE rowId = :rowId")
    suspend fun setPosition(rowId: Long, position: Int)

    @Transaction
    suspend fun rewritePositions(rowIdsInOrder: List<Long>) {
        rowIdsInOrder.forEachIndexed { index, rowId -> setPosition(rowId, index) }
    }
}

@Dao
interface AlbumPinDao {
    @Query("SELECT * FROM album_pins ORDER BY pinnedAt DESC")
    fun observeAll(): Flow<List<AlbumPin>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pin: AlbumPin)

    @Query("DELETE FROM album_pins WHERE albumKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface LikedOnlineDao {
    @Query("SELECT * FROM liked_online ORDER BY likedAt DESC")
    fun observeAll(): Flow<List<LikedOnline>>

    @Query("SELECT * FROM liked_online ORDER BY likedAt DESC")
    suspend fun all(): List<LikedOnline>

    @Query("SELECT * FROM liked_online WHERE videoId = :videoId")
    suspend fun get(videoId: String): LikedOnline?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(like: LikedOnline)

    @Query("DELETE FROM liked_online WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEvent): Long

    @Query(
        "SELECT trackId, COUNT(*) AS plays FROM play_events " +
            "WHERE playedAt >= :since AND skipped = 0 GROUP BY trackId ORDER BY plays DESC LIMIT :limit",
    )
    fun observeTopSince(since: Long, limit: Int): Flow<List<TrackPlays>>

    @Query(
        "SELECT trackId, COUNT(*) AS plays FROM play_events " +
            "WHERE playedAt >= :since AND skipped = 0 GROUP BY trackId ORDER BY plays DESC LIMIT :limit",
    )
    suspend fun topSince(since: Long, limit: Int): List<TrackPlays>

    @Query("SELECT * FROM play_events ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlayEvent>

    @Query("SELECT * FROM play_events ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayEvent>>

    @Query("SELECT * FROM play_events WHERE playedAt >= :since ORDER BY playedAt DESC")
    fun observeSince(since: Long): Flow<List<PlayEvent>>

    @Query("SELECT * FROM play_events WHERE playedAt >= :since ORDER BY playedAt DESC")
    suspend fun since(since: Long): List<PlayEvent>

    @Query("SELECT MAX(playedAt) FROM play_events")
    fun observeLastPlayedAt(): Flow<Long?>

    @Query("DELETE FROM play_events")
    suspend fun clear()

    /** Listening time of the plays [prune] would remove. */
    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM play_events WHERE playedAt < :before OR id NOT IN (SELECT id FROM play_events ORDER BY playedAt DESC, id DESC LIMIT :keep)")
    suspend fun listenedToPrune(before: Long, keep: Int): Long

    /** Drops plays older than [before] and all but the newest [keep]. */
    @Query("DELETE FROM play_events WHERE playedAt < :before OR id NOT IN (SELECT id FROM play_events ORDER BY playedAt DESC, id DESC LIMIT :keep)")
    suspend fun prune(before: Long, keep: Int): Int
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadTask?

    /** The cover url a song was downloaded with (its library row keeps only the local file). */
    @Query("SELECT thumbnailUrl FROM downloads WHERE videoId = :videoId AND thumbnailUrl IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun thumbnailOf(videoId: String): String?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadTask?>

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): DownloadTask?

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','RUNNING') ORDER BY createdAt, id")
    suspend fun pending(): List<DownloadTask>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId AND state IN ('QUEUED','RUNNING') LIMIT 1")
    suspend fun activeFor(videoId: String): DownloadTask?

    @Insert
    suspend fun insert(task: DownloadTask): Long

    @Insert
    suspend fun insertAll(tasks: List<DownloadTask>): List<Long>

    @Update
    suspend fun update(task: DownloadTask)

    @Query("UPDATE downloads SET progress = :progress, bytesDone = :done, bytesTotal = :total, updatedAt = :at WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float, done: Long, total: Long, at: Long)

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun setState(id: Long, state: DownloadState, error: String?, at: Long)

    @Query("UPDATE downloads SET state = 'QUEUED', updatedAt = :at WHERE state = 'RUNNING'")
    suspend fun requeueInterrupted(at: Long)

    @Query("DELETE FROM downloads WHERE state IN ('DONE','CANCELED')")
    suspend fun clearFinished()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ImportedPlaylistDao {
    @Query("SELECT * FROM imported_playlists ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportedPlaylist>>

    @Query("SELECT * FROM imported_playlists WHERE url = :url")
    suspend fun get(url: String): ImportedPlaylist?

    @Query("SELECT * FROM imported_playlists")
    suspend fun all(): List<ImportedPlaylist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: ImportedPlaylist)

    @Query("DELETE FROM imported_playlists WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface OnlinePlayDao {
    @Insert
    suspend fun insert(play: OnlinePlay): Long

    @Insert
    suspend fun insertAll(plays: List<OnlinePlay>)

    @Query("SELECT * FROM online_plays WHERE playedAt >= :since ORDER BY playedAt DESC")
    suspend fun since(since: Long): List<OnlinePlay>

    @Query("SELECT * FROM online_plays WHERE playedAt >= :since ORDER BY playedAt DESC")
    fun observeSince(since: Long): Flow<List<OnlinePlay>>

    @Query("SELECT MAX(playedAt) FROM online_plays")
    fun observeLastPlayedAt(): Flow<Long?>

    @Query("DELETE FROM online_plays")
    suspend fun clear()

    /** Listening time of the plays [prune] would remove. */
    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM online_plays WHERE playedAt < :before OR id NOT IN (SELECT id FROM online_plays ORDER BY playedAt DESC, id DESC LIMIT :keep)")
    suspend fun listenedToPrune(before: Long, keep: Int): Long

    /** Drops plays older than [before] and all but the newest [keep]. */
    @Query("DELETE FROM online_plays WHERE playedAt < :before OR id NOT IN (SELECT id FROM online_plays ORDER BY playedAt DESC, id DESC LIMIT :keep)")
    suspend fun prune(before: Long, keep: Int): Int
}

@Dao
interface PlaylistOnlineDao {
    @Query("SELECT * FROM playlist_online")
    fun observeAll(): Flow<List<PlaylistOnline>>

    @Query("SELECT * FROM playlist_online WHERE playlistId = :playlistId ORDER BY position, rowId")
    fun observeFor(playlistId: Long): Flow<List<PlaylistOnline>>

    @Query("SELECT * FROM playlist_online WHERE playlistId = :playlistId ORDER BY position, rowId")
    suspend fun rows(playlistId: Long): List<PlaylistOnline>

    @Query("DELETE FROM playlist_online WHERE playlistId = :playlistId")
    suspend fun clearFor(playlistId: Long)

    @Query("SELECT * FROM playlist_online WHERE videoId = :videoId")
    suspend fun byVideo(videoId: String): List<PlaylistOnline>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_online WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_online WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun countOf(playlistId: Long, videoId: String): Int

    @Insert
    suspend fun insert(row: PlaylistOnline): Long

    @Query("DELETE FROM playlist_online WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("UPDATE playlist_online SET position = :position WHERE rowId = :rowId")
    suspend fun setPosition(rowId: Long, position: Int)
}

/** Unseen messages of one conversation. */
data class UnreadCount(val friendId: String, val n: Int)

@Dao
interface SharedSongDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(song: SharedSong): Long

    @Query("SELECT * FROM shared_songs WHERE friendId = :friendId ORDER BY at, id")
    fun observeThread(friendId: String): Flow<List<SharedSong>>

    @Query("SELECT * FROM shared_songs ORDER BY at DESC, id DESC")
    fun observeAll(): Flow<List<SharedSong>>

    @Query("UPDATE shared_songs SET seen = 1 WHERE friendId = :friendId AND seen = 0")
    suspend fun markSeen(friendId: String)

    @Query("SELECT * FROM shared_songs ORDER BY at, id")
    suspend fun all(): List<SharedSong>

    @Query("UPDATE shared_songs SET seen = 1 WHERE direction = :direction AND deliveryId = :deliveryId")
    suspend fun markSeenOne(direction: String, deliveryId: Long)

    @Query("DELETE FROM shared_songs")
    suspend fun clear()

    @Query("SELECT * FROM shared_songs WHERE friendId = :friendId AND deliveryId = :deliveryId LIMIT 1")
    suspend fun find(friendId: String, deliveryId: Long): SharedSong?

    /** Your reaction (null takes it back); an older one never replaces a newer. */
    @Query("UPDATE shared_songs SET myReaction = :emoji, myReactionAt = :at WHERE friendId = :friendId AND deliveryId = :deliveryId AND myReactionAt <= :at")
    suspend fun setMyReaction(friendId: String, deliveryId: Long, emoji: String?, at: Long): Int

    /**
     * The new text of a message (only a text, only on the side that wrote it: an edit that points
     * at a message from someone else changes nothing); an older correction never replaces a newer.
     */
    @Query("UPDATE shared_songs SET text = :text, editedAt = :at WHERE friendId = :friendId AND direction = :direction AND deliveryId = :deliveryId AND kind = 'text' AND editedAt < :at")
    suspend fun editText(friendId: String, direction: String, deliveryId: Long, text: String, at: Long): Int

    /** Unseen messages per friend (for the badges). */
    @Query("SELECT friendId, count(*) AS n FROM shared_songs WHERE direction = 'in' AND seen = 0 GROUP BY friendId")
    fun observeUnread(): Flow<List<UnreadCount>>

    /** The friend's reaction, only on a message of your conversation with them. */
    @Query("UPDATE shared_songs SET theirReaction = :emoji, theirReactionAt = :at WHERE friendId = :friendId AND deliveryId = :deliveryId AND theirReactionAt <= :at")
    suspend fun setTheirReaction(friendId: String, deliveryId: Long, emoji: String?, at: Long): Int
}

@Dao
interface SongSoundDao {
    @Query("SELECT * FROM song_sounds")
    suspend fun all(): List<SongSound>

    @Query("SELECT * FROM song_sounds WHERE videoId = :videoId")
    suspend fun of(videoId: String): SongSound?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(sound: SongSound)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places")
    suspend fun all(): List<Place>

    @Insert
    suspend fun insert(place: Place): Long

    @androidx.room.Update
    suspend fun update(place: Place)

    @Query("DELETE FROM places")
    suspend fun clear()

    /** Forgetting places also takes them off the listening (the DNA forgets where, not what). */
    @Query("UPDATE play_events SET placeId = NULL WHERE placeId IS NOT NULL")
    suspend fun clearFromEvents()

    @Query("UPDATE online_plays SET placeId = NULL WHERE placeId IS NOT NULL")
    suspend fun clearFromOnline()
}
