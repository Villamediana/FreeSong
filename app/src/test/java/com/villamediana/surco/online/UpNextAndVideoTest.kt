package com.villamediana.surco.online

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Up next" parsing (YouTube Music radio) and the music-video choice. Offline, from real (trimmed) answers. */
class UpNextAndVideoTest {

    private fun json(s: String) = OnlineJson.parseToJsonElement(s).jsonObject

    /** music.youtube.com `next` for sWft4cMiwXQ (De Música Ligera), RDAMVM radio, first rows; one wrapped, one unplayable. */
    private val next = """
        {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
        {"tabRenderer":{"title":"A seguir","content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[
        {"playlistPanelVideoRenderer": {"title": {"runs": [{"text": "De Música Ligera (Remasterizado 2007)"}]}, "longBylineText": {"runs": [{"text": "Soda Stereo", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCQJuTjhtb6Oyy2kStA1BUKw"}}}, {"text": " • "}, {"text": "Canción Animal", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_ZMGnFMwKuo8"}}}, {"text": " • "}, {"text": "1990"}]}, "thumbnail": {"thumbnails": [{"url": "https://yt3.googleusercontent.com/fqzemW0CAQ7WkgOlJfUkW-WT7eWe1SFjoOnSAkqty0VGV-zQF_6CRxhvNJ0xCg1jRJPayvSrKI7A5R8=w302-h302-l90-rj", "width": 302, "height": 302}, {"url": "https://yt3.googleusercontent.com/fqzemW0CAQ7WkgOlJfUkW-WT7eWe1SFjoOnSAkqty0VGV-zQF_6CRxhvNJ0xCg1jRJPayvSrKI7A5R8=w544-h544-l90-rj", "width": 544, "height": 544}]}, "lengthText": {"runs": [{"text": "3:33"}]}, "videoId": "sWft4cMiwXQ", "navigationEndpoint": {"watchEndpoint": {"videoId": "sWft4cMiwXQ", "playlistId": "RDAMVMsWft4cMiwXQ", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_ATV"}}}}}},
        {"playlistPanelVideoRenderer": {"title": {"runs": [{"text": "Prófugos (Remasterizado 2007)"}]}, "longBylineText": {"runs": [{"text": "Soda Stereo", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCQJuTjhtb6Oyy2kStA1BUKw"}}}, {"text": " • "}, {"text": "Signos", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_vT1f0GE91Sa"}}}, {"text": " • "}, {"text": "1986"}]}, "thumbnail": {"thumbnails": [{"url": "https://yt3.googleusercontent.com/bIuke1myMJphJsefXLUscinjSYlVVEJK2F_kGocPnvy9wKR1_0_YOUCdnMGXkYppa092_U4NLa7zfAM=w544-h544-l90-rj", "width": 544, "height": 544}]}, "lengthText": {"runs": [{"text": "5:18"}]}, "videoId": "9WT4bMTjQqY", "navigationEndpoint": {"watchEndpoint": {"videoId": "9WT4bMTjQqY", "playlistId": "RDAMVMsWft4cMiwXQ"}}}},
        {"playlistPanelVideoWrapperRenderer": {"primaryRenderer": {"playlistPanelVideoRenderer": {"title": {"runs": [{"text": "Rayando el Sol (2020 Remasterizado)"}]}, "longBylineText": {"runs": [{"text": "Maná", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCtSeeqnGJM5ngqJWZ1vQygw"}}}, {"text": " • "}, {"text": "Falta Amor (2020 Remasterizado)", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_DQn8CB7Bb2s"}}}, {"text": " • "}, {"text": "2020"}]}, "thumbnail": {"thumbnails": [{"url": "https://yt3.googleusercontent.com/9rHd6t5gin7Z2uqI9sktI3nUseeT1vAK-9Xci47ZWZXShzCAUzx1IS49ny7qKNr2WLZCDCgJYJpjp_4=w544-h544-l90-rj", "width": 544, "height": 544}]}, "lengthText": {"runs": [{"text": "4:15"}]}, "videoId": "whMPhP6P7vE", "navigationEndpoint": {"watchEndpoint": {"videoId": "whMPhP6P7vE"}}}}}},
        {"playlistPanelVideoRenderer": {"title": {"runs": [{"text": "Lamento Boliviano (Video Oficial)"}]}, "longBylineText": {"runs": [{"text": "Los Enanitos Verdes"}, {"text": " • "}, {"text": "12 mi de visualizações"}]}, "lengthText": {"runs": [{"text": "3:43"}]}, "videoId": "BRuHGchMrD8", "navigationEndpoint": {"watchEndpoint": {"videoId": "BRuHGchMrD8", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_UGC"}}}}}},
        {"playlistPanelVideoRenderer": {"title": {"runs": [{"text": "Gone"}]}, "unplayableText": {"runs": [{"text": "Indisponível"}]}, "videoId": "goneGoneGon"}},
        {"playlistPanelVideoRenderer": {"title": {"runs": [{"text": "Prófugos (Remasterizado 2007)"}]}, "videoId": "9WT4bMTjQqY"}}
        ]}}}}}}]}}}}}
    """.trimIndent()

