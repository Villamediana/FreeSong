package com.villamediana.surco.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrack::class, AlbumPin::class, PlayEvent::class, DownloadTask::class],
    version = 1,
    exportSchema = true,
)
abstract class SurcoDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun albumPins(): AlbumPinDao
    abstract fun playEvents(): PlayEventDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context): SurcoDatabase =
            Room.databaseBuilder(context, SurcoDatabase::class.java, "surco.db").build()

        fun inMemory(context: Context): SurcoDatabase =
            Room.inMemoryDatabaseBuilder(context, SurcoDatabase::class.java).allowMainThreadQueries().build()
    }
}
