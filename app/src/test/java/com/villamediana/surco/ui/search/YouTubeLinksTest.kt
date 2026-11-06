package com.villamediana.surco.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test
    fun plainLinks() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&t=42s",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM$id",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=Ab12_cd",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://music.youtube.com/playlist?list=OLAK5uy_kHlA3cOFeYTm7VRJ7tdmJrL0Hhp5pBVzI",
            "https://music.youtube.com/browse/MPREb_4pL8gzRtw1p",
            "https://music.youtube.com/channel/UCRaUw_AHULbGlxnbfn9wkKg",
            "https://www.youtube.com/@sodastereo",
            "https://www.youtube.com/c/SodaStereo",
            "https://www.youtube.com/user/sodastereovevo",
        ).forEach { url -> assertEquals(url, YouTubeLinks.find(url)) }
    }

    @Test
    fun linksInsideSharedText() {
        assertEquals(
            "https://music.youtube.com/watch?v=$id&feature=share",
            YouTubeLinks.find("Escuta essa: https://music.youtube.com/watch?v=$id&feature=share."),
        )
        assertEquals("https://youtu.be/$id", YouTubeLinks.find("(https://youtu.be/$id)"))
        assertEquals("https://youtu.be/$id", YouTubeLinks.find("“https://youtu.be/$id”"))
        assertEquals("https://youtu.be/$id", YouTubeLinks.find("Soda Stereo - De Música Ligera\nhttps://youtu.be/$id\n"))
    }

    @Test
    fun schemeLessLinksGetHttps() {
        assertEquals("https://youtu.be/$id", YouTubeLinks.find("youtu.be/$id"))
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeLinks.find("www.youtube.com/watch?v=$id"))
        assertEquals("https://music.youtube.com/playlist?list=PL123", YouTubeLinks.find("music.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun skipsUnsupportedAndKeepsLookingForTheNextLink() {
        assertEquals(
            "https://youtu.be/$id",
            YouTubeLinks.find("home https://www.youtube.com/ e o vídeo https://youtu.be/$id"),
        )
    }

    @Test
    fun rejectsThingsThatAreNotMusicLinks() {
        listOf(
            null,
            "",
            "   ",
            "soda stereo de musica ligera",
            "https://www.youtube.com/",
            "https://www.youtube.com/feed/trending",
            "https://www.youtube.com/results?search_query=soda",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/playlist",
            "https://youtu.be/",
            "https://notyoutube.com/watch?v=$id",
            "https://youtube.com.evil.io/watch?v=$id",
            "https://gaming.youtube.com/watch?v=$id",
            "https://vimeo.com/123456",
            "ftp://youtube.com/watch?v=$id",
        ).forEach { text -> assertNull("should reject: $text", YouTubeLinks.find(text)) }
    }

    @Test
    fun containsAndIsSupported() {
        assertTrue(YouTubeLinks.contains("olha https://youtu.be/$id"))
        assertFalse(YouTubeLinks.contains("olha isso"))
        assertTrue(YouTubeLinks.isSupported("https://music.youtube.com/browse/MPREb_x"))
        assertFalse(YouTubeLinks.isSupported("https://music.youtube.com/explore"))
        assertFalse(YouTubeLinks.isSupported("not a url"))
    }

    @Test
    fun watchWithOnlyAListIsAPlaylist() {
        assertEquals("https://www.youtube.com/watch?list=PL123", YouTubeLinks.find("https://www.youtube.com/watch?list=PL123"))
    }
}
