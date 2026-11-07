package com.villamediana.sound.data

import com.villamediana.sound.data.isOnline
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

    @Test fun yourPlaylistsHoldStreamedSongsWithoutDownloading() = runBlocking {
        val list = library.createPlaylist("Domingo")
        val localId = library.insertTrack(com.villamediana.sound.data.db.Track(title = "No celular", artist = "Banda", filePath = "/x/a.m4a", videoId = "local000001"))
        library.addToPlaylist(list, listOf(localId))
        assertTrue(library.addOnlineToPlaylist(list, song("b2", "Online")))
        // Twice is still once.
        assertTrue(!library.addOnlineToPlaylist(list, song("b2", "Online")))
        library.addOnlineToPlaylist(list, song("c3", "Outra online"))

        var detail = library.playlist(list).first()!!
        assertEquals(listOf("No celular", "Online", "Outra online"), detail.tracks.map { it.title })
        assertEquals(3, detail.summary.trackCount)
        assertTrue(detail.tracks[1].isOnline)
        // Nothing was downloaded.
        assertEquals(1, library.allTracks().size)

        // Moving mixes both kinds; removing a streamed row and putting it back keeps its slot.
        library.movePlaylistEntry(list, from = 2, to = 0)
        detail = library.playlist(list).first()!!
        assertEquals(listOf("Outra online", "No celular", "Online"), detail.tracks.map { it.title })
        val online = detail.entries.first { it.track.title == "Online" }
        library.removeFromPlaylist(list, online.rowId)
        assertEquals(listOf("Outra online", "No celular"), library.playlist(list).first()!!.tracks.map { it.title })
        library.putBackInPlaylist(list, online.track, online.position)
        assertEquals(listOf("Outra online", "No celular", "Online"), library.playlist(list).first()!!.tracks.map { it.title })

        // Downloaded later: it becomes the playlist's song on the phone, same slot.
        library.insertTrack(com.villamediana.sound.data.db.Track(title = "Online", artist = "Banda", filePath = "/x/b.m4a", videoId = "b2"))
        detail = library.playlist(list).first()!!
        assertEquals(listOf("Outra online", "No celular", "Online"), detail.tracks.map { it.title })
        assertTrue(!detail.tracks[2].isOnline)
    }

    @Test fun version4DatabasesGainStreamedPlaylistRows() {
        migrations.createDatabase("migrate5.db", 4).close()
        val v5 = migrations.runMigrationsAndValidate("migrate5.db", 5, true)
        v5.query("SELECT COUNT(*) FROM playlist_online").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        v5.close()
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

    @Test fun aSongComesOutAndGoesBackInItsPlace() = runBlocking {
        library.saveImported(url, "Domingo", "Miguel", null, listOf(song("a1", "Um"), song("b2", "Dois"), song("c3", "Três")))
        library.removeFromImported(url, 1, "b2")
        assertEquals(listOf("a1", "c3"), library.importedDetail(url)!!.tracks.map { it.videoId })
        assertEquals(2, library.imported.first().single().trackCount)
        // Undo: back in the same slot (and never twice).
        library.putBackInImported(url, song("b2", "Dois"), 1)
        library.putBackInImported(url, song("b2", "Dois"), 1)
        assertEquals(listOf("a1", "b2", "c3"), library.importedDetail(url)!!.tracks.map { it.videoId })
        // An index that moved meanwhile: the song is found by its id.
        library.removeFromImported(url, 0, "c3")
        assertEquals(listOf("a1", "b2"), library.importedDetail(url)!!.tracks.map { it.videoId })
    }

    @Test fun editInfoRenamesAndChangesTheCover() = runBlocking {
        library.saveImported(url, "Domingo", "Miguel", "https://i.scdn.co/x", listOf(song("a1", "Um")))
        library.editImported(url, "  Sábado  ", "/covers/imported-1.jpg")
        val p = library.imported.first().single()
        assertEquals("Sábado", p.name)
        assertEquals("/covers/imported-1.jpg", p.coverUrl)
        assertEquals(1, library.importedDetail(url)!!.tracks.size)
        library.editImported(url, " ", null)
        assertEquals("Sábado", library.imported.first().single().name) // a blank name keeps the old one
    }

    @Test fun aListOfYoursAndStreamedSongsGoesIntoAPlaylistInOrder() = runBlocking {
        val mine = library.getTrack(library.insertTrack(com.villamediana.sound.data.db.Track(title = "Minha", artist = "Banda", filePath = "/m.m4a", videoId = "mine0000001")))!!
        val streamed = song("s1", "Online").asTransientTrack()
        val pid = library.createPlaylist("Mista")
        assertEquals(2, library.addSongsToPlaylist(pid, listOf(streamed, mine)))
        val entries = library.playlist(pid).first()!!.entries
        assertEquals(listOf("s1", "mine0000001"), entries.map { it.track.videoId })
        assertTrue(entries[0].track.isOnline)
        // Already there: nothing twice.
        assertEquals(0, library.addSongsToPlaylist(pid, listOf(mine, streamed)))
    }
}
