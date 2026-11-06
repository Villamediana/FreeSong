package com.villamediana.surco.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {

    @Test fun extractsTheLinkFromSharedText() {
        assertEquals(
            "https://music.youtube.com/watch?v=sWft4cMiwXQ&si=Xy12AbCdEfGh",
            YouTubeLinks.extractUrl("Ouça De Música Ligera no YouTube Music: https://music.youtube.com/watch?v=sWft4cMiwXQ&si=Xy12AbCdEfGh"),
        )
        assertEquals("https://youtu.be/T_FkEw27XJ0", YouTubeLinks.extractUrl("Mira esto (https://youtu.be/T_FkEw27XJ0)."))
        assertEquals("https://www.youtube.com/@SodaStereo", YouTubeLinks.extractUrl("www.youtube.com/@SodaStereo"))
        assertNull(YouTubeLinks.extractUrl("nenhum link aqui"))
        assertNull(YouTubeLinks.extractUrl("https://example.com/watch?v=T_FkEw27XJ0"))
    }

    @Test fun normalizeDropsTracking() {
        assertEquals(
            "https://music.youtube.com/watch?v=sWft4cMiwXQ",
            YouTubeLinks.normalize("https://music.youtube.com/watch?v=sWft4cMiwXQ&si=abc&feature=share"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=T_FkEw27XJ0&list=PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ",
            YouTubeLinks.normalize("http://www.youtube.com/watch?v=T_FkEw27XJ0&pp=ygUJc29kYQ%3D%3D&list=PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ#t=3"),
        )
    }

    @Test fun videos() {
        assertEquals(YouTubeLink.Video("sWft4cMiwXQ", music = true), YouTubeLinks.parse("Ouça no YouTube Music: https://music.youtube.com/watch?v=sWft4cMiwXQ&si=Xy12"))
        assertEquals(YouTubeLink.Video("T_FkEw27XJ0", music = false), YouTubeLinks.parse("https://youtu.be/T_FkEw27XJ0?si=abc"))
        assertEquals(YouTubeLink.Video("T_FkEw27XJ0", music = false), YouTubeLinks.parse("https://www.youtube.com/watch?v=T_FkEw27XJ0&feature=share"))
        assertEquals(YouTubeLink.Video("T_FkEw27XJ0", music = false), YouTubeLinks.parse("https://m.youtube.com/watch?v=T_FkEw27XJ0&list=RDT_FkEw27XJ0&start_radio=1"))
        assertEquals(YouTubeLink.Video("dQw4w9WgXcQ", music = false), YouTubeLinks.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(YouTubeLink.Video("dQw4w9WgXcQ", music = false), YouTubeLinks.parse("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"))
        assertEquals("sWft4cMiwXQ", YouTubeLinks.videoIdOf("https://music.youtube.com/watch?v=sWft4cMiwXQ"))
        assertNull(YouTubeLinks.parse("https://www.youtube.com/watch?v=short"))
    }

    @Test fun playlistsAndAlbums() {
        assertEquals(
            YouTubeLink.Playlist("PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ", CollectionKind.PLAYLIST),
            YouTubeLinks.parse("https://www.youtube.com/playlist?list=PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ&si=xyz"),
        )
        assertEquals(
            YouTubeLink.Playlist("OLAK5uy_nhHzfeEh8qiJo8EbAoWRfLH6wFcYPSigI", CollectionKind.ALBUM),
            YouTubeLinks.parse("https://music.youtube.com/playlist?list=OLAK5uy_nhHzfeEh8qiJo8EbAoWRfLH6wFcYPSigI&si=abc"),
        )
        assertEquals(
            YouTubeLink.Playlist("RDCLAK5uy_nyD9oEfxn-d5aJD7sgL89sZoTJ5q3jZ4w", CollectionKind.PLAYLIST),
            YouTubeLinks.parse("https://music.youtube.com/playlist?list=RDCLAK5uy_nyD9oEfxn-d5aJD7sgL89sZoTJ5q3jZ4w"),
        )
        assertEquals(
            YouTubeLink.Playlist("PLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ", CollectionKind.PLAYLIST),
            YouTubeLinks.parse("https://music.youtube.com/browse/VLPLgaFNC_I_ZkmtWBxiCtmsjKcGeenO9vKQ"),
        )
        assertEquals(YouTubeLink.AlbumBrowse("MPREb_7LvbQPQlUGp"), YouTubeLinks.parse("https://music.youtube.com/browse/MPREb_7LvbQPQlUGp?si=1"))
        // Endless radios / private lists aren't collections.
        assertNull(YouTubeLinks.parse("https://www.youtube.com/playlist?list=RDMM"))
        assertNull(YouTubeLinks.parse("https://www.youtube.com/playlist?list=RDAMVMsWft4cMiwXQ"))
        assertNull(YouTubeLinks.parse("https://www.youtube.com/playlist?list=WL"))
    }

    @Test fun channelsAndArtists() {
        assertEquals(YouTubeLink.Channel("UCQJuTjhtb6Oyy2kStA1BUKw"), YouTubeLinks.parse("https://www.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw"))
        assertEquals(YouTubeLink.Channel("UCQJuTjhtb6Oyy2kStA1BUKw"), YouTubeLinks.parse("https://music.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw?si=pq"))
        assertEquals(YouTubeLink.Channel("UCQJuTjhtb6Oyy2kStA1BUKw"), YouTubeLinks.parse("https://music.youtube.com/browse/UCQJuTjhtb6Oyy2kStA1BUKw"))
        assertEquals(YouTubeLink.ChannelPath("@SodaStereo"), YouTubeLinks.parse("https://www.youtube.com/@SodaStereo"))
        assertEquals(YouTubeLink.ChannelPath("@SodaStereo"), YouTubeLinks.parse("https://www.youtube.com/@SodaStereo/videos"))
        assertEquals(YouTubeLink.ChannelPath("@SodaStereo"), YouTubeLinks.parse("https://music.youtube.com/@SodaStereo"))
        assertEquals(YouTubeLink.ChannelPath("c/SodaStereo"), YouTubeLinks.parse("youtube.com/c/SodaStereo"))
        assertEquals(YouTubeLink.ChannelPath("user/sodastereo"), YouTubeLinks.parse("https://www.youtube.com/user/sodastereo"))
    }

    @Test fun notYouTube() {
        assertNull(YouTubeLinks.parse("https://open.spotify.com/track/abc"))
        assertNull(YouTubeLinks.parse("https://www.youtube.com/"))
        assertNull(YouTubeLinks.parse("https://www.youtube.com/feed/library"))
    }

    @Test fun canonicalUrls() {
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_x", YouTubeLinks.playlistUrl("OLAK5uy_x"))
        assertEquals("https://music.youtube.com/channel/UCQJuTjhtb6Oyy2kStA1BUKw", YouTubeLinks.channelUrl("UCQJuTjhtb6Oyy2kStA1BUKw"))
        assertEquals("https://www.youtube.com/watch?v=T_FkEw27XJ0", YouTubeLinks.watchUrl("T_FkEw27XJ0", music = false))
        assertEquals("https://music.youtube.com/watch?v=T_FkEw27XJ0", YouTubeLinks.watchUrl("T_FkEw27XJ0", music = true))
    }
}
