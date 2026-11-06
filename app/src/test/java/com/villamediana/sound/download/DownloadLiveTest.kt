package com.villamediana.sound.download

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.Storage
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.online.AudioStream
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.NewPipeHttp
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.online.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Live: downloads one real song end to end (NewPipe → 8 MB ranges → library). SOUND_LIVE=1 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadLiveTest {

    /** Just enough of a YouTubeSource, straight on NewPipe (the online module is built elsewhere). */
    private class NewPipeAudio(private val watchUrl: String) : YouTubeSource {
        override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream = withContext(Dispatchers.IO) {
            val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
            val best = info.audioStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
                .maxBy { it.averageBitrate }
            AudioStream(
                url = best.content,
                mimeType = "audio/mp4",
                extension = "m4a",
                bitrate = best.averageBitrate * 1000,
                contentLength = best.itagItem?.contentLength?.takeIf { it > 0 },
                codec = best.codec,
            )
        }

        override suspend fun search(query: String, filter: SearchFilter): SearchPage = throw UnsupportedOperationException()
        override suspend fun more(page: SearchPage): SearchPage = throw UnsupportedOperationException()
        override suspend fun suggestions(query: String): List<String> = emptyList()
        override suspend fun resolve(text: String): ResolvedLink = ResolvedLink.Unsupported
        override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = throw UnsupportedOperationException()
        override suspend fun track(videoId: String): RemoteTrack = throw UnsupportedOperationException()
    }

    @Test fun downloadsARealSong() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        NewPipeHttp.init(http, Locale("pt", "BR"))

        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery("Soda Stereo De Musica Ligera", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val item = withContext(Dispatchers.IO) {
            yt.getSearchExtractor(handler).also { it.fetchPage() }.initialPage.items.filterIsInstance<StreamInfoItem>().first()
        }
        val videoId = yt.streamLHFactory.getId(item.url)
        println("live: ${item.name} — ${item.uploaderName} ($videoId)")

        val db = SoundDatabase.inMemory(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val library = LibraryRepository(db, scope)
        val storage = Storage(context)
        val engine = DownloadEngine(
            dao = db.downloads(),
            library = library,
            storage = storage,
            settings = { Settings(fetchLyrics = false) },
            youTube = NewPipeAudio(item.url),
            enricher = FakeEnricher(),
            lyrics = FakeLyrics(),
            http = http,
            messages = AndroidDownloadMessages(context),
            accentOf = { null },
        )
        val queue = DownloadQueue(db.downloads(), library, storage, engine, { }, { })
        try {
            queue.download(
                listOf(
                    RemoteTrack(
                        videoId = videoId,
                        url = item.url,
                        title = item.name,
                        artist = item.uploaderName.orEmpty(),
                        durationMs = item.duration.takeIf { it > 0 }?.times(1000),
                        thumbnailUrl = item.thumbnails.maxByOrNull { it.height }?.url,
                        fromMusicCatalog = true,
                    ),
                ),
            )
            val started = System.nanoTime()
            val report = engine.runQueue()
            val seconds = (System.nanoTime() - started) / 1e9
            val task = db.downloads().observeAll().first().single()
            println("live: state=${task.state} error=${task.error} bytes=${task.bytesTotal} in ${"%.1f".format(seconds)} s")
            assertEquals(DownloadState.DONE, task.state)
            assertEquals(1, report.done.size)

            val track = library.trackByVideoId(videoId)!!
            val file = File(track.filePath)
            assertTrue(file.length() > 500_000)
            // An M4A file starts with an ISO-BMFF "ftyp" box.
            assertEquals("ftyp", String(file.readBytes().copyOfRange(4, 8)))
            val cover = BitmapFactory.decodeFile(track.coverPath)
            assertEquals(cover.width, cover.height)
            println("live: ${file.length()} bytes, cover ${cover.width}px, ${"%.0f".format(file.length() / 1024.0 / seconds)} KB/s")
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
