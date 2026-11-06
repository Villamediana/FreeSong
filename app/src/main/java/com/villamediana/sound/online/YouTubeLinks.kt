package com.villamediana.sound.online

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a YouTube / YouTube Music link points to. Pure parsing, no network. */
sealed interface YouTubeLink {
    data class Video(val videoId: String, val music: Boolean) : YouTubeLink
    data class Playlist(val playlistId: String, val kind: CollectionKind) : YouTubeLink
    /** music.youtube.com/browse/MPREb_… — an album page that still needs to be mapped to its playlist. */
    data class AlbumBrowse(val browseId: String) : YouTubeLink
    data class Channel(val channelId: String) : YouTubeLink
    /** "@handle", "c/name" or "user/name": needs a network lookup to become a channel id. */
    data class ChannelPath(val path: String) : YouTubeLink
}

object YouTubeLinks {
    private val urlInText = Regex(
        "(?i)\\b((?:https?://)?(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)(?:/[^\\s<>\"']*)?)",
    )
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val listId = Regex("^[A-Za-z0-9_-]{10,}$")
    private val channelId = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val trackingParams = setOf("si", "feature", "pp", "ab_channel", "app", "utm_source", "utm_medium", "utm_campaign", "embeds_referring_euri", "source_ve_path")

    /** First YouTube link inside arbitrary shared text ("Ouça … no YouTube Music: https://…"), or null. */
    fun extractUrl(text: String): String? {
        val raw = urlInText.find(text)?.value ?: return null
        val trimmed = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '»', '”', '’', '"', '\'')
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
    }

    /** Drops tracking parameters (si, feature, pp…) and fragments; keeps v/list and the path. */
    fun normalize(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: return url.trim()
        val b = parsed.newBuilder().fragment(null)
        for (name in parsed.queryParameterNames) {
            if (name.lowercase() in trackingParams) b.removeAllQueryParameters(name)
        }
        if (parsed.scheme == "http") b.scheme("https")
        return b.build().toString()
    }

    /** Understands every common YouTube / YouTube Music link shape. Null when it isn't one. */
    fun parse(text: String): YouTubeLink? {
        val url = extractUrl(text)?.let(::normalize) ?: return null
        val u = url.toHttpUrlOrNull() ?: return null
        val host = u.host.lowercase()
        val music = host.startsWith("music.")
        val segs = u.pathSegments.filter { it.isNotEmpty() }

        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            val id = segs.firstOrNull()?.takeIf { videoId.matches(it) } ?: return null
            return YouTubeLink.Video(id, music = false)
        }
        if (!(host == "youtube.com" || host.endsWith(".youtube.com") || host.endsWith("youtube-nocookie.com"))) return null

        val first = segs.firstOrNull()
        return when {
            first == null -> null
            first == "watch" || first == "watch_popup" -> {
                val v = u.queryParameter("v")?.takeIf { videoId.matches(it) }
                when {
                    v != null -> YouTubeLink.Video(v, music)
                    else -> playlistFrom(u)
                }
            }
            first in setOf("shorts", "live", "embed", "v", "e") -> {
                segs.getOrNull(1)?.takeIf { videoId.matches(it) }?.let { YouTubeLink.Video(it, music) }
            }
            first == "playlist" -> playlistFrom(u)
            first == "browse" -> {
                val id = segs.getOrNull(1) ?: return null
                when {
                    id.startsWith("MPREb_") -> YouTubeLink.AlbumBrowse(id)
                    id.startsWith("VL") && listId.matches(id.drop(2)) -> playlistLink(id.drop(2))
                    channelId.matches(id) -> YouTubeLink.Channel(id)
                    else -> null
                }
            }
            first == "channel" -> segs.getOrNull(1)?.takeIf { channelId.matches(it) }?.let { YouTubeLink.Channel(it) }
            first.startsWith("@") && first.length > 1 -> YouTubeLink.ChannelPath(first)
            first == "c" || first == "user" -> segs.getOrNull(1)?.let { YouTubeLink.ChannelPath("$first/$it") }
            else -> null
        }
    }

    /** OLAK5uy_… are album playlists; RD… other than curated RDCLAK… are endless radios (unsupported). */
    fun playlistKind(id: String): CollectionKind? = when {
        id.startsWith("OLAK5uy_") -> CollectionKind.ALBUM
        id.startsWith("RDCLAK") -> CollectionKind.PLAYLIST
        id.startsWith("RD") -> null
        id == "WL" || id == "LL" || id.startsWith("LM") -> null
        else -> CollectionKind.PLAYLIST
    }

    fun playlistUrl(id: String): String = "https://music.youtube.com/playlist?list=$id"
    fun channelUrl(id: String): String = "https://music.youtube.com/channel/$id"
    fun albumBrowseUrl(browseId: String): String = "https://music.youtube.com/browse/$browseId"
    fun watchUrl(videoId: String, music: Boolean): String =
        if (music) "https://music.youtube.com/watch?v=$videoId" else "https://www.youtube.com/watch?v=$videoId"

    /** "https://music.youtube.com/watch?v=abc" / "https://youtu.be/abc" → "abc". */
    fun videoIdOf(url: String): String? = (parse(url) as? YouTubeLink.Video)?.videoId

    private fun playlistFrom(u: HttpUrl): YouTubeLink? {
        val id = u.queryParameter("list")?.takeIf { listId.matches(it) } ?: return null
        return playlistLink(id)
    }

    private fun playlistLink(id: String): YouTubeLink? = playlistKind(id)?.let { YouTubeLink.Playlist(id, it) }
}