    @Test fun watchNextRows() {
        val rows = YouTubeMusicParser.watchNext(json(next))
        assertEquals(listOf("sWft4cMiwXQ", "9WT4bMTjQqY", "whMPhP6P7vE", "BRuHGchMrD8"), rows.map { it.videoId })
        val first = rows[0]
        assertEquals("De Música Ligera (Remasterizado 2007)", first.title)
        assertEquals("Soda Stereo", first.artist)
        assertEquals("Canción Animal", first.album)
        assertEquals(213_000L, first.durationMs)
        assertEquals("https://music.youtube.com/watch?v=sWft4cMiwXQ", first.url)
        assertTrue(first.thumbnailUrl!!.startsWith("https://yt3.googleusercontent.com/fqzemW0"))
        assertTrue(first.fromMusicCatalog)
        // Wrapped row (audio/video switch) is unwrapped.
        assertEquals("Maná", rows[2].artist)
        assertEquals("Falta Amor (2020 Remasterizado)", rows[2].album)
        // A user video: artist from the first group, no album, title not trusted as clean.
        assertEquals("Los Enanitos Verdes", rows[3].artist)
        assertNull(rows[3].album)
        assertEquals(false, rows[3].fromMusicCatalog)
        assertEquals(223_000L, rows[3].durationMs)
    }

    @Test fun watchNextWithoutAPanel() {
        assertEquals(emptyList<RemoteTrack>(), YouTubeMusicParser.watchNext(json("""{"contents":{}}""")))
    }

    // ---------------------------------------------------------------- video choice

    private fun v(h: Int, codec: String?, mime: String = VideoStreams.MP4, fps: Int = 30, bitrate: Int = h * 1000) =
        VideoStreams.Video("https://v/$h-$codec-$fps", h * 16 / 9, h, fps, codec, mime, bitrate)

    private val audio = AudioStream("https://a/140", "audio/mp4", "m4a", 128_000, 3_000_000, "mp4a.40.2")

    @Test fun picksTheBestH264WithinTheCapPlusAudio() {
        val videoOnly = listOf(
            v(1080, "avc1.640028"), v(720, "avc1.4d401f"), v(720, "avc1.4d4020", fps = 60, bitrate = 2_000_000),
            v(480, "avc1.4d401e"), v(1080, "vp9", "video/webm"), v(720, "av01.0.05M.08"),
        )
        val muxed = listOf(v(360, "avc1.42001E"))
        val s = VideoStreams.pick(videoOnly, muxed, audio, 720)!!
        assertEquals("https://v/720-avc1.4d401f-30", s.videoUrl) // 30 fps beats 60 at the same height
        assertEquals("https://a/140", s.audioUrl)
        assertEquals(720, s.height)
        assertEquals("audio/mp4", s.audioMimeType)

        assertEquals("https://v/480-avc1.4d401e-30", VideoStreams.pick(videoOnly, muxed, audio, 600)!!.videoUrl)
    }

