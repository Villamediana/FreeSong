package com.villamediana.sound.ui.downloads

import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGroupingTest {
    private fun task(
        id: Long,
        state: DownloadState = DownloadState.QUEUED,
        batch: String? = null,
        batchTitle: String? = null,
        createdAt: Long = 1_000,
        position: Int? = null,
        trackNumber: Int? = null,
        progress: Float = 0f,
        thumb: String? = "thumb$id",
        albumCover: String? = null,
        album: String? = null,
        playlistId: Long? = null,
    ) = DownloadTask(
        id = id, videoId = "v$id", url = "u$id", title = "t$id", artist = "a", album = album,
        trackNumber = trackNumber, thumbnailUrl = thumb, albumCoverUrl = albumCover,
        targetPlaylistId = playlistId, targetPosition = position, batchId = batch, batchTitle = batchTitle,
        state = state, progress = progress, createdAt = createdAt,
    )

    @Test
    fun batchesNewestFirstThenLooseSongs() {
        val tasks = listOf(
            task(1, batch = "old", batchTitle = "Álbum velho", createdAt = 100),
            task(2, createdAt = 50),
            task(3, batch = "new", batchTitle = "Playlist nova", createdAt = 300),
            task(4, createdAt = 400),
            task(5, batch = "old", batchTitle = "Álbum velho", createdAt = 100),
        )
        val groups = DownloadGrouping.groups(tasks)
        assertEquals(listOf("batch:new", "batch:old", DownloadGrouping.LOOSE_KEY), groups.map { it.key })
        assertEquals(listOf(4L, 2L), groups.last().tasks.map { it.id })
        assertFalse(groups.last().isBatch)
        assertNull(groups.last().title)
        assertTrue(groups.first().isBatch)
    }

    @Test
    fun batchKeepsSourceOrder() {
        val tasks = listOf(
            task(10, batch = "b", position = 2),
            task(11, batch = "b", position = 0),
            task(12, batch = "b", position = 1),
            task(13, batch = "b", trackNumber = 1),
        )
        val group = DownloadGrouping.groups(tasks).single()
        assertEquals(listOf(11L, 12L, 10L, 13L), group.tasks.map { it.id })
    }

    @Test
    fun albumOrderByTrackNumberThenCreation() {
        val tasks = listOf(
            task(1, batch = "b", trackNumber = 3),
            task(2, batch = "b", trackNumber = 1),
            task(3, batch = "b", createdAt = 5),
            task(4, batch = "b", createdAt = 1),
        )
        assertEquals(listOf(2L, 1L, 4L, 3L), DownloadGrouping.groups(tasks).single().tasks.map { it.id })
    }

    @Test
    fun batchTitleCoverAndPlaylist() {
        val withTitle = DownloadGrouping.groups(
            listOf(
                task(1, batch = "b", batchTitle = " ", album = "Canción Animal", position = 0, albumCover = "cover-big", playlistId = 9),
                task(2, batch = "b", batchTitle = "Canción Animal", position = 1),
            ),
        ).single()
        assertEquals("Canción Animal", withTitle.title)
        assertEquals("cover-big", withTitle.coverUrl)
        assertEquals(9L, withTitle.targetPlaylistId)

        val fallback = DownloadGrouping.groups(listOf(task(1, batch = "b", album = "Signos", thumb = null), task(2, batch = "b", thumb = "t2"))).single()
        assertEquals("Signos", fallback.title)
        assertEquals("t2", fallback.coverUrl)
    }

    @Test
    fun groupStats() {
        val stats = GroupStats.of(
            listOf(
                task(1, DownloadState.DONE),
                task(2, DownloadState.RUNNING, progress = 0.5f),
                task(3, DownloadState.QUEUED),
                task(4, DownloadState.FAILED),
                task(5, DownloadState.CANCELED),
            ),
        )
        assertEquals(5, stats.total)
        assertEquals(1, stats.done)
        assertEquals(1, stats.running)
        assertEquals(1, stats.queued)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.canceled)
        assertEquals(4, stats.wanted)
        assertEquals(2, stats.active)
        assertTrue(stats.isActive)
        assertEquals(1.5f / 4f, stats.fraction, 1e-5f)
        assertEquals(0f, GroupStats.of(listOf(task(1, DownloadState.CANCELED))).fraction, 0f)
    }

    @Test
    fun overviewTalksAboutTheCurrentWave() {
        val tasks = listOf(
            // History from yesterday.
            task(1, DownloadState.DONE, createdAt = 10),
            task(2, DownloadState.DONE, createdAt = 20),
            task(3, DownloadState.FAILED, createdAt = 30),
            // Today's album of 6: 2 done, 1 running at 50%, 3 queued, plus one canceled.
            task(10, DownloadState.DONE, batch = "x", createdAt = 1000),
            task(11, DownloadState.DONE, batch = "x", createdAt = 1000),
            task(12, DownloadState.RUNNING, batch = "x", createdAt = 1000, progress = 0.5f),
            task(13, DownloadState.QUEUED, batch = "x", createdAt = 1000),
            task(14, DownloadState.QUEUED, batch = "x", createdAt = 1000),
            task(15, DownloadState.QUEUED, batch = "x", createdAt = 1000),
            task(16, DownloadState.CANCELED, batch = "x", createdAt = 1000),
        )
        val o = DownloadGrouping.overview(tasks)
        assertTrue(o.isActive)
        assertEquals(1, o.running)
        assertEquals(3, o.queued)
        assertEquals(1, o.failed)
        assertEquals(4, o.done)
        assertEquals(1, o.canceled)
        assertEquals(2, o.waveDone)
        assertEquals(6, o.waveTotal)
        assertEquals(2.5f / 6f, o.fraction!!, 1e-5f)
    }

    @Test
    fun idleOverview() {
        val o = DownloadGrouping.overview(listOf(task(1, DownloadState.DONE), task(2, DownloadState.FAILED), task(3, DownloadState.CANCELED)))
        assertFalse(o.isActive)
        assertNull(o.fraction)
        assertEquals(1, o.done)
        assertEquals(1, o.failed)
        assertEquals(2, o.finished)
        assertFalse(o.isEmpty)
        assertTrue(DownloadGrouping.overview(emptyList()).isEmpty)
    }

    @Test
    fun finishedBatchesStartCollapsed() {
        val finished = DownloadGrouping.groups(listOf(task(1, DownloadState.DONE, batch = "b"), task(2, DownloadState.DONE, batch = "b"))).single()
        val withFailure = DownloadGrouping.groups(listOf(task(1, DownloadState.DONE, batch = "b"), task(2, DownloadState.FAILED, batch = "b"))).single()
        val running = DownloadGrouping.groups(listOf(task(1, DownloadState.DONE, batch = "b"), task(2, DownloadState.RUNNING, batch = "b"))).single()
        val loose = DownloadGrouping.groups(listOf(task(1, DownloadState.DONE))).single()
        assertTrue(finished.collapsedByDefault())
        assertFalse(withFailure.collapsedByDefault())
        assertFalse(running.collapsedByDefault())
        assertFalse(loose.collapsedByDefault())
    }
}
