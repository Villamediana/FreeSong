package com.villamediana.sound.download

import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.OnlinePlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDownloadsTest {
    private fun play(videoId: String, at: Long, skipped: Boolean = false) = OnlinePlay(
        videoId = videoId, title = "T $videoId", artist = "A", url = "https://music.youtube.com/watch?v=$videoId",
        thumbnailUrl = "https://i/$videoId", durationMs = 200_000, playedAt = at, listenedMs = 150_000, completed = !skipped, skipped = skipped,
    )

    private fun task(videoId: String, state: DownloadState) = DownloadTask(videoId = videoId, url = "u", title = "t", artist = "a", state = state)

    @Test fun songsHeardThreeTimesOnlineQualifyMostHeardFirst() {
        val plays = listOf(
            play("a", 1), play("a", 2), play("a", 3),
            play("b", 4), play("b", 5), play("b", 6), play("b", 7),
            play("c", 8), play("c", 9), // twice: not yet
            play("d", 10), play("d", 11), play("d", 12, skipped = true), // skips don't count
        )
        val picks = SmartDownloads.pick(plays, onPhone = emptySet(), tasks = emptyList())
        assertEquals(listOf("b", "a"), picks.map { it.videoId })
        assertTrue(picks.all { it.fromMusicCatalog && it.url.contains("music.youtube.com") })
    }

    @Test fun neverAgainWhatIsOnThePhoneQueuedOrWasTakenOff() {
        val plays = (1..3).flatMap { listOf(play("phone", it.toLong()), play("queued", it.toLong()), play("removed", it.toLong()), play("new", it.toLong())) }
        val picks = SmartDownloads.pick(
            plays,
            onPhone = setOf("phone"),
            tasks = listOf(task("queued", DownloadState.QUEUED), task("removed", DownloadState.DONE)),
        )
        assertEquals(listOf("new"), picks.map { it.videoId })
    }

    @Test fun aRunIsCapped() {
        val plays = (1..30).flatMap { n -> (1..3).map { play("v$n", (n * 10 + it).toLong()) } }
        assertEquals(SmartDownloads.MAX_PER_RUN, SmartDownloads.pick(plays, emptySet(), emptyList()).size)
    }
}
