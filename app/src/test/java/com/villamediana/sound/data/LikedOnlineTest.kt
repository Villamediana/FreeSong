package com.villamediana.sound.data

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Curtidas with streamed songs: liked without a library row, moved to the row once downloaded. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LikedOnlineTest {
    private lateinit var db: SoundDatabase
    private lateinit var library: LibraryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SoundDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val iris = RemoteTrack("iris1234567", "https://music.youtube.com/watch?v=iris1234567", "Iris", "Goo Goo Dolls", durationMs = 289_000, thumbnailUrl = "https://i.ytimg.com/iris.jpg", fromMusicCatalog = true)

    @Before fun setUp() {
        db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext())
        library = LibraryRepository(db, scope)
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun downloaded(videoId: String, title: String) = Track(title = title, artist = "Goo Goo Dolls", filePath = "/music/$videoId.m4a", videoId = videoId, durationMs = 289_000)

    @Test fun aStreamedLikeIsInCurtidasAndPlaysOnline() = runBlocking {
        library.setLiked(iris, true)
        val liked = library.likedAll.first()
        assertEquals(listOf("Iris"), liked.map { it.title })
        assertTrue("streamed (transient) song", liked.single().isOnline)
        assertEquals("iris1234567", liked.single().videoId)
        assertEquals(setOf("iris1234567"), library.likedVideoIds.first())

        library.setLiked(iris, false)
        assertTrue(library.likedAll.first().isEmpty())
        assertTrue(library.likedVideoIds.first().isEmpty())
    }

    @Test fun downloadingALikedStreamedSongMovesTheLikeToItsLibraryRow() = runBlocking {
        library.setLiked(iris, true)
        val id = library.insertTrack(downloaded("iris1234567", "Iris"))
        val liked = library.likedAll.first()
        assertEquals(1, liked.size)
        assertEquals(id, liked.single().id)
        assertTrue(liked.single().liked)
        assertTrue(library.likedOnline.first().isEmpty())
    }

    @Test fun theHeartOfASongOnThePhoneLikesItsLibraryRow() = runBlocking {
        val id = library.insertTrack(downloaded("iris1234567", "Iris"))
        library.setLiked(iris, true)
        assertTrue(library.getTrack(id)!!.liked)
        assertTrue(library.likedOnline.first().isEmpty())
        library.setLiked(iris, false)
        assertFalse(library.getTrack(id)!!.liked)
    }

    @Test fun curtidasMixesBothNewestLikeFirst() = runBlocking {
        val id = library.insertTrack(downloaded("other123456", "Slide"))
        library.setLiked(id, true)
        Thread.sleep(5)
        library.setLiked(iris, true)
        assertEquals(listOf("Iris", "Slide"), library.likedAll.first().map { it.title })
    }

    @Test fun version1DatabasesGainTheLikedOnlineTable() {
        migrations.createDatabase("migrate.db", 1).close()
        val v2 = migrations.runMigrationsAndValidate("migrate.db", 2, true)
        v2.query("SELECT COUNT(*) FROM liked_online").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        v2.close()
    }
}
