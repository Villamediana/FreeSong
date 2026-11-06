package com.villamediana.surco.download

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.Settings
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.SurcoDatabase
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.Lyrics
import com.villamediana.surco.online.OnlineException
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.online.RemoteTrack
import com.villamediana.surco.online.TrackMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadEngineTest {
    private lateinit var h: Harness
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        h = Harness(context)
    }

    @After fun tearDown() = h.close()

    private fun part(videoId: String) = h.storage.tempFile(videoId)

    private fun audioHits(videoId: String) = h.server.hitsFor("/audio/$videoId")

    // ------------------------------------------------------------------ loose songs

    @Test fun downloadsASongIntoTheLibrary() = runBlocking {
        val song = h.song("aaa", size = 300_000)
        h.server.put("/img/thumb_aaa", png(1280, 720, Color.RED))
        h.lyrics.result = Lyrics("[00:01.00] hola", synced = true)

        h.queue.download(listOf(song))
        assertEquals(listOf(SchedulePolicy.REPLACE), h.scheduled)
        val queued = h.task("aaa")
        assertEquals(DownloadState.QUEUED, queued.state)

        val report = h.engine.runQueue()
        assertEquals(1, report.done.size)
        assertFalse(report.networkLost)

        val task = h.task("aaa")
        assertEquals(DownloadState.DONE, task.state)
        assertEquals(1f, task.progress)
        val track = h.library.getTrack(task.trackId!!)!!
        assertEquals("aaa", track.videoId)
        assertEquals("Song aaa", track.title)
        assertEquals("Artist", track.artist)
        assertNull(track.album)
        assertEquals(180_000L, track.durationMs)
        assertEquals("audio/mp4", track.mimeType)
        assertEquals(130_000, track.bitrate)
        assertEquals(song.url, track.sourceUrl)
        assertEquals(Harness.ACCENT, track.accentColor)
        assertArrayEquals(h.audio("aaa").bytes, File(track.filePath).readBytes())
        assertEquals(300_000L, track.fileSize)
        assertTrue(track.filePath.endsWith("aaa.m4a"))
        // Cover: the single's own file, square.
        assertEquals(h.storage.coverFile("aaa").path, track.coverPath)
        val cover = BitmapFactory.decodeFile(track.coverPath)
        assertEquals(cover.width, cover.height)
        assertEquals(720, cover.width)
        // Lyrics saved as .lrc.
        assertTrue(track.lyricsSynced)
        assertEquals("[00:01.00] hola", File(track.lyricsPath!!).readText())
        // No leftovers in tmp/.
        assertFalse(part("aaa").exists())
        assertFalse(DownloadEngine.signatureFile(part("aaa")).exists())
        // Every request was a bounded range.
        assertTrue(audioHits("aaa").all { it.range != null && it.status == 206 })
    }

    @Test fun durationFallsBackToTheFileAndLyricsCanBeOff() = runBlocking {
        h.settings = Settings(fetchLyrics = false)
        h.lyrics.result = Lyrics("plain", synced = false)
        h.queue.download(listOf(h.song("bbb", durationMs = null)))
        h.engine.runQueue()
        val track = h.library.trackByVideoId("bbb")!!
        assertEquals(Harness.PROBED_DURATION, track.durationMs)
        assertNull(track.lyricsPath)
        assertNull(track.coverPath) // thumbnail url answers 404: no cover, still downloaded
    }

    @Test fun skipsSongsAlreadyInTheLibraryOrInTheQueue() = runBlocking {
        h.library.insertTrack(Track(videoId = "have", title = "Have", artist = "A", filePath = "/x.m4a"))
        h.queue.download(listOf(h.song("have"), h.song("new"), h.song("new")))
        assertEquals(listOf("new"), h.allTasks().map { it.videoId })
        h.queue.download(listOf(h.song("new")))
        assertEquals(1, h.allTasks().size)
    }

    @Test fun downloadingAFailedSongAgainReplacesItsRow() = runBlocking {
        h.youTube.failure = { OnlineException("gone", kind = OnlineException.Kind.UNAVAILABLE) }
        h.queue.download(listOf(h.song("ccc")))
        h.engine.runQueue()
        assertEquals(DownloadState.FAILED, h.task("ccc").state)
        h.youTube.failure = null
        h.queue.download(listOf(h.song("ccc")))
        val rows = h.allTasks()
        assertEquals(1, rows.size)
        assertEquals(DownloadState.QUEUED, rows.single().state)
    }

    // ------------------------------------------------------------------ albums

    private fun albumDetail(vararg ids: String, author: String? = "Soda Stereo") = CollectionDetail(
        collection = RemoteCollection(
            kind = CollectionKind.ALBUM,
            url = "https://music.youtube.com/browse/MPREb_album",
            title = "Doble Vida",
            author = author,
            thumbnailUrl = h.server.url("/img/album"),
            year = 1988,
        ),
        tracks = ids.map { h.song(it, artist = "Soda Stereo") },
    )

    @Test fun albumMetadataWinsOverTheCatalog() = runBlocking {
        h.server.put("/img/album", png(600, 600, Color.RED))
        h.server.put("/img/artwork", png(600, 600, Color.BLUE))
        h.enricher.answer = { input ->
            TrackMeta(
                title = input.title + " (clean)",
                artist = "Soda Stereo",
                album = "Wrong Album",
                albumArtist = "Wrong Artist",
                genre = "Rock",
                year = 2004,
                trackNumber = 9,
                artworkUrl = h.server.url("/img/artwork"),
            )
        }
        assertNull(h.queue.downloadCollection(albumDetail("t1", "t2")))
        val tasks = h.allTasks().sortedBy { it.trackNumber }
        assertEquals(listOf(1, 2), tasks.map { it.trackNumber })
        assertTrue(tasks.all { it.batchId == "album:https://music.youtube.com/browse/MPREb_album" && it.batchTitle == "Doble Vida" })
        assertTrue(tasks.all { it.targetPlaylistId == null })

        h.engine.runQueue()

        // The enricher saw what the album page said.
        val input = h.enricher.inputs.first { it.title == "Song t2" }
        assertEquals("Doble Vida", input.album)
        assertEquals("Soda Stereo", input.albumArtist)
        assertEquals(2, input.trackNumber)
        assertEquals(1988, input.year)

        val t2 = h.library.trackByVideoId("t2")!!
        assertEquals("Song t2 (clean)", t2.title)
        assertEquals("Doble Vida", t2.album)
        assertEquals("Soda Stereo", t2.albumArtist)
        assertEquals(2, t2.trackNumber)
        assertEquals(1988, t2.year)
        assertEquals("Rock", t2.genre)
        // Album cover (red) beats the catalog artwork (blue).
        val cover = BitmapFactory.decodeFile(t2.coverPath)
        assertTrue(cover.getPixel(300, 300).isReddish())
    }

    @Test fun songsOfAnAlbumShareOneCover() = runBlocking {
        h.server.put("/img/album", png(1200, 1200, Color.RED))
        h.queue.downloadCollection(albumDetail("s1", "s2", "s3"))
        h.engine.runQueue()
        val tracks = listOf("s1", "s2", "s3").map { h.library.trackByVideoId(it)!! }
        val expected = h.storage.coverFile(CoverArt.albumCoverName(AlbumKey("Soda Stereo", "Doble Vida"))).path
        assertTrue(tracks.all { it.coverPath == expected })
        assertEquals(1, h.server.hitsFor("/img/album").size)
        assertEquals(1, h.storage.coversDir.listFiles()!!.count { it.name.startsWith("album_") })
        val cover = BitmapFactory.decodeFile(expected)
        assertEquals(1000, cover.width) // 1200 px artwork capped at 1000
        assertEquals(1000, cover.height)
    }

    @Test fun albumWithoutAuthorFallsBackToTheTrackArtist() = runBlocking {
        h.queue.downloadCollection(albumDetail("n1", author = null))
        assertEquals("Soda Stereo", h.task("n1").albumArtist)
    }

    private fun yhlqmdlg(vararg tracks: RemoteTrack) = CollectionDetail(
        collection = RemoteCollection(
            kind = CollectionKind.ALBUM,
            url = "https://music.youtube.com/browse/MPREb_yhlqmdlg",
            title = "YHLQMDLG",
            author = "Bad Bunny",
            thumbnailUrl = h.server.url("/img/album"),
            year = 2020,
        ),
        tracks = tracks.toList(),
    )

    @Test fun anAlbumGathersTheSongAlreadyOnThePhone() = runBlocking {
        h.server.put("/img/album", png(600, 600, Color.RED))
        // Downloaded loose first: the catalog gave no album artist nor track number.
        val loose = h.song("s2", artist = "Bad Bunny, Jhay Cortez").copy(album = "YHLQMDLG")
        h.queue.download(listOf(loose))
        h.engine.runQueue()
        val before = h.library.trackByVideoId("s2")!!
        assertNull(before.trackNumber)

        h.queue.downloadCollection(yhlqmdlg(h.song("s1", artist = "Bad Bunny"), loose, h.song("s3", artist = "Bad Bunny")))
        // Not downloaded again, but in the album right away.
        assertEquals(setOf("s1", "s3"), h.allTasks().filter { it.state == DownloadState.QUEUED }.map { it.videoId }.toSet())
        val joined = h.library.trackByVideoId("s2")!!
        assertEquals("YHLQMDLG", joined.album)
        assertEquals("Bad Bunny", joined.albumArtist)
        assertEquals(2, joined.trackNumber)
        assertEquals(2020, joined.year)
        assertEquals(before.filePath, joined.filePath)

        h.engine.runQueue()
        h.background.forEach { it() }

        val key = AlbumKey("Bad Bunny", "YHLQMDLG")
        assertEquals(listOf(key to 3), LibraryRepository.groupAlbums(h.library.allTracks()).map { it.key to it.trackCount })
        assertEquals(listOf("s1", "s2", "s3"), h.library.album(key).first()!!.tracks.map { it.videoId })
        // One cover for the whole album, the song that was loose included.
        val cover = h.storage.coverFile(CoverArt.albumCoverName(key)).path
        val s2 = h.library.trackByVideoId("s2")!!
        assertTrue(listOf("s1", "s2", "s3").all { h.library.trackByVideoId(it)!!.coverPath == cover })
        assertEquals(Harness.ACCENT, s2.accentColor)
    }

    @Test fun aSongQueuedElsewhereJoinsTheAlbumWhenItLands() = runBlocking {
        h.server.put("/img/album", png(600, 600, Color.RED))
        val loose = h.song("q2", artist = "Bad Bunny, Jhay Cortez").copy(album = "YHLQMDLG")
        h.queue.download(listOf(loose))
        val album = yhlqmdlg(h.song("q1", artist = "Bad Bunny"), loose)
        h.queue.downloadCollection(album)
        // The album keeps its own task for the song; asking for the album again adds nothing.
        h.queue.downloadCollection(album)
        assertEquals(listOf("q1", "q2", "q2"), h.allTasks().map { it.videoId }.sorted())

        h.engine.runQueue()

        assertTrue(h.allTasks().all { it.state == DownloadState.DONE })
        // 200 KB in 64 KB chunks = 4 requests: downloaded once.
        assertEquals(4, audioHits("q2").size)
        val q2 = h.library.trackByVideoId("q2")!!
        assertEquals("Bad Bunny", q2.albumArtist)
        assertEquals(2, q2.trackNumber)
        assertEquals(h.storage.coverFile(CoverArt.albumCoverName(AlbumKey("Bad Bunny", "YHLQMDLG"))).path, q2.coverPath)
        assertEquals(1, LibraryRepository.groupAlbums(h.library.allTracks()).size)
    }

    // ------------------------------------------------------------------ playlists

    private fun playlistDetail(url: String, title: String, vararg ids: String) = CollectionDetail(
        collection = RemoteCollection(
            kind = CollectionKind.PLAYLIST,
            url = url,
            title = title,
            author = "Miguel",
            thumbnailUrl = h.server.url("/img/pl"),
        ),
        tracks = ids.map { h.song(it) },
        description = "Para viajar",
    )

    @Test fun playlistKeepsTheSourceOrderWhateverFinishesFirst() = runBlocking {
        h.server.put("/img/pl", png(1280, 720, Color.GREEN))
        // p1 is already on the phone; p0 is slow so p2/p3 finish before it.
        val p1Id = h.library.insertTrack(Track(videoId = "p1", title = "P1", artist = "A", filePath = "/p1.m4a"))
        val detail = playlistDetail("https://www.youtube.com/playlist?list=PL1", "Viagem", "p0", "p1", "p2", "p3")
        h.audio("p0").writeDelayMs = 40

        val playlistId = h.queue.downloadCollection(detail)!!
        val playlist = h.library.getPlaylist(playlistId)!!
        assertEquals("Viagem", playlist.name)
        assertEquals("Para viajar", playlist.description)
        assertEquals("https://www.youtube.com/playlist?list=PL1", playlist.sourceUrl)
        // The song already downloaded is in the playlist right away, in its slot.
        assertEquals(listOf(p1Id to 1), h.db.playlists().rows(playlistId).map { it.trackId to it.position })
        val tasks = h.allTasks()
        assertEquals(setOf("p0", "p2", "p3"), tasks.map { it.videoId }.toSet())
        assertTrue(tasks.all { it.targetPlaylistId == playlistId && it.batchId == "playlist:https://www.youtube.com/playlist?list=PL1" })
        assertEquals(mapOf("p0" to 0, "p2" to 2, "p3" to 3), tasks.associate { it.videoId to it.targetPosition })

        h.engine.runQueue()

        val rows = h.db.playlists().rows(playlistId)
        val byId = rows.associateBy { it.trackId }
        val order = rows.map { row -> h.library.getTrack(row.trackId)!!.videoId }
        assertEquals(listOf("p0", "p1", "p2", "p3"), order)
        // p2 really finished before p0 (its row was inserted first).
        val p0Row = byId.getValue(h.library.trackByVideoId("p0")!!.id)
        val p2Row = byId.getValue(h.library.trackByVideoId("p2")!!.id)
        assertTrue(p2Row.rowId < p0Row.rowId)

        // Cover: the playlist thumbnail, center-cropped square.
        h.background.forEach { it() }
        val coverPath = h.library.getPlaylist(playlistId)!!.coverPath
        assertEquals(h.storage.coverFile("playlist_$playlistId").path, coverPath)
        val cover = BitmapFactory.decodeFile(coverPath)
        assertEquals(720, cover.width)
        assertEquals(720, cover.height)
        assertTrue(cover.getPixel(360, 360).isGreenish())
    }

    @Test fun importingThePlaylistAgainReusesIt() = runBlocking {
        val detail = playlistDetail("https://www.youtube.com/playlist?list=PL2", "Noite", "q0", "q1")
        val first = h.queue.downloadCollection(detail)!!
        // Tapping again while it downloads queues nothing new.
        assertEquals(first, h.queue.downloadCollection(detail))
        assertEquals(2, h.allTasks().size)
        h.engine.runQueue()
        assertEquals(first, h.queue.downloadCollection(detail))
        assertEquals(2, h.allTasks().size)
        assertEquals(2, h.db.playlists().rows(first).size)
    }

    @Test fun aSongInTwoPlaylistsIsDownloadedOnce() = runBlocking {
        h.settings = Settings(parallelDownloads = 4)
        val a = h.queue.downloadCollection(playlistDetail("https://www.youtube.com/playlist?list=A", "A", "shared", "onlyA"))!!
        val b = h.queue.downloadCollection(playlistDetail("https://www.youtube.com/playlist?list=B", "B", "x", "shared"))!!
        h.engine.runQueue()
        val shared = h.library.trackByVideoId("shared")!!
        assertEquals(listOf(shared.id), h.db.playlists().rows(a).filter { it.position == 0 }.map { it.trackId })
        assertEquals(listOf(shared.id), h.db.playlists().rows(b).filter { it.position == 1 }.map { it.trackId })
        // 200 KB in 64 KB chunks = 4 requests: downloaded exactly once.
        assertEquals(4, audioHits("shared").size)
        assertTrue(h.allTasks().all { it.state == DownloadState.DONE })
    }

    // ------------------------------------------------------------------ failures & resume

    @Test fun refreshesAnExpiredStreamUrl() = runBlocking {
        h.queue.download(listOf(h.song("exp")))
        h.audio("exp").forbidden += 2
        h.engine.runQueue()
        assertEquals(DownloadState.DONE, h.task("exp").state)
        assertEquals(2, h.youTube.calls("exp"))
        assertArrayEquals(h.audio("exp").bytes, File(h.library.trackByVideoId("exp")!!.filePath).readBytes())
    }

    @Test fun serverErrorsFailAfterThreeAttempts() = runBlocking {
        h.queue.download(listOf(h.song("err")))
        h.audio("err").failWith = 500
        val report = h.engine.runQueue()
        val task = h.task("err")
        assertEquals(DownloadState.FAILED, task.state)
        assertEquals(3, task.attempts)
        assertEquals("O servidor respondeu com erro 500", task.error)
        assertEquals(listOf(task.id), report.failed.map { it.id })
        assertNull(h.library.trackByVideoId("err"))
    }

    @Test fun unavailableVideoFailsAtOnce() = runBlocking {
        h.youTube.failure = { OnlineException("Video unavailable", kind = OnlineException.Kind.UNAVAILABLE) }
        h.queue.download(listOf(h.song("gone")))
        h.engine.runQueue()
        val task = h.task("gone")
        assertEquals(DownloadState.FAILED, task.state)
        assertEquals("Vídeo indisponível", task.error)
        assertEquals(1, h.youTube.calls("gone"))
    }

    @Test fun transientErrorRecoversOnTheNextAttempt() = runBlocking {
        h.queue.download(listOf(h.song("flaky", size = 150_000)))
        h.audio("flaky").dropAt = 70_000 // connection reset mid-chunk while still online
        h.engine.runQueue()
        val task = h.task("flaky")
        assertEquals(DownloadState.DONE, task.state)
        assertEquals(1, task.attempts)
        assertArrayEquals(h.audio("flaky").bytes, File(h.library.trackByVideoId("flaky")!!.filePath).readBytes())
        // The second attempt resumed where the first one stopped.
        assertEquals("bytes=70000-135535", audioHits("flaky").map { it.range }.last { it!!.startsWith("bytes=70000") })
    }

    @Test fun losingTheNetworkRequeuesWithoutSpendingAnAttempt() = runBlocking {
        h.queue.download(listOf(h.song("net", size = 300_000)))
        h.audio("net").dropAt = 100_000
        h.online = false
        val report = h.engine.runQueue()
        assertTrue(report.networkLost)
        var task = h.task("net")
        assertEquals(DownloadState.QUEUED, task.state)
        assertEquals(0, task.attempts)
        assertEquals(100_000L, part("net").length())

        // Network back: the worker runs again and resumes from byte 100000.
        h.online = true
        h.engine.runQueue()
        task = h.task("net")
        assertEquals(DownloadState.DONE, task.state)
        assertEquals("bytes=100000-165535", audioHits("net").last { it.range!!.startsWith("bytes=100000") }.range)
        assertArrayEquals(h.audio("net").bytes, File(h.library.trackByVideoId("net")!!.filePath).readBytes())
    }

    @Test fun deadProcessIsResumedFromThePartFile() = runBlocking {
        h.queue.download(listOf(h.song("dead", size = 400_000)))
        h.audio("dead").writeDelayMs = 30
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        val id = h.task("dead").id
        h.awaitState(id, { it.bytesDone >= 70_000 })
        run.cancel() // the worker is stopped (constraints lost, process going away)
        runCatching { run.await() }
        assertEquals(DownloadState.QUEUED, h.task("dead").state)
        val kept = part("dead").length()
        assertTrue(kept >= 65_536)

        // A dead process leaves the row RUNNING: kick() puts it back and schedules the worker.
        h.dao.setState(id, DownloadState.RUNNING, null, 0)
        h.engine.idle()
        h.scheduled.clear()
        h.queue.kick()
        assertEquals(DownloadState.QUEUED, h.task("dead").state)
        assertEquals(listOf(SchedulePolicy.REPLACE), h.scheduled)

        h.audio("dead").writeDelayMs = 0
        h.engine.runQueue()
        assertEquals(DownloadState.DONE, h.task("dead").state)
        val resumed = audioHits("dead").map { it.range!! }.firstOrNull { it.startsWith("bytes=$kept-") }
        assertNotNull("expected a range starting at $kept", resumed)
        assertArrayEquals(h.audio("dead").bytes, File(h.library.trackByVideoId("dead")!!.filePath).readBytes())
    }

    @Test fun aStopWhileTheTrackIsBeingSavedKeepsItPlayable() = runBlocking {
        // The worker is stopped (network constraint, REPLACE, timeout) right while the Track row is inserted.
        val inserting = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val db = Room.inMemoryDatabaseBuilder(context, SurcoDatabase::class.java)
            .setQueryCallback(Dispatchers.Unconfined) { sql, _ ->
                if (sql.contains("INSERT", ignoreCase = true) && sql.contains("`tracks`") && armed.getAndSet(false)) {
                    inserting.countDown()
                    Thread.sleep(500)
                }
            }
            .build()
        h.close()
        h = Harness(context, db)
        h.queue.download(listOf(h.song("cut")))
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        assertTrue(inserting.await(10, TimeUnit.SECONDS))
        run.cancel()
        runCatching { run.await() }

        val track = h.library.trackByVideoId("cut")!!
        assertEquals(DownloadState.DONE, h.task("cut").state)
        assertArrayEquals(h.audio("cut").bytes, File(track.filePath).readBytes())
        assertFalse(part("cut").exists())
        assertFalse(DownloadEngine.signatureFile(part("cut")).exists())
        // The next worker has nothing left to do and leaves the file alone.
        h.engine.runQueue()
        assertTrue(File(track.filePath).exists())
        assertFalse(part("cut").exists())
    }

    @Test fun aSongFinishedElsewhereCleansTheLeftoverPart() = runBlocking {
        // An earlier attempt left a .part; meanwhile the song reached the library some other way.
        h.queue.download(listOf(h.song("left")))
        part("left").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(1_000))
        DownloadEngine.signatureFile(part("left")).writeText("sig")
        h.library.insertTrack(Track(videoId = "left", title = "Left", artist = "A", filePath = "/left.m4a"))
        h.engine.runQueue()
        assertEquals(DownloadState.DONE, h.task("left").state)
        assertFalse(part("left").exists())
        assertFalse(DownloadEngine.signatureFile(part("left")).exists())
    }

    // ------------------------------------------------------------------ cancel / retry

    @Test fun cancelStopsTheDownloadAndCleansUp() = runBlocking {
        h.queue.download(listOf(h.song("can", size = 600_000)))
        h.audio("can").writeDelayMs = 30
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        val id = h.task("can").id
        h.awaitState(id, { it.state == DownloadState.RUNNING && it.bytesDone > 0 })
        h.queue.cancel(id)
        val report = withTimeout(5_000) { run.await() }
        assertTrue(report.done.isEmpty())
        val task = h.task("can")
        assertEquals(DownloadState.CANCELED, task.state)
        assertFalse(part("can").exists())
        assertFalse(DownloadEngine.signatureFile(part("can")).exists())
        assertNull(h.library.trackByVideoId("can"))
        assertTrue(h.storage.audioDir.listFiles().isNullOrEmpty())
    }

    @Test fun cancelAllCancelsEverythingPending() = runBlocking {
        h.queue.download(listOf(h.song("c1"), h.song("c2"), h.song("c3")))
        h.queue.cancelAll()
        assertTrue(h.allTasks().all { it.state == DownloadState.CANCELED })
        val report = h.engine.runQueue()
        assertEquals(0, report.finished)
        assertTrue(audioHits("c1").isEmpty())
        h.queue.clearFinished()
        assertTrue(h.allTasks().isEmpty())
    }

    @Test fun retryPutsAFailedTaskBackInTheQueue() = runBlocking {
        h.queue.download(listOf(h.song("re")))
        h.audio("re").failWith = 503
        h.engine.runQueue()
        val failed = h.task("re")
        assertEquals(DownloadState.FAILED, failed.state)

        h.audio("re").failWith = null
        h.engine.idle()
        h.scheduled.clear()
        h.queue.retry(failed.id)
        val queued = h.task("re")
        assertEquals(DownloadState.QUEUED, queued.state)
        assertEquals(0, queued.attempts)
        assertNull(queued.error)
        assertEquals(listOf(SchedulePolicy.REPLACE), h.scheduled)
        h.engine.runQueue()
        assertEquals(DownloadState.DONE, h.task("re").state)
    }

    @Test fun retryAllFailedOnlyTouchesFailedTasks() = runBlocking {
        h.queue.download(listOf(h.song("f1"), h.song("f2"), h.song("ok")))
        h.audio("f1").failWith = 500
        h.audio("f2").failWith = 500
        h.engine.runQueue()
        h.audio("f1").failWith = null
        h.audio("f2").failWith = null
        h.queue.retryAllFailed()
        assertEquals(
            mapOf("f1" to DownloadState.QUEUED, "f2" to DownloadState.QUEUED, "ok" to DownloadState.DONE),
            h.allTasks().associate { it.videoId to it.state },
        )
    }

    @Test fun tasksQueuedDuringARunArePickedUp() = runBlocking {
        h.queue.download(listOf(h.song("first", size = 300_000)))
        h.audio("first").writeDelayMs = 20
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        h.awaitState(h.task("first").id, { it.state == DownloadState.RUNNING })
        h.scheduled.clear()
        h.queue.download(listOf(h.song("late")))
        // The running loop takes it: no new worker scheduled.
        assertTrue(h.scheduled.isEmpty())
        val report = withTimeout(10_000) { run.await() }
        assertEquals(setOf("first", "late"), report.done.map { it.videoId }.toSet())
        assertEquals(DownloadEngine.Phase.EXITING, h.engine.phase)
        // After the loop decided to stop, new work is appended behind the finishing worker.
        h.queue.download(listOf(h.song("after")))
        assertEquals(listOf(SchedulePolicy.APPEND), h.scheduled)
    }

    @Test fun aReplacedWorkerEndingLateDoesNotIdleTheNewRun() = runBlocking {
        h.queue.download(listOf(h.song("new", size = 300_000)))
        h.audio("new").writeDelayMs = 20
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        h.awaitState(h.task("new").id, { it.state == DownloadState.RUNNING })
        // The worker replaced by REPLACE ("only on Wi-Fi" changed) runs its finally after the new run started.
        h.engine.idle()
        assertEquals(DownloadEngine.Phase.RUNNING, h.engine.phase)
        h.scheduled.clear()
        h.queue.download(listOf(h.song("more")))
        // Still picked up by the running loop: no worker scheduled on top of it.
        assertTrue(h.scheduled.isEmpty())
        val report = withTimeout(10_000) { run.await() }
        assertEquals(setOf("new", "more"), report.done.map { it.videoId }.toSet())
        h.engine.idle()
        assertEquals(DownloadEngine.Phase.IDLE, h.engine.phase)
    }

    @Test fun respectsParallelDownloads() = runBlocking {
        h.settings = Settings(parallelDownloads = 1)
        h.queue.download(listOf(h.song("one"), h.song("two")))
        h.audio("one").writeDelayMs = 20
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        val one = h.task("one").id
        h.awaitState(one, { it.state == DownloadState.RUNNING })
        assertEquals(DownloadState.QUEUED, h.task("two").state)
        withTimeout(10_000) { run.await() }
        assertTrue(h.allTasks().all { it.state == DownloadState.DONE })
    }

    @Test fun snapshotDescribesTheRun() = runBlocking {
        h.queue.download(listOf(h.song("s1", size = 400_000), h.song("s2"), h.song("s3")))
        h.settings = Settings(parallelDownloads = 1)
        h.audio("s1").writeDelayMs = 20
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        h.awaitState(h.task("s1").id, { it.bytesDone > 0 })
        val snap = h.engine.snapshot()
        assertEquals(3, snap.total)
        assertEquals(1, snap.position)
        assertEquals(listOf("Song s1"), snap.running.map { it.title })
        assertEquals(2, snap.queued)
        assertTrue(snap.hasWork)
        withTimeout(10_000) { run.await() }
        val end = h.engine.snapshot()
        assertEquals(3, end.finished)
        assertFalse(end.hasWork)
    }

    @Test fun cancellingTheRunItselfIsACancellation() = runBlocking {
        h.queue.download(listOf(h.song("w", size = 400_000)))
        h.audio("w").writeDelayMs = 30
        val run = async(Dispatchers.IO) { h.engine.runQueue() }
        h.awaitState(h.task("w").id, { it.bytesDone > 0 })
        run.cancel()
        try {
            run.await()
            fail("cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        // Not the user's cancel: the song waits in the queue with its partial file.
        assertEquals(DownloadState.QUEUED, h.task("w").state)
        assertTrue(part("w").length() > 0)
    }

    @Test fun summaryFlowCounts() {
        val now = 0L
        fun t(state: DownloadState, progress: Float = 0f) =
            com.villamediana.surco.data.db.DownloadTask(videoId = "v", url = "u", title = "t", artist = "a", state = state, progress = progress, createdAt = now)
        val s = summarize(
            listOf(
                t(DownloadState.QUEUED, 0f),
                t(DownloadState.RUNNING, 0.5f),
                t(DownloadState.RUNNING, 1f),
                t(DownloadState.FAILED),
                t(DownloadState.DONE),
                t(DownloadState.CANCELED),
            ),
        )
        assertEquals(DownloadSummary(queued = 1, running = 2, failed = 1, done = 1, progress = 0.5f), s)
        assertEquals(3, s.active)
        assertNull(summarize(listOf(t(DownloadState.DONE))).progress)
    }

    @Test fun letterboxedThumbnailBecomesACleanSquare() = runBlocking {
        // 480x360 hqdefault-style thumbnail: 16:9 picture between black bars.
        h.server.put("/img/thumb_lb", png(480, 360, Color.BLACK, Rect(0, 45, 480, 315) to Color.GREEN))
        h.queue.download(listOf(h.song("lb")))
        h.engine.runQueue()
        val cover = BitmapFactory.decodeFile(h.library.trackByVideoId("lb")!!.coverPath)
        assertEquals(270, cover.width)
        assertEquals(270, cover.height)
        assertTrue(cover.getPixel(2, 2).isGreenish())
        assertTrue(cover.getPixel(267, 267).isGreenish())
    }
}
