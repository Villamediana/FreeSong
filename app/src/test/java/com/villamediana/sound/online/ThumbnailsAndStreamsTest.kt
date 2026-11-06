package com.villamediana.sound.online

import com.villamediana.sound.data.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.AudioStream as NpAudioStream

class ThumbnailsAndStreamsTest {

    // ---------------------------------------------------------------- thumbnails

    @Test fun googleImagesAreResized() {
        assertEquals(
            "https://yt3.googleusercontent.com/psHs2R3z=w544-h544-l90-rj",
            Thumbnails.upgrade("https://yt3.googleusercontent.com/psHs2R3z=w120-h120-l90-rj", 544),
        )
        assertEquals("https://yt3.ggpht.com/LjaP2N=s1080", Thumbnails.upgrade("https://yt3.ggpht.com/LjaP2N=s192", 1080))
        assertEquals(
            "https://yt3.googleusercontent.com/a_NN=s1080-c-k-c0x00ffffff-no-rj",
            Thumbnails.upgrade("https://yt3.googleusercontent.com/a_NN=s160-c-k-c0x00ffffff-no-rj", 1080),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/v0SO=w1080-h1080-p-l90-rj",
            Thumbnails.upgrade("https://lh3.googleusercontent.com/v0SO=w120-h120-p-l90-rj", 1080),
        )
    }

    @Test fun videoFramesLoseTheirSignedResize() {
        assertEquals(
            "https://i.ytimg.com/vi/T_FkEw27XJ0/hq720.jpg",
            Thumbnails.upgrade("https://i.ytimg.com/vi/T_FkEw27XJ0/hq720.jpg?sqp=-oaymwEcCNAFEJQDSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CL", 720),
        )
        assertEquals("https://i.ytimg.com/vi/sWft4cMiwXQ/maxresdefault.jpg", Thumbnails.upgrade("https://i.ytimg.com/vi/sWft4cMiwXQ/maxresdefault.jpg", 720))
        assertEquals("https://i.ytimg.com/vi/sWft4cMiwXQ/maxresdefault.jpg", Thumbnails.upgrade("https://i.ytimg.com/vi_webp/sWft4cMiwXQ/maxresdefault.webp", 720))
        // Signed crops are 16:9 without bars → kept; the unsigned hqdefault has black bars → mqdefault.
        val signed = "https://i.ytimg.com/vi/KbVxUOk1YL8/hqdefault.jpg?sqp=-oaymwEcCNACELwBSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4"
        assertEquals(signed, Thumbnails.upgrade(signed, 720))
        assertEquals("https://i.ytimg.com/vi/KbVxUOk1YL8/mqdefault.jpg", Thumbnails.upgrade("https://i.ytimg.com/vi/KbVxUOk1YL8/hqdefault.jpg", 720))
        // Playlist covers from i9.ytimg are left alone.
        val cover = "https://i9.ytimg.com/s_p/OLAK5uy_lq/maxresdefault.jpg?sqp=CND"
        assertEquals(cover, Thumbnails.upgrade(cover, 1080))
    }

    @Test fun bestPicksTheLargest() {
        val thumbs = listOf(
            Thumbnails.Thumb("https://i.ytimg.com/vi/sWft4cMiwXQ/hqdefault.jpg?sqp=a", 336, 188),
            Thumbnails.Thumb("https://i.ytimg.com/vi/sWft4cMiwXQ/maxresdefault.jpg", 1920, 1080),
            Thumbnails.Thumb("https://i.ytimg.com/vi/sWft4cMiwXQ/hqdefault.jpg?sqp=b", 168, 94),
        )
        assertEquals("https://i.ytimg.com/vi/sWft4cMiwXQ/maxresdefault.jpg", Thumbnails.best(thumbs, 720))
        assertNull(Thumbnails.best(emptyList(), 720))
        assertEquals(
            "https://yt3.googleusercontent.com/x=w720-h720-l90-rj",
            Thumbnails.best(listOf(Thumbnails.Thumb("https://yt3.googleusercontent.com/x=w60-h60-l90-rj", 60, 60), Thumbnails.Thumb("https://yt3.googleusercontent.com/x=w120-h120-l90-rj", 120, 120)), 720),
        )
    }

    // ---------------------------------------------------------------- audio streams

