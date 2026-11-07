package com.villamediana.sound.data

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Playlists imported from Spotify: kept as online playlists (their songs streamed, nothing downloaded). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportedPlaylistTest {
    private lateinit var db: SoundDatabase
    private lateinit var library: LibraryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SoundDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val url = "https://open.spotify.com/playlist/abcdefghijklmnopqrstuv"
    private fun song(id: String, title: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, "Banda", "Disco", 200_000, "https://lh3/$id", fromMusicCatalog = true)

    @Before fun setUp() {
        db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext())
        library = LibraryRepository(db, scope)
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    @Test fun savedThenOpenedAsAnOnlinePlaylist() = runBlocking {
        library.saveImported(url, "Domingo", "Miguel", "https://i.scdn.co/x", listOf(song("a1", "Um"), song("b2", "Dois")))
        val list = library.imported.first().single()
        assertEquals("Domingo", list.name)
        assertEquals(2, list.trackCount)

        val detail = library.importedDetail(url)!!
        assertEquals(CollectionKind.PLAYLIST, detail.collection.kind)
        assertEquals(url, detail.collection.url)
        assertEquals("Miguel", detail.collection.author)
        assertEquals(listOf(song("a1", "Um"), song("b2", "Dois")), detail.tracks)
        // Nothing went to the library: the songs stream.
        assertTrue(library.allTracks().isEmpty())
    }

    @Test fun importingAgainRefreshesAndRemovingForgets() = runBlocking {
        library.saveImported(url, "Domingo", null, null, listOf(song("a1", "Um")))
        library.saveImported(url, "Domingo novo", null, null, listOf(song("a1", "Um"), song("c3", "Três")))
        assertEquals(listOf("Domingo novo"), library.imported.first().map { it.name })
        assertEquals(2, library.importedDetail(url)!!.tracks.size)
        library.removeImported(url)
        assertTrue(library.imported.first().isEmpty())
        assertNull(library.importedDetail(url))
    }

    @Test fun onlineListensAreKeptWithTheirSong() = runBlocking {
        library.recordOnlinePlay(song("a1", "Um"), listenedMs = 190_000, durationMs = 200_000, context = "RADIO", now = 1_000)
        library.recordOnlinePlay(song("a1", "Um"), listenedMs = 5_000, durationMs = 200_000, context = "RADIO", now = 2_000)
        library.recordOnlinePlay(song("b2", "Dois"), listenedMs = 500, durationMs = 200_000, context = null, now = 3_000) // too short: not a listen
        val plays = library.onlinePlaysListSince(0)
        assertEquals(listOf(2_000L, 1_000L), plays.map { it.playedAt })
        assertTrue(plays[1].completed)
        assertTrue(plays[0].skipped)
        assertEquals("Um", plays[0].title)
        library.clearHistory()
        assertTrue(library.onlinePlaysListSince(0).isEmpty())
    }

    @Test fun version3DatabasesGainTheOnlineHistory() {
        migrations.createDatabase("migrate4.db", 3).close()
        val v4 = migrations.runMigrationsAndValidate("migrate4.db", 4, true)
        v4.query("SELECT COUNT(*) FROM online_plays").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        v4.close()
    }

    @Test fun version2DatabasesGainTheImportedTable() {
        migrations.createDatabase("migrate3.db", 2).close()
        val v3 = migrations.runMigrationsAndValidate("migrate3.db", 3, true)
        v3.query("SELECT COUNT(*) FROM imported_playlists").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        v3.close()
    }
}
