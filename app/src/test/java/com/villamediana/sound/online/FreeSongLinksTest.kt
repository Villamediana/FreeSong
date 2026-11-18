package com.villamediana.sound.online

import org.junit.Assert.assertEquals
import org.junit.Test

/** The links FreeSong shares: each stands for one YouTube link, there and back. */
class FreeSongLinksTest {
    @Test fun aSharedLinkIsFreeSongsOwn() {
        assertEquals("https://freesong.simplyonegoal.com/m/dQw4w9WgXcQ", FreeSongLinks.of("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc"))
        assertEquals("https://freesong.simplyonegoal.com/v/dQw4w9WgXcQ", FreeSongLinks.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://freesong.simplyonegoal.com/v/dQw4w9WgXcQ", FreeSongLinks.of("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("https://freesong.simplyonegoal.com/l/PLabcdefghij1234567890", FreeSongLinks.of("https://music.youtube.com/playlist?list=PLabcdefghij1234567890"))
        assertEquals("https://freesong.simplyonegoal.com/l/OLAK5uy_abcdefghij1234567890", FreeSongLinks.of("https://music.youtube.com/playlist?list=OLAK5uy_abcdefghij1234567890"))
        assertEquals("https://freesong.simplyonegoal.com/a/MPREb_abcDEF12345", FreeSongLinks.of("https://music.youtube.com/browse/MPREb_abcDEF12345"))
        // What has no page of ours goes as it is: a playlist imported from Spotify, an artist.
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1DX", FreeSongLinks.of("https://open.spotify.com/playlist/37i9dQZF1DX"))
        assertEquals("https://music.youtube.com/channel/UCabcdefghijklmnopqrstuv", FreeSongLinks.of("https://music.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
    }

    @Test fun openedItIsTheYouTubeLinkItStandsFor() {
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", FreeSongLinks.unwrap("https://freesong.simplyonegoal.com/m/dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", FreeSongLinks.unwrap("https://freesong.simplyonegoal.com/v/dQw4w9WgXcQ"))
        assertEquals("https://music.youtube.com/playlist?list=PLabcdefghij1234567890", FreeSongLinks.unwrap("freesong.simplyonegoal.com/l/PLabcdefghij1234567890"))
        assertEquals("https://music.youtube.com/browse/MPREb_abcDEF12345", FreeSongLinks.unwrap("HTTPS://FreeSong.SimplyOneGoal.com/a/MPREb_abcDEF12345"))
        // Inside a message, with whatever a chat app hangs on the link.
        assertEquals(
            "Bohemian Rhapsody — Queen\nhttps://music.youtube.com/watch?v=dQw4w9WgXcQ",
            FreeSongLinks.unwrap("Bohemian Rhapsody — Queen\nhttps://freesong.simplyonegoal.com/m/dQw4w9WgXcQ?utm_source=x"),
        )
        // Every link the app shares comes back to where it started.
        for (url in listOf(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/playlist?list=PLabcdefghij1234567890",
            "https://music.youtube.com/browse/MPREb_abcDEF12345",
        )) assertEquals(url, FreeSongLinks.unwrap(FreeSongLinks.of(url)))
    }

    @Test fun whatIsNotOursStaysAsItIs() {
        for (text in listOf(
            "https://freesong.simplyonegoal.com/v1/health",
            "https://freesong.simplyonegoal.com/m/short",
            "https://freesong.simplyonegoal.com/a/NOTANALBUM12345",
            "https://notfreesong.simplyonegoal.com/m/dQw4w9WgXcQ",
            "https://evil.example/freesong.simplyonegoal.com/m/dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "só um texto",
        )) assertEquals(text, FreeSongLinks.unwrap(text))
    }
}
