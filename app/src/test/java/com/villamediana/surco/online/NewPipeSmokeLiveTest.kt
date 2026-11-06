package com.villamediana.surco.online

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

/** Live: proves NewPipe can search YouTube Music and fetch audio bytes from this machine. SURCO_LIVE=1 */
class NewPipeSmokeLiveTest {
    @Test
    fun searchAndFetchAudio() {
        assumeTrue(System.getProperty("surco.live") == "1")
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale("pt", "BR"))
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery("Soda Stereo De Musica Ligera", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val search = yt.getSearchExtractor(handler).also { it.fetchPage() }
        val items = search.initialPage.items.filterIsInstance<StreamInfoItem>()
        println("songs: " + items.take(5).joinToString { "${it.name} — ${it.uploaderName} (${it.duration}s) ${it.url}" })
        val first = items.first()
        val info = StreamInfo.getInfo(yt, first.url)
        val audio = info.audioStreams
        println("audio streams: " + audio.joinToString { "${it.format?.mimeType} ${it.averageBitrate}kbps itag=${it.itag} len=${it.itagItem?.contentLength}" })
        val best = audio.maxBy { it.averageBitrate }
        val req = Request.Builder().url(best.content).header("Range", "bytes=0-262143").header("User-Agent", NewPipeHttp.USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            val bytes = r.body.bytes()
            println("GET range → ${r.code}, ${bytes.size} bytes")
            check(r.code == 206 || r.code == 200) { "HTTP ${r.code}" }
            check(bytes.size > 100_000)
        }
    }
}
