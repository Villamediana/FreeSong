package com.villamediana.sound.online

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    private val art = "https://lh3.googleusercontent.com/abc=w720-h720-l90-rj"

    @Test fun aRowCoverAsksForASmallPicture() {
        assertEquals("https://lh3.googleusercontent.com/abc=w240-h240-l90-rj", Thumbnails.sized(art, 156))
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", Thumbnails.sized(art, 500))
        // The player's big cover keeps the full art; never bigger than asked.
        assertEquals(art, Thumbnails.sized(art, 990))
        assertEquals(art, Thumbnails.sized(art, 0))
        assertEquals("https://yt3.ggpht.com/x=s120", Thumbnails.sized("https://yt3.ggpht.com/x=s544", 100))
    }

    @Test fun videoFramesStepDownForSmallSpots() {
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", Thumbnails.sized("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", 200))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", Thumbnails.sized("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", 600))
        // Signed crops and other hosts stay as they are.
        val signed = "https://i.ytimg.com/vi/abcdefghijk/hq720.jpg?sqp=abc"
        assertEquals(signed, Thumbnails.sized(signed, 100))
        assertEquals("https://i.scdn.co/image/x", Thumbnails.sized("https://i.scdn.co/image/x", 100))
    }
}
