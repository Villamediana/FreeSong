package com.villamediana.sound.online

/**
 * FreeSong's own links, the ones the app shares: `https://freesong.simplyonegoal.com/m/<video>`
 * (a song), `/v/<video>` (a video that isn't from the music catalog), `/l/<playlist>` and
 * `/a/<album>`. On a phone with FreeSong they open in the app (verified App Links: the server
 * answers `/.well-known/assetlinks.json` with the app's signing key); anywhere else they open a
 * small page that offers the app and the same song on YouTube Music. Each stands for exactly one
 * YouTube link, so nothing is looked up to open it. Pure.
 */
object FreeSongLinks {
    const val HOST = "freesong.simplyonegoal.com"

    private val link = Regex("""(?i)(?:https?://)?freesong\.simplyonegoal\.com/([mvla])/([A-Za-z0-9_-]{10,80})[^\s<>"'`]*""")

    /** The FreeSong link for a YouTube [url] the app can open; any other url (a Spotify playlist, a channel) as it is. */
    fun of(url: String): String = when (val parsed = YouTubeLinks.parse(url)) {
        is YouTubeLink.Video -> "https://$HOST/${if (parsed.music) "m" else "v"}/${parsed.videoId}"
        is YouTubeLink.Playlist -> "https://$HOST/l/${parsed.playlistId}"
        is YouTubeLink.AlbumBrowse -> "https://$HOST/a/${parsed.browseId}"
        else -> url
    }

    /** [text] with every FreeSong link in it turned back into the YouTube link it stands for. */
    fun unwrap(text: String): String = link.replace(text) { m ->
        val start = m.range.first
        // Glued to something else ("notfreesong.simplyonegoal.com…"): not ours.
        if (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it in ".-/:@_" }) return@replace m.value
        youTubeUrl(m.groupValues[1].lowercase(), m.groupValues[2]) ?: m.value
    }

    private fun youTubeUrl(kind: String, id: String): String? = when (kind) {
        "m" -> id.takeIf { it.length == 11 }?.let { YouTubeLinks.watchUrl(it, music = true) }
        "v" -> id.takeIf { it.length == 11 }?.let { YouTubeLinks.watchUrl(it, music = false) }
        "l" -> YouTubeLinks.playlistUrl(id)
        "a" -> id.takeIf { it.startsWith("MPREb_") }?.let(YouTubeLinks::albumBrowseUrl)
        else -> null
    }
}
