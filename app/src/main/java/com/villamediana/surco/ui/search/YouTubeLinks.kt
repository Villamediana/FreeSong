package com.villamediana.surco.ui.search

import java.net.URI
import java.net.URLDecoder

/**
 * Finds a YouTube / YouTube Music link inside free text (clipboard, a shared message, the search
 * field). Only links that point to something Surco can open are accepted: a video or song, a
 * playlist or album, or a channel/artist page. The YouTube home page, feeds, search pages and
 * look-alike domains are not links to music.
 */
object YouTubeLinks {
    private val candidate = Regex(
        """(?i)(?:https?://)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtube-nocookie\.com|youtu\.be)(?::\d+)?/[^\s<>"'`]*""",
    )
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val trailingJunk = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '\'', '"', '»', '”', '’')

    /** The first supported link inside [text], normalized to https, or null. */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (match in candidate.findAll(text)) {
            // A candidate glued to something else ("notyoutube.com", "ftp://youtube.com", "site.com/youtube.com") is not a YouTube link.
            val start = match.range.first
            if (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it in ".-/:@_" }) continue
            val raw = match.value.trimEnd(*trailingJunk)
            val url = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
            if (isSupported(url)) return url
        }
        return null
    }

    /** True when [text] contains a supported link. */
    fun contains(text: String?): Boolean = find(text) != null

    /** True when [url] (already an absolute url) points to a video, playlist/album or channel. */
    fun isSupported(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty()
        val params = queryParams(uri.rawQuery)
        return when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/').matches(videoId)
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
            "youtube-nocookie.com", "www.youtube-nocookie.com",
            -> youtubePath(path, params)
            else -> false
        }
    }

    private fun youtubePath(path: String, params: Map<String, String>): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val first = segments.firstOrNull() ?: return false
        val second = segments.getOrNull(1)
        return when {
            first == "watch" -> params["v"]?.matches(videoId) == true || !params["list"].isNullOrBlank()
            first in setOf("shorts", "embed", "live", "v", "e") -> second?.matches(videoId) == true
            first == "playlist" -> !params["list"].isNullOrBlank()
            first == "browse" -> !second.isNullOrBlank()
            first == "channel" -> !second.isNullOrBlank()
            first == "c" || first == "user" -> !second.isNullOrBlank()
            first.startsWith("@") -> first.length > 1
            else -> false
        }
    }

    private fun queryParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            if (key.isEmpty()) return@mapNotNull null
            val value = part.substringAfter('=', "")
            key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }.toMap()
    }
}
