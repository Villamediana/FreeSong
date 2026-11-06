package com.villamediana.surco.ui.search

import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusTest {
    private fun task(id: Long, videoId: String, state: DownloadState, progress: Float = 0f, createdAt: Long = id, error: String? = null) =
        DownloadTask(id = id, videoId = videoId, url = "https://youtu.be/$videoId", title = "t$id", artist = "a", state = state, progress = progress, createdAt = createdAt, error = error)

    @Test
    fun stateMapping() {
        val lookup = DownloadLookup.build(
            downloaded = setOf("done"),
            tasks = listOf(
                task(1, "queued", DownloadState.QUEUED),
                task(2, "running", DownloadState.RUNNING, progress = 0.42f),
                task(3, "failed", DownloadState.FAILED, error = "HTTP 403"),
                task(4, "canceled", DownloadState.CANCELED),
                task(5, "doneButDeleted", DownloadState.DONE),
                task(6, "overflow", DownloadState.RUNNING, progress = 1.7f),
            ),
        )
        assertEquals(ItemDownload.Done, lookup.stateOf("done"))
        assertEquals(ItemDownload.Queued, lookup.stateOf("queued"))
        assertEquals(ItemDownload.Running(0.42f), lookup.stateOf("running"))
        assertEquals(ItemDownload.Failed(3, "HTTP 403"), lookup.stateOf("failed"))
        assertEquals(ItemDownload.None, lookup.stateOf("canceled"))
        // The task finished but the song is no longer in the library (deleted): downloadable again.
        assertEquals(ItemDownload.None, lookup.stateOf("doneButDeleted"))
        assertEquals(ItemDownload.Running(1f), lookup.stateOf("overflow"))
        assertEquals(ItemDownload.None, lookup.stateOf("unknown"))
    }

    @Test
    fun libraryWinsOverAnyTask() {
        val lookup = DownloadLookup.build(setOf("v"), listOf(task(1, "v", DownloadState.FAILED)))
        assertEquals(ItemDownload.Done, lookup.stateOf("v"))
    }

    @Test
    fun activeTaskWinsOverNewerFinishedOne() {
        val lookup = DownloadLookup.build(
            emptySet(),
            listOf(task(9, "v", DownloadState.FAILED, createdAt = 900), task(2, "v", DownloadState.QUEUED, createdAt = 100)),
        )
        assertEquals(ItemDownload.Queued, lookup.stateOf("v"))
        assertEquals(2L, lookup.taskOf("v")?.id)
    }

    @Test
    fun newestFinishedTaskWinsWhenNothingIsActive() {
        val lookup = DownloadLookup.build(
            emptySet(),
            listOf(task(1, "v", DownloadState.CANCELED, createdAt = 100), task(2, "v", DownloadState.FAILED, createdAt = 200)),
        )
        assertEquals(ItemDownload.Failed(2, null), lookup.stateOf("v"))
        // Same createdAt: the higher id is the newer one.
        val tie = DownloadLookup.build(emptySet(), listOf(task(7, "w", DownloadState.FAILED, createdAt = 5), task(3, "w", DownloadState.CANCELED, createdAt = 5)))
        assertEquals(ItemDownload.Failed(7, null), tie.stateOf("w"))
    }

    @Test
    fun requestedSongsShowAsQueuedUntilTheQueueKnowsThem() {
        val lookup = DownloadLookup.build(
            emptySet(),
            listOf(task(1, "failed", DownloadState.FAILED), task(2, "canceled", DownloadState.CANCELED)),
            requested = setOf("new", "failed", "canceled"),
        )
        assertEquals(ItemDownload.Queued, lookup.stateOf("new"))
        assertEquals(ItemDownload.Queued, lookup.stateOf("failed"))
        assertEquals(ItemDownload.Queued, lookup.stateOf("canceled"))
        assertFalse(lookup.isKnown("new"))
        assertFalse(lookup.isKnown("failed"))
    }

    @Test
    fun isKnownMeansInLibraryOrInTheQueue() {
        val lookup = DownloadLookup.build(
            setOf("lib"),
            listOf(task(1, "q", DownloadState.QUEUED), task(2, "r", DownloadState.RUNNING), task(3, "f", DownloadState.FAILED)),
        )
        assertTrue(lookup.isKnown("lib"))
        assertTrue(lookup.isKnown("q"))
        assertTrue(lookup.isKnown("r"))
        assertFalse(lookup.isKnown("f"))
        assertFalse(lookup.isKnown("x"))
        assertTrue(lookup.isDownloaded("lib"))
        assertFalse(lookup.isDownloaded("q"))
    }

    @Test
    fun batchProgressCountsDistinctSongs() {
        val lookup = DownloadLookup.build(
            setOf("a", "b"),
            listOf(task(1, "c", DownloadState.RUNNING, progress = 0.5f), task(2, "d", DownloadState.QUEUED), task(3, "e", DownloadState.FAILED)),
        )
        val p = BatchProgress.of(listOf("a", "b", "c", "d", "e", "f", "a"), lookup)
        assertEquals(6, p.total)
        assertEquals(2, p.done)
        assertEquals(1, p.running)
        assertEquals(1, p.queued)
        assertEquals(1, p.failed)
        assertEquals(2, p.active)
        assertEquals(4, p.remaining)
        assertEquals(2.5f / 6f, p.fraction, 1e-5f)
        assertFalse(p.allDone)
        assertEquals(BatchProgress.Empty, BatchProgress.of(emptyList(), lookup))
        assertTrue(BatchProgress.of(listOf("a", "b"), lookup).allDone)
    }

    @Test
    fun primaryActionFollowsTheBatch() {
        fun progress(total: Int, done: Int, queued: Int = 0, running: Int = 0, failed: Int = 0) =
            BatchProgress(total, done, queued, running, failed, if (total == 0) 0f else done / total.toFloat())

        assertEquals(PrimaryAction.Enqueuing, PrimaryAction.of(false, progress(12, 0), hasLocalPlaylist = false, enqueuing = true))
        assertEquals(PrimaryAction.Hidden, PrimaryAction.of(false, progress(0, 0), false, false))
        assertEquals(PrimaryAction.Download(12, 12), PrimaryAction.of(false, progress(12, 0), false, false))
        assertFalse((PrimaryAction.of(false, progress(12, 0), false, false) as PrimaryAction.Download).partial)
        val partial = PrimaryAction.of(true, progress(12, 4, failed = 2), true, false)
        assertEquals(PrimaryAction.Download(8, 12), partial)
        assertTrue((partial as PrimaryAction.Download).partial)
        assertEquals(PrimaryAction.Progress(3, 12, 0.25f), PrimaryAction.of(true, progress(12, 3, queued = 8, running = 1), false, false))
        assertEquals(PrimaryAction.InLibrary, PrimaryAction.of(false, progress(12, 12), false, false))
        assertEquals(PrimaryAction.InLibrary, PrimaryAction.of(true, progress(12, 12), hasLocalPlaylist = true, enqueuing = false))
        // Every song is already on the phone but the playlist was never created.
        assertEquals(PrimaryAction.SavePlaylist, PrimaryAction.of(true, progress(12, 12), hasLocalPlaylist = false, enqueuing = false))
    }
}
