package com.villamediana.sound.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrack::class, AlbumPin::class, PlayEvent::class, DownloadTask::class, LikedOnline::class, ImportedPlaylist::class, OnlinePlay::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class SoundDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun albumPins(): AlbumPinDao
    abstract fun playEvents(): PlayEventDao
    abstract fun downloads(): DownloadDao
    abstract fun likedOnline(): LikedOnlineDao
    abstract fun importedPlaylists(): ImportedPlaylistDao
    abstract fun onlinePlays(): OnlinePlayDao

    companion object {
        fun build(context: Context): SoundDatabase =
            Room.databaseBuilder(context, SoundDatabase::class.java, "sound.db").build()

        fun inMemory(context: Context): SoundDatabase =
            Room.inMemoryDatabaseBuilder(context, SoundDatabase::class.java).allowMainThreadQueries().build()
    }
}
