package com.villamediana.sound.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The listening history never grows forever: a bit over a year, a ceiling of rows, the hours kept. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryPruneTest {
    private lateinit var db: SoundDatabase
    private lateinit var library: LibraryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_900_000_000_000L
    private val day = 24 * 3_600_000L

    @Before fun setUp() {
        db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext())
        library = LibraryRepository(db, scope)
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun online(id: String, at: Long, ms: Long = 100_000) = OnlinePlay(
        videoId = id, title = "Song $id", artist = "A", durationMs = 200_000, url = "https://music.youtube.com/watch?v=$id",
        playedAt = at, listenedMs = ms, completed = false, skipped = false, context = "test",
    )

    @Test
    fun olderThanTheKeptSpanGoesAndItsListeningIsCounted() = runBlocking {
        val track = library.insertTrack(Track(title = "No celular", artist = "Banda", filePath = "/x.m4a", durationMs = 200_000))
        val events = db.playEvents()
        events.insert(PlayEvent(trackId = track, playedAt = now - 500 * day, listenedMs = 30_000, completed = false, skipped = false))
        events.insert(PlayEvent(trackId = track, playedAt = now - 10 * day, listenedMs = 40_000, completed = true, skipped = false))
        db.onlinePlays().insertAll(listOf(online("old", now - 401 * day, 70_000), online("recent", now - 399 * day, 80_000), online("today", now)))

        val removed = library.pruneHistory(now)

        assertEquals(30_000L + 70_000L, removed)
        assertEquals(listOf(now - 10 * day), library.playEventsListSince(0).map { it.playedAt })
        assertEquals(listOf("today", "recent"), library.onlinePlaysListSince(0).map { it.videoId })
        // Nothing more to take: a second pass removes nothing.
        assertEquals(0L, library.pruneHistory(now))
    }

    @Test
    fun evenWithinTheSpanOnlyTheNewestRowsStay() = runBlocking {
        val extra = 7
        val total = LibraryRepository.HISTORY_MAX_ROWS + extra
        // The oldest [extra] (a minute apart, all within the year) are the ones over the ceiling.
        db.onlinePlays().insertAll((0 until total).map { i -> online("v$i", now - (total - i) * 60_000L, ms = 1_000) })

        val removed = library.pruneHistory(now)

        assertEquals(extra * 1_000L, removed)
        val left = library.onlinePlaysListSince(0)
        assertEquals(LibraryRepository.HISTORY_MAX_ROWS, left.size)
        assertEquals("v$extra", left.last().videoId)
    }
}
