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

    @Query("DELETE FROM play_events")
    suspend fun clear()
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
