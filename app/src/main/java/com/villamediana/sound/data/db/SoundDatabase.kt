package com.villamediana.sound.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrack::class, AlbumPin::class, PlayEvent::class, DownloadTask::class],
    version = 1,
    exportSchema = true,
)
abstract class SoundDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun albumPins(): AlbumPinDao
    abstract fun playEvents(): PlayEventDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context): SoundDatabase =
            Room.databaseBuilder(context, SoundDatabase::class.java, "sound.db").build()

        fun inMemory(context: Context): SoundDatabase =
            Room.inMemoryDatabaseBuilder(context, SoundDatabase::class.java).allowMainThreadQueries().build()
    }
}