    private fun stream(itag: Int, format: MediaFormat, kbps: Int, track: AudioTrackType? = null, delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP): NpAudioStream {
        val item = ItagItem.getItag(itag).also { it.contentLength = 1_000_000L + itag; it.codec = if (format == MediaFormat.M4A) "mp4a.40.2" else "opus" }
        return NpAudioStream.Builder()
            .setId("$itag-${track ?: "orig"}")
            .setContent("https://rr1---sn.googlevideo.com/videoplayback?itag=$itag&track=${track ?: ""}", true)
            .setMediaFormat(format)
            .setDeliveryMethod(delivery)
            .setAverageBitrate(kbps)
            .setItagItem(item)
            .apply { if (track != null) setAudioTrackType(track) }
            .build()
    }

    private val youtubeSet = listOf(
        stream(139, MediaFormat.M4A, 48),
        stream(140, MediaFormat.M4A, 128),
        stream(249, MediaFormat.WEBMA_OPUS, 50),
        stream(250, MediaFormat.WEBMA_OPUS, 70),
        stream(251, MediaFormat.WEBMA_OPUS, 160),
    )

    @Test fun qualityChoices() {
        val best = AudioStreams.pick(youtubeSet, AudioQuality.BEST)!!
        assertEquals("audio/webm", best.mimeType)
        assertEquals("webm", best.extension)
        assertEquals(true, best.url.contains("itag=251"))
        assertEquals(1_000_251L, best.contentLength)

        val compatible = AudioStreams.pick(youtubeSet, AudioQuality.COMPATIBLE)!!
        assertEquals("audio/mp4", compatible.mimeType)
        assertEquals("m4a", compatible.extension)
        assertEquals(true, compatible.url.contains("itag=140"))
        assertEquals(128_000, compatible.bitrate)
        assertEquals("mp4a.40.2", compatible.codec)

        val saver = AudioStreams.pick(youtubeSet, AudioQuality.SAVER)!!
        assertEquals(true, saver.url.contains("itag=139"))
    }

    @Test fun skipsDubbedTracksAndNonProgressive() {
        val set = youtubeSet + stream(251, MediaFormat.WEBMA_OPUS, 170, AudioTrackType.DUBBED) +
            stream(141, MediaFormat.M4A, 256, delivery = DeliveryMethod.DASH)
        val best = AudioStreams.pick(set, AudioQuality.BEST)!!
        assertEquals(true, best.url.contains("itag=251&track="))
        assertEquals(160_000, best.bitrate)
        assertNull(AudioStreams.pick(emptyList(), AudioQuality.BEST))
    }

    @Test fun compatibleFallsBackWhenThereIsNoM4a() {
        val opusOnly = youtubeSet.filter { it.format != MediaFormat.M4A }
        assertEquals("webm", AudioStreams.pick(opusOnly, AudioQuality.COMPATIBLE)!!.extension)
    }

    // ---------------------------------------------------------------- auto-generated descriptions

    @Test fun albumFromProvidedToYouTube() {
        val d = """
            Provided to YouTube by Sony Music Entertainment

            De Música Ligera (Remasterizado 2007) · Soda Stereo

            Canción Animal

            ℗ 1990 Sony Music Entertainment Argentina S.A.

            Released on: 1990-08-07

            Auto-generated by YouTube.
        """.trimIndent()
        assertEquals("Canción Animal", AutoGeneratedDescription.album(d))
        // As NewPipe delivers it: HTML.
        val html = "Provided to YouTube by Columbia<br><br>De Música Ligera (Remasterizado 2007) · Soda Stereo<br><br>" +
            "Canción Animal<br><br>℗ 1990 Sony Music Entertainment (Argentina) S.A.<br><br>Released on: 1990-10-09<br><br>Auto-generated by YouTube."
        assertEquals("Canción Animal", AutoGeneratedDescription.album(html))
        assertEquals("Rock & Roll \"x\" é", AutoGeneratedDescription.htmlToText("Rock &amp; Roll &quot;x&quot; &#233;"))
        assertNull(AutoGeneratedDescription.album("Official video of the song. Subscribe!"))
        assertNull(AutoGeneratedDescription.album(null))
    }

    @Test fun albumPrefixAndYear() {
        assertEquals("Sueño Stereo", NewPipeYouTubeSource.stripAlbumPrefix("Album – Sueño Stereo"))
        assertEquals("Sueño Stereo", NewPipeYouTubeSource.stripAlbumPrefix("Álbum - Sueño Stereo"))
        assertEquals("Albuquerque", NewPipeYouTubeSource.stripAlbumPrefix("Albuquerque"))
        assertEquals(1995, NewPipeYouTubeSource.yearIn("Álbum • Soda Stereo • 1995"))
    }
}
