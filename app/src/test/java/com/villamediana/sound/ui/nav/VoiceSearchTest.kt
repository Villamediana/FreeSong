package com.villamediana.sound.ui.nav

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.ui.search.FakePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Ok Google, tocar … no Sound" against a real (in-memory) library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceSearchTest {
    private lateinit var db: SoundDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private val player = FakePlayer()

    @Before
    fun setUp() {
        db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        runBlocking {
            listOf(
                Track(title = "De Música Ligera", artist = "Soda Stereo", album = "Canción Animal", trackNumber = 4, genre = "Rock", filePath = "/m/1.m4a"),
                Track(title = "Canción Animal", artist = "Soda Stereo", album = "Canción Animal", trackNumber = 2, genre = "Rock", filePath = "/m/2.m4a"),
                Track(title = "Crimen", artist = "Gustavo Cerati", album = "Ahí Vamos", trackNumber = 6, genre = "Pop", filePath = "/m/3.m4a"),
            ).forEach { library.insertTrack(it) }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun play(query: String) = runBlocking { VoiceSearch.play(query, library, player) { "“$it”" } }

    @Test
    fun noQueryPlaysTheSmartShuffle() {
        assertTrue(play(""))
        assertEquals(listOf<SmartSeed>(SmartSeed.Library), player.smartPlays.toList())
        assertTrue(player.plays.isEmpty())
    }

    @Test
    fun matchingSongsPlayFirst() {
        assertTrue(play("crimen"))
        val p = player.plays.single()
        assertEquals(listOf("Crimen"), p.tracks.map { it.title })
        assertEquals(0, p.index)
        assertEquals(PlaySource(PlaySource.Type.SEARCH, "“crimen”", "crimen"), p.source)
    }

    @Test
    fun withoutSongsAPlaylistOrGenreThatMatchesPlays() {
        val pid = runBlocking {
            val ids = library.tracks.first().map { it.id }
            library.createPlaylist("Estrada").also { library.addToPlaylist(it, ids.take(2)) }
        }
        assertTrue(play("estrada"))
        val p = player.plays.single()
        assertEquals(PlaySource.Type.PLAYLIST, p.source.type)
        assertEquals(pid.toString(), p.source.id)
        assertEquals(2, p.tracks.size)

        assertTrue(play("pop"))
        assertEquals(listOf<SmartSeed>(SmartSeed.Genre("Pop")), player.smartPlays.toList())
    }

    @Test
    fun nothingInTheLibraryPlaysNothing() {
        assertFalse(play("los prisioneros"))
        assertTrue(player.plays.isEmpty())
        assertTrue(player.smartPlays.isEmpty())
    }
}
