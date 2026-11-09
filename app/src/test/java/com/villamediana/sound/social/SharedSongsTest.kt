package com.villamediana.sound.social

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The songs swapped with friends live in the phone's own database (the relay keeps none). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SharedSongsTest {
    private val db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val songs = SharedSongs(db.sharedSongs(), RelayApi(OkHttpClient(), "http://127.0.0.1:1") { null })

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SoundDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @After fun tearDown() = db.close()

    private fun song(n: Int) = SongRef("vid${n.toString().padStart(8, '0')}", "Música $n", "Artista", cover = "https://i.ytimg.com/vi/x/hq.jpg")

    @Test fun aDeliveryIsKeptOnceAndSeenWhenTheFriendsPageOpens() = runBlocking {
        val d = Delivery(id = 7, kind = "share", from = "uana", at = 1_000, track = song(1))
        assertTrue(songs.keep(d))
        assertFalse(songs.keep(d)) // the same delivery again (live and from the mailbox)
        val thread = songs.thread("uana").first()
        assertEquals(1, thread.size)
        assertEquals(SharedSong.IN, thread.single().direction)
        assertFalse(thread.single().seen)
        songs.markSeen("uana")
        assertTrue(songs.thread("uana").first().single().seen)
        assertFalse(songs.keep(d.copy(id = 8, track = null)))
    }

    @Test fun deletingTheProfileClearsThem() = runBlocking {
        songs.keep(Delivery(1, "share", "uana", 1_000, song(1)))
        songs.keep(Delivery(2, "share", "ubia", 2_000, song(2)))
        assertEquals(listOf("ubia", "uana"), songs.all.first().map { it.friendId })
        songs.clear()
        assertTrue(songs.all.first().isEmpty())
    }

    @Test fun version5DatabasesGainTheSharedSongs() {
        migrations.createDatabase("migrate6.db", 5).close()
        val v6 = migrations.runMigrationsAndValidate("migrate6.db", 6, true)
        v6.query("SELECT COUNT(*) FROM shared_songs").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        v6.close()
    }

    @Test fun aSongTravelsWithItsVideoAndAPictureOthersCanLoad() {
        // A downloaded song: its cover is a file here, so the others get the video's picture.
        val downloaded = Track(id = 5, videoId = "abcdefghijk", title = "No celular", artist = "Banda", filePath = "/x/a.m4a", coverPath = "/data/covers/a.jpg", durationMs = 180_000)
        val ref = SongRef.of(downloaded)!!
        assertEquals("abcdefghijk", ref.videoId)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", ref.cover)
        assertEquals(180_000L, ref.durationMs)
        // Without a YouTube id there is nothing to play on another phone.
        assertNull(SongRef.of(downloaded.copy(videoId = null)))
        // A streamed one keeps its own picture and page.
        val streamed = song(3).toRemote().asTransientTrack()
        assertEquals(song(3).cover, SongRef.of(streamed)!!.cover)
    }
}
