package com.villamediana.sound.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.villamediana.sound.R
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.data.model.ArtistSummary
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.data.sortKey
import com.villamediana.sound.smart.Mix
import com.villamediana.sound.smart.SmartMixes
import com.villamediana.sound.smart.Taste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The library as a browse tree for cars, watches and Bluetooth browsers:
 *
 * root → Tocar aleatório inteligente (playable) · Mixes · Playlists · Álbuns · Artistas · Curtidas · Recentes
 *
 * Containers are playable too. A song inside a container has the id "<container>|<trackId>", so
 * picking it plays the whole container from that song — like tapping a row in the app.
 */
internal class LibraryBrowser(
    private val context: Context,
    private val library: LibraryRepository,
    private val tracks: suspend () -> List<Track>,
    private val taste: suspend () -> Taste,
    private val strings: PlaybackStrings,
) {
    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Children of [parentId], or null when the id is unknown. */
    suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        ROOT -> listOf(
            smartItem(),
            folder(MIXES, context.getString(R.string.playback_browse_mixes), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(PLAYLISTS, context.getString(R.string.playback_browse_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(ALBUMS, context.getString(R.string.playback_browse_albums), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            folder(ARTISTS, context.getString(R.string.playback_browse_artists), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            folder(LIKED, context.getString(R.string.playback_browse_liked), MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true),
            folder(RECENT, context.getString(R.string.playback_browse_recent), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true),
        )
        MIXES -> mixes().map(::mixItem)
        PLAYLISTS -> library.playlists.first().filter { it.trackCount > 0 }.map(::playlistItem)
        ALBUMS -> library.albums.first().map(::albumItem)
        ARTISTS -> library.artists.first().map(::artistItem)
        else -> collection(parentId)?.tracks?.map { MediaItems.browsable("$parentId$SEP${it.id}", it) }
    }

    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return root()
        children(ROOT)!!.firstOrNull { it.mediaId == id }?.let { return it }
        val sep = id.lastIndexOf(SEP)
        if (sep > 0) {
            val track = id.substring(sep + 1).toLongOrNull()?.let { library.getTrack(it) } ?: return null
            return MediaItems.browsable(id, track)
        }
        id.toLongOrNull()?.let { trackId -> return library.getTrack(trackId)?.let(MediaItems::of) }
        return when {
            id.startsWith(P_MIX) -> mixes().firstOrNull { it.id == decode(id.removePrefix(P_MIX)) }?.let(::mixItem)
            id.startsWith(P_PLAYLIST) -> id.removePrefix(P_PLAYLIST).toLongOrNull()
                ?.let { pid -> library.playlists.first().firstOrNull { it.id == pid } }?.let(::playlistItem)
            id.startsWith(P_ALBUM) -> albumKey(id)?.let { key -> library.albums.first().firstOrNull { it.key.normalized == key.normalized } }
                ?.let(::albumItem)
            id.startsWith(P_ARTIST) -> decode(id.removePrefix(P_ARTIST)).let { name ->
                library.artists.first().firstOrNull { it.name.trim().lowercase() == name }
            }?.let(::artistItem)
            else -> null
        }
    }

    /** Local search (title, artist, album), as playable songs. */
    suspend fun search(query: String): List<MediaItem> {
        val ctx = P_SEARCH + encode(query)
        return library.search(query).first().tracks.take(SEARCH_LIMIT).map { MediaItems.browsable("$ctx$SEP${it.id}", it) }
    }

    /**
     * What to play for a request coming from outside the app: a song in a container ("album/…|42"),
     * a container, a bare track id, or a voice query ("tocar Djavan no Sound").
     */
    suspend fun resolve(item: MediaItem): QueuePlan? {
        val query = item.requestMetadata.searchQuery
        if (item.mediaId.isEmpty() && query != null) return voiceSearch(query)
        val id = item.mediaId
        val sep = id.lastIndexOf(SEP)
        if (sep > 0) {
            val trackId = id.substring(sep + 1).toLongOrNull() ?: return null
            val plan = collection(id.substring(0, sep)) ?: return single(trackId)
            val start = plan.tracks.indexOfFirst { it.id == trackId }
            return if (start >= 0) plan.copy(startIndex = start) else single(trackId)
        }
        id.toLongOrNull()?.let { return single(it) }
        return collection(id)
    }

    private suspend fun single(trackId: Long): QueuePlan? =
        library.getTrack(trackId)?.let { QueuePlan(listOf(it), 0, null, smart = false) }

    private suspend fun collection(id: String): QueuePlan? = when {
        id == SMART -> smartPlan(SmartSeed.Library)
        id == LIKED -> library.liked.first().takeIf { it.isNotEmpty() }
            ?.let { QueuePlan(it, 0, PlaySource(PlaySource.Type.LIKED, strings.liked), smart = false) }
        id == RECENT -> library.recentlyPlayed(RECENT_LIMIT).first().takeIf { it.isNotEmpty() }
            ?.let { QueuePlan(it, 0, PlaySource(PlaySource.Type.SONGS, strings.recent), smart = false) }
        id.startsWith(P_MIX) -> smartPlan(SmartSeed.Mix(decode(id.removePrefix(P_MIX))))
        id.startsWith(P_PLAYLIST) -> id.removePrefix(P_PLAYLIST).toLongOrNull()?.let { pid ->
            library.playlist(pid).first()?.takeIf { it.tracks.isNotEmpty() }?.let {
                QueuePlan(it.tracks, 0, PlaySource(PlaySource.Type.PLAYLIST, it.summary.name, pid.toString()), smart = false)
            }
        }
        id.startsWith(P_ALBUM) -> albumKey(id)?.let { key ->
            library.album(key).first()?.takeIf { it.tracks.isNotEmpty() }?.let {
                QueuePlan(it.tracks, 0, PlaySource(PlaySource.Type.ALBUM, it.summary.title, it.summary.key.normalized), smart = false)
            }
        }
        id.startsWith(P_ARTIST) -> library.artist(decode(id.removePrefix(P_ARTIST))).first()?.takeIf { it.allTracks.isNotEmpty() }?.let {
            QueuePlan(it.allTracks, 0, PlaySource(PlaySource.Type.ARTIST, it.summary.name, it.summary.name), smart = false)
        }
        id.startsWith(P_SEARCH) -> decode(id.removePrefix(P_SEARCH)).let { q ->
            library.search(q).first().tracks.take(SEARCH_LIMIT).takeIf { it.isNotEmpty() }
                ?.let { QueuePlan(it, 0, PlaySource(PlaySource.Type.SEARCH, strings.search(q), q), smart = false) }
        }
        else -> null
    }

    /**
     * Voice requests: an empty query means "play something" (Android's contract) → smart shuffle;
     * otherwise an artist, album or playlist named exactly like the query, else matching songs.
     */
    private suspend fun voiceSearch(query: String): QueuePlan? {
        val q = query.sortKey()
        if (q.isBlank()) return collection(SMART)
        val result = library.search(query).first()
        result.artists.firstOrNull { it.name.sortKey() == q }?.let { return collection(P_ARTIST + encode(it.name.trim().lowercase())) }
        result.albums.firstOrNull { it.title.sortKey() == q }?.let { return collection(albumId(it.key)) }
        result.playlists.firstOrNull { it.name.sortKey() == q }?.let { return collection(P_PLAYLIST + it.id) }
        return collection(P_SEARCH + encode(query))
    }

    private suspend fun mixes(): List<Mix> {
        val all = tracks()
        val t = taste()
        return withContext(Dispatchers.Default) { SmartMixes.build(all, t, strings.mixLabels) }
    }

    private suspend fun smartPlan(seed: SmartSeed): QueuePlan? {
        val all = tracks()
        val t = taste()
        return withContext(Dispatchers.Default) { SmartPlans.plan(seed, all, t, strings, System.currentTimeMillis()) }
    }

    // ---------------------------------------------------------------- items

    private fun smartItem(): MediaItem = MediaItem.Builder()
        .setMediaId(SMART)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(context.getString(R.string.playback_browse_smart))
                .setSubtitle(context.getString(R.string.playback_browse_smart_subtitle))
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private fun folder(id: String, title: String, type: Int, playable: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setMediaType(type)
                .setIsBrowsable(true)
                .setIsPlayable(playable)
                .build(),
        )
        .build()

    private fun container(id: String, title: String, subtitle: String?, cover: String?, type: Int, artist: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(artist)
                    .setArtworkUri(MediaItems.coverUri(cover))
                    .setMediaType(type)
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun songs(n: Int) = context.resources.getQuantityString(R.plurals.n_songs, n, n)

    private fun mixItem(mix: Mix) =
        container(P_MIX + encode(mix.id), mix.title, mix.subtitle, mix.covers.firstOrNull(), MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun playlistItem(p: PlaylistSummary) =
        container(P_PLAYLIST + p.id, p.name, songs(p.trackCount), p.coverPath ?: p.mosaic.firstOrNull(), MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun albumItem(a: AlbumSummary) =
        container(albumId(a.key), a.title, a.artist, a.coverPath, MediaMetadata.MEDIA_TYPE_ALBUM, artist = a.artist)

    private fun artistItem(a: ArtistSummary) =
        container(P_ARTIST + encode(a.name.trim().lowercase()), a.name, songs(a.trackCount), a.coverPath, MediaMetadata.MEDIA_TYPE_ARTIST)

    private fun albumId(key: AlbumKey) = P_ALBUM + encode(key.normalized)

    private fun albumKey(id: String): AlbumKey? {
        val parts = decode(id.removePrefix(P_ALBUM)).split(ALBUM_SEP, limit = 2)
        return if (parts.size == 2) AlbumKey(parts[0], parts[1]) else null
    }

    companion object {
        const val ROOT = "root"
        const val SMART = "smart"
        const val MIXES = "mixes"
        const val PLAYLISTS = "playlists"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val LIKED = "liked"
        const val RECENT = "recent"
        const val P_MIX = "mix/"
        const val P_PLAYLIST = "playlist/"
        const val P_ALBUM = "album/"
        const val P_ARTIST = "artist/"
        const val P_SEARCH = "search/"
        const val SEP = '|'
        private const val ALBUM_SEP = '\u0001'
        private const val SEARCH_LIMIT = 50
        private const val RECENT_LIMIT = 50

        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
        fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** Pages a list the way browse requests ask (page × pageSize), safely for huge values. */
        fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
            if (pageSize <= 0 || page < 0) return this
            val from = page.toLong() * pageSize
            if (from >= size) return emptyList()
            return subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
        }
    }
}
