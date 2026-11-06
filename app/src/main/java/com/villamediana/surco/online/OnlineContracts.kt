package com.villamediana.surco.online

/*
 * Contracts of the online side (search + metadata). The only part of Surco that needs internet:
 * everything here is used to FIND and DOWNLOAD music; playback never touches the network.
 */

enum class SearchFilter { SONGS, VIDEOS, ALBUMS, PLAYLISTS, ARTISTS }

/** A song/video found online. */
data class RemoteTrack(
    val videoId: String,
    /** Canonical watch url (https://www.youtube.com/watch?v=… or music.youtube.com). */
    val url: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Best thumbnail url (square for YouTube Music songs). */
    val thumbnailUrl: String? = null,
    /** True when it came from the YouTube Music catalog (clean title/artist). */
    val fromMusicCatalog: Boolean = false,
)

enum class CollectionKind { ALBUM, PLAYLIST, ARTIST }

/** An album, playlist or artist found online. */
data class RemoteCollection(
    val kind: CollectionKind,
    val url: String,
    val title: String,
    /** Album artist / playlist owner / null for artists. */
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val itemCount: Long? = null,
    val year: Int? = null,
)

sealed interface SearchItem {
    data class Song(val track: RemoteTrack) : SearchItem
    data class Collection(val collection: RemoteCollection) : SearchItem
}

data class SearchPage(
    val query: String,
    val filter: SearchFilter,
    val items: List<SearchItem>,
    /** Opaque continuation token owned by the [YouTubeSource] implementation; null = no more pages. */
    val cursor: Any? = null,
) {
    val hasMore: Boolean get() = cursor != null
}

/** Full contents of an album/playlist/artist page. */
data class CollectionDetail(
    val collection: RemoteCollection,
    val tracks: List<RemoteTrack>,
    val description: String? = null,
    /** For artists: their albums/singles, when available. */
    val related: List<RemoteCollection> = emptyList(),
)

sealed interface ResolvedLink {
    data class Track(val track: RemoteTrack) : ResolvedLink
    data class Collection(val collection: RemoteCollection) : ResolvedLink
    data object Unsupported : ResolvedLink
}

/** A direct, downloadable audio-only stream. */
data class AudioStream(
    val url: String,
    /** "audio/mp4" (AAC in M4A) or "audio/webm" (Opus). */
    val mimeType: String,
    /** "m4a" or "webm". */
    val extension: String,
    /** Average bitrate in bits per second (0 if unknown). */
    val bitrate: Int,
    val contentLength: Long?,
    val codec: String?,
)

/** A playable music-video stream. [audioUrl] is null when [videoUrl] already carries audio (muxed). */
data class VideoStream(
    val videoUrl: String,
    val audioUrl: String?,
    val width: Int,
    val height: Int,
    val videoMimeType: String,
    val audioMimeType: String?,
    /**
     * The upload actually played when it is not the requested video (e.g. the official clip found
     * for a YouTube Music art track). Its timeline differs from the song's: no position hand-off.
     */
    val substituteVideoId: String? = null,
)

class OnlineException(message: String, cause: Throwable? = null, val kind: Kind = Kind.UNKNOWN) : Exception(message, cause) {
    enum class Kind { NO_NETWORK, NOT_FOUND, AGE_RESTRICTED, GEO_BLOCKED, UNAVAILABLE, RATE_LIMITED, UNKNOWN }
}

interface YouTubeSource {
    /** Searches YouTube Music (songs/albums/playlists/artists) or YouTube (videos). */
    suspend fun search(query: String, filter: SearchFilter): SearchPage

    /** Next page of a previous search. */
    suspend fun more(page: SearchPage): SearchPage

    /** Autocomplete suggestions. Never throws (empty list on failure). */
    suspend fun suggestions(query: String): List<String>

    /**
     * Understands a pasted/shared link (or a text that contains one): video, YouTube Music song,
     * playlist, album (OLAK5uy_… playlists / browse/MPREb…), channel/artist.
     */
    suspend fun resolve(text: String): ResolvedLink

    /** Loads every track of an album/playlist (or an artist's songs), following pages up to [limit]. */
    suspend fun collection(url: String, kind: CollectionKind, limit: Int = 500): CollectionDetail

    /** Picks the audio-only stream to download for [videoId] according to the quality setting. */
    suspend fun audioStream(videoId: String, quality: com.villamediana.surco.data.AudioQuality): AudioStream

    /** Fresh details of one video (title/uploader/duration/thumbnail) — used for shared single links. */
    suspend fun track(videoId: String): RemoteTrack

    /**
     * "Up next" for a song (YouTube Music radio / related videos), used to keep an ONLINE queue
     * going when it ends. Never throws (empty on failure).
     */
    suspend fun related(videoId: String, limit: Int = 25): List<RemoteTrack> = emptyList()

    /**
     * Best progressive video stream up to [maxHeight] for "Ver o vídeo" (video-only mp4 + the
     * audio stream, or a muxed stream). For YouTube Music songs ("Topic" art tracks) this may be
     * the static-art video; implementations may look up the official music video instead.
     */
    suspend fun videoStream(videoId: String, maxHeight: Int = 720): VideoStream =
        throw OnlineException("Video not supported", kind = OnlineException.Kind.UNAVAILABLE)
}

/** Clean metadata for a track about to be saved. */
data class TrackMeta(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    /** High resolution square artwork, if a catalog match was found. */
    val artworkUrl: String? = null,
)

/** What the enricher knows when a download starts. */
data class MetaInput(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val year: Int?,
    val durationMs: Long?,
    val fromMusicCatalog: Boolean,
)

interface MetadataEnricher {
    /**
     * Cleans the title/artist (removes "(Official Video)", splits "Artist - Title", etc.) and looks
     * the song up in a public catalog (iTunes Search API) for genre, album, year, track number and
     * artwork. Values already present in [input] (e.g. from an album download) win over the catalog.
     * Never throws: on any failure returns the cleaned input.
     */
    suspend fun enrich(input: MetaInput): TrackMeta
}

data class Lyrics(val text: String, val synced: Boolean)

interface LyricsSource {
    /** Synced (LRC) lyrics when available, plain otherwise, null if not found. Never throws. */
    suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics?
}