    @Test fun fallsBackToMuxedThenOtherCodecsThenAnything() {
        val muxed = listOf(v(360, "avc1.42001E"), v(144, "mp4v.20.3", "video/3gpp"))
        // No H.264 video-only within the cap → the muxed 360p (it has its own audio).
        val m = VideoStreams.pick(listOf(v(1080, "avc1.640028"), v(720, "vp9", "video/webm")), muxed, audio, 720)!!
        assertEquals("https://v/360-avc1.42001E-30", m.videoUrl)
        assertNull(m.audioUrl)
        // Nothing muxed: VP9 within the cap + audio.
        assertEquals("https://v/720-vp9-30", VideoStreams.pick(listOf(v(1080, "avc1.640028"), v(720, "vp9", "video/webm")), emptyList(), audio, 720)!!.videoUrl)
        // Only big H.264: the smallest of them.
        assertEquals("https://v/1080-avc1.640028-30", VideoStreams.pick(listOf(v(1440, "avc1.640032"), v(1080, "avc1.640028")), emptyList(), audio, 720)!!.videoUrl)
        // No audio stream: only muxed can play.
        assertEquals("https://v/360-avc1.42001E-30", VideoStreams.pick(listOf(v(720, "avc1.4d401f")), muxed, null, 720)!!.videoUrl)
        assertNull(VideoStreams.pick(listOf(v(720, "avc1.4d401f")), emptyList(), null, 720))
        assertNull(VideoStreams.pick(emptyList(), emptyList(), audio, 720))
    }

    @Test fun verticalVideosAreCappedByTheirShortSide() {
        val tall = VideoStreams.Video("https://v/tall", 720, 1280, 30, "avc1.4d401f", VideoStreams.MP4, 1_500_000)
        val taller = VideoStreams.Video("https://v/taller", 1080, 1920, 30, "avc1.640028", VideoStreams.MP4, 3_000_000)
        assertEquals("https://v/tall", VideoStreams.pick(listOf(tall, taller), emptyList(), audio, 720)!!.videoUrl)
    }

    @Test fun heightFromResolutionLabels() {
        assertEquals(720, VideoStreams.heightOf("720p60"))
        assertEquals(1080, VideoStreams.heightOf("1080p"))
        assertEquals(0, VideoStreams.heightOf(null))
        assertEquals(0, VideoStreams.heightOf("unknown"))
    }

    // ---------------------------------------------------------------- official music video

    private fun c(id: String, title: String, uploader: String, seconds: Int) = MusicVideoMatch.Candidate(id, title, uploader, seconds * 1000L)

    @Test fun findsTheOfficialVideoOfAnArtTrack() {
        val results = listOf(
            c("art", "De Música Ligera (Remasterizado 2007)", "Soda Stereo - Topic", 213),
            c("lyric", "Soda Stereo - De Música Ligera (Letra)", "Letras Rock", 214),
            c("live", "Soda Stereo - De Música Ligera (En Vivo)", "SodaStereoVEVO", 290),
            c("cover", "De Música Ligera - Cover acústico", "Guitarrista X", 200),
            c("mv", "Soda Stereo - De Música Ligera (Official Video)", "SodaStereoVEVO", 228),
            c("other", "Soda Stereo - Persiana Americana (Official Video)", "SodaStereoVEVO", 290),
        )
        val best = MusicVideoMatch.best("De Música Ligera (Remasterizado 2007)", "Soda Stereo", 213_000, results, exclude = "art")
        assertEquals("mv", best?.videoId)
    }

    @Test fun noMatchWhenNothingIsTheSameSong() {
        val results = listOf(
            c("other", "Soda Stereo - Persiana Americana (Official Video)", "SodaStereoVEVO", 290),
            c("long", "Soda Stereo - De Música Ligera (Official Video) [1 hour loop]", "Loops", 3600),
            c("otherArtist", "De Música Ligera (Official Video)", "Some Band", 213),
        )
        assertNull(MusicVideoMatch.best("De Música Ligera", "Soda Stereo", 213_000, results))
        assertNull(MusicVideoMatch.best("De Música Ligera", "Soda Stereo", 213_000, emptyList()))
    }

    @Test fun liveSongsMatchLiveVideos() {
        val results = listOf(
            c("studio", "Soda Stereo - De Música Ligera (Official Video)", "SodaStereoVEVO", 228),
            c("live", "Soda Stereo - De Música Ligera (En Vivo)", "SodaStereoVEVO", 300),
        )
        assertEquals("live", MusicVideoMatch.best("De Música Ligera (En Vivo)", "Soda Stereo", 291_000, results)?.videoId)
    }
}
