package com.villamediana.sound.data

import com.villamediana.sound.data.db.PlaylistEntry
import com.villamediana.sound.data.db.PlaylistOnline
import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.ImportedPlaylist
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import kotlinx.serialization.builtins.ListSerializer
import com.villamediana.sound.online.YouTubeLinks
import androidx.room.withTransaction
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.db.AlbumPin
import com.villamediana.sound.data.db.LikedOnline
import com.villamediana.sound.data.db.PlayEvent
import com.villamediana.sound.data.db.Playlist
import com.villamediana.sound.data.db.PlaylistTrack
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.AlbumDetail
import com.villamediana.sound.data.model.AlbumSummary
import com.villamediana.sound.data.model.ArtistDetail
import com.villamediana.sound.data.model.ArtistSummary
import com.villamediana.sound.data.model.GenreSummary
import com.villamediana.sound.data.model.LibraryStats
import com.villamediana.sound.data.model.LocalSearchResult
import com.villamediana.sound.data.model.PinnedItem
import com.villamediana.sound.data.model.PlaylistDetail
import com.villamediana.sound.data.model.PlaylistSummary
import com.villamediana.sound.data.model.TrackSort
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Single source of truth for the offline library. Libraries are small (hundreds to a few thousand
 * songs), so grouping into albums/artists/genres happens in memory from one shared flow of all
 * tracks — simpler and more flexible than SQL GROUP BYs, and fast enough.
 */
class LibraryRepository(
    private val db: SoundDatabase,
    private val scope: CoroutineScope,
) {
    private val trackDao = db.tracks()
    private val playlistDao = db.playlists()
    private val pinDao = db.albumPins()
    private val eventDao = db.playEvents()
    private val likedOnlineDao = db.likedOnline()
    private val importedDao = db.importedPlaylists()
    private val onlinePlayDao = db.onlinePlays()
    private val playlistOnlineDao = db.playlistOnline()

    /** All tracks, newest first. Shared: every derived flow reuses one Room query. */
    val tracks: Flow<List<Track>> = trackDao.observeAll()
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val albumPins: Flow<List<AlbumPin>> = pinDao.observeAll()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val downloadedVideoIds: Flow<Set<String>> = trackDao.observeVideoIds().map { it.toSet() }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun track(id: Long): Flow<Track?> = trackDao.observe(id)
    suspend fun getTrack(id: Long): Track? = trackDao.get(id)
    suspend fun getTracks(ids: List<Long>): List<Track> {
        val byId = trackDao.getMany(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
    suspend fun allTracks(): List<Track> = trackDao.all()
    suspend fun trackByVideoId(videoId: String): Track? = trackDao.byVideoId(videoId)

    fun sortedTracks(sort: TrackSort): Flow<List<Track>> = tracks.map { sortTracks(it, sort) }.flowOn(Dispatchers.Default)

    // ---------------------------------------------------------------- albums

    val albums: Flow<List<AlbumSummary>> = combine(tracks, albumPins) { all, pins ->
        val pinned = pins.associateBy { it.albumKey }
        groupAlbums(all).map { it.copy(pinned = pinned.containsKey(it.key.normalized)) }
    }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun album(key: AlbumKey): Flow<AlbumDetail?> = albums.combine(tracks) { albums, all ->
        val summary = albums.firstOrNull { it.key.normalized == key.normalized } ?: return@combine null
        val tracks = all.filter { it.albumKey?.normalized == key.normalized }
            .sortedWith(compareBy<Track>({ it.trackNumber ?: Int.MAX_VALUE }, { it.addedAt }))
        AlbumDetail(summary, tracks)
    }.flowOn(Dispatchers.Default)

    suspend fun setAlbumPinned(key: AlbumKey, pinned: Boolean) {
        if (pinned) pinDao.insert(AlbumPin(key.normalized, key.artist, key.title)) else pinDao.delete(key.normalized)
    }

    // ---------------------------------------------------------------- artists

    val artists: Flow<List<ArtistSummary>> = tracks.map { all ->
        all.groupBy { it.artist.trim().lowercase() }.values.map { group ->
            ArtistSummary(
                name = group.mostCommon { it.artist.trim() },
                coverPath = group.sortedByDescending { it.playCount }.firstNotNullOfOrNull { it.coverPath },
                trackCount = group.size,
                albumCount = group.mapNotNull { it.albumKey?.normalized }.distinct().size,
                lastPlayedAt = group.mapNotNull { it.lastPlayedAt }.maxOrNull(),
            )
        }.sortedBy { it.name.sortKey() }
    }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun artist(name: String): Flow<ArtistDetail?> = combine(tracks, albums) { all, albums ->
        val n = name.trim().lowercase()
        val mine = all.filter { it.artist.trim().lowercase() == n || it.albumArtistOrArtist.trim().lowercase() == n }
        if (mine.isEmpty()) return@combine null
        val summary = ArtistSummary(
            name = mine.mostCommon { it.artist.trim() },
            coverPath = mine.sortedByDescending { it.playCount }.firstNotNullOfOrNull { it.coverPath },
            trackCount = mine.size,
            albumCount = mine.mapNotNull { it.albumKey?.normalized }.distinct().size,
            lastPlayedAt = mine.mapNotNull { it.lastPlayedAt }.maxOrNull(),
        )
        ArtistDetail(
            summary = summary,
            topTracks = mine.sortedWith(compareByDescending<Track> { it.playCount }.thenByDescending { it.liked }.thenByDescending { it.addedAt }).take(10),
            albums = albums.filter { it.key.artist.trim().lowercase() == n }.sortedByDescending { it.year ?: 0 },
            singles = mine.filter { !it.hasAlbum },
            allTracks = mine.sortedWith(compareBy({ it.album?.lowercase() ?: "￿" }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title.sortKey() })),
        )
    }.flowOn(Dispatchers.Default)

    // ---------------------------------------------------------------- genres

    val genres: Flow<List<GenreSummary>> = tracks.map { all ->
        all.filter { !it.genre.isNullOrBlank() }
            .groupBy { it.genre!!.trim().lowercase() }.values
            .map { group ->
                val byPlays = group.sortedByDescending { it.playCount }
                GenreSummary(
                    name = group.mostCommon { it.genre!!.trim() },
                    trackCount = group.size,
                    durationMs = group.sumOf { it.durationMs },
                    covers = byPlays.mapNotNull { it.coverPath }.distinct().take(4),
                    accentColor = byPlays.firstNotNullOfOrNull { it.accentColor },
                )
            }
            .sortedByDescending { it.trackCount }
    }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun genreTracks(genre: String): Flow<List<Track>> = tracks.map { all ->
        val g = genre.trim().lowercase()
        all.filter { it.genre?.trim()?.lowercase() == g }
    }.flowOn(Dispatchers.Default)

    // ---------------------------------------------------------------- liked & history

    val liked: Flow<List<Track>> = tracks.map { all -> all.filter { it.liked }.sortedByDescending { it.likedAt ?: 0 } }
        .flowOn(Dispatchers.Default)

    /** Streamed songs liked without downloading them, newest first. */
    val likedOnline: Flow<List<LikedOnline>> = likedOnlineDao.observeAll()

    /**
     * Curtidas: the liked library songs plus the streamed ones (as transient tracks, which play
     * online), newest like first. A streamed like whose song is on the phone already counts once.
     */
    val likedAll: Flow<List<Track>> = combine(tracks, likedOnline) { all, online ->
        val onPhone = all.mapNotNullTo(HashSet()) { it.videoId }
        val mine = all.filter { it.liked }.map { it to (it.likedAt ?: 0L) }
        val streamed = online.filter { it.videoId !in onPhone }.map { it.toTrack() to it.likedAt }
        (mine + streamed).sortedByDescending { it.second }.map { it.first }
    }.flowOn(Dispatchers.Default)

    /** Video ids of every liked song (on the phone or streamed): the hearts of online rows. */
    val likedVideoIds: Flow<Set<String>> = combine(tracks, likedOnline) { all, online ->
        all.filter { it.liked }.mapNotNullTo(HashSet()) { it.videoId } + online.map { it.videoId }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** The heart of an online row: the library song when it is on the phone, else a streamed like. */
    suspend fun setLiked(remote: RemoteTrack, liked: Boolean) = db.withTransaction {
        val have = trackDao.byVideoId(remote.videoId)
        if (have != null) trackDao.setLiked(have.id, liked, if (liked) System.currentTimeMillis() else null)
        if (liked && have == null) likedOnlineDao.insert(LikedOnline.of(remote)) else likedOnlineDao.delete(remote.videoId)
    }

    fun recentlyAdded(limit: Int = 20): Flow<List<Track>> = tracks.map { it.take(limit) }

    fun recentlyPlayed(limit: Int = 20): Flow<List<Track>> = tracks.map { all ->
        all.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }.take(limit)
    }.flowOn(Dispatchers.Default)

    /** Most played (not skipped) since [sinceMs], most first. */
    fun mostPlayedSince(sinceMs: Long, limit: Int = 30): Flow<List<Track>> =
        combine(eventDao.observeTopSince(sinceMs, limit), tracks) { top, all ->
            val byId = all.associateBy { it.id }
            top.mapNotNull { byId[it.trackId] }
        }.flowOn(Dispatchers.Default)

    /** Play counts per track since [sinceMs] (for smart features). */
    suspend fun playCountsSince(sinceMs: Long): Map<Long, Int> =
        eventDao.topSince(sinceMs, Int.MAX_VALUE).associate { it.trackId to it.plays }

    suspend fun recentEvents(limit: Int = 200): List<PlayEvent> = eventDao.recent(limit)

    suspend fun setLiked(trackId: Long, liked: Boolean) =
        trackDao.setLiked(trackId, liked, if (liked) System.currentTimeMillis() else null)

    /** Called by the player when a listening session of a track ends. */
    suspend fun recordPlay(trackId: Long, listenedMs: Long, durationMs: Long, context: String?) {
        if (listenedMs < 1_000) return
        val now = System.currentTimeMillis()
        val (completed, skipped) = listenOutcome(listenedMs, durationMs)
        db.withTransaction {
            eventDao.insert(PlayEvent(trackId = trackId, playedAt = now, listenedMs = listenedMs, completed = completed, skipped = skipped, context = context))
            if (skipped) trackDao.markSkipped(trackId) else trackDao.markPlayed(trackId, now)
        }
    }

    /** A song listened to online (not in the library): kept for the retrospective and the smart downloads. */
    suspend fun recordOnlinePlay(song: RemoteTrack, listenedMs: Long, durationMs: Long, context: String?, now: Long = System.currentTimeMillis()) {
        if (listenedMs < 1_000) return
        val (completed, skipped) = listenOutcome(listenedMs, durationMs)
        onlinePlayDao.insert(
            OnlinePlay(
                videoId = song.videoId, title = song.title, artist = song.artist, album = song.album,
                thumbnailUrl = song.thumbnailUrl, url = song.url, durationMs = durationMs,
                playedAt = now, listenedMs = listenedMs, completed = completed, skipped = skipped, context = context,
            ),
        )
    }

    /** Library listens since [since] (newest first). */
    fun playEventsSince(since: Long): Flow<List<PlayEvent>> = eventDao.observeSince(since)

    /** Online listens since [since] (newest first). */
    fun onlinePlaysSince(since: Long): Flow<List<OnlinePlay>> = onlinePlayDao.observeSince(since)

    suspend fun onlinePlaysListSince(since: Long): List<OnlinePlay> = onlinePlayDao.since(since)

    /** The library's listening sessions since [since] (newest first), for the taste profile. */
    suspend fun playEventsListSince(since: Long): List<PlayEvent> = eventDao.since(since)

    /** Streamed songs with a heart. */
    suspend fun likedOnlineList(): List<LikedOnline> = likedOnlineDao.all()

    // ---------------------------------------------------------------- the profile's vault
    // What follows the person to another phone (social/VaultSync): hearts, listening, playlists.

    /** Every heart that can follow the person (songs with a YouTube id) and when it was given. */
    suspend fun vaultLikes(): List<Pair<RemoteTrack, Long>> {
        val mine = trackDao.all().filter { it.liked && !it.videoId.isNullOrBlank() }.map { t ->
            RemoteTrack(t.videoId!!, t.sourceUrl ?: "https://music.youtube.com/watch?v=${t.videoId}", t.title, t.artist, t.album, t.durationMs.takeIf { it > 0 }, t.coverPath?.takeIf { it.startsWith("https://") }) to (t.likedAt ?: t.addedAt)
        }
        val streamed = likedOnlineDao.all().map { RemoteTrack(it.videoId, it.url, it.title, it.artist, it.album, it.durationMs, it.thumbnailUrl) to it.likedAt }
        return (mine + streamed).distinctBy { it.first.videoId }
    }

    /** A heart from another phone, with the time it was given there. */
    suspend fun likeFromVault(song: RemoteTrack, at: Long) = db.withTransaction {
        val have = trackDao.byVideoId(song.videoId)
        if (have != null) trackDao.setLiked(have.id, true, at)
        else likedOnlineDao.insert(LikedOnline.of(song).copy(likedAt = at))
    }

    /** A heart taken back on another phone. */
    suspend fun unlikeFromVault(videoId: String) = db.withTransaction {
        trackDao.byVideoId(videoId)?.let { trackDao.setLiked(it.id, false, null) }
        likedOnlineDao.delete(videoId)
    }

    /** Listening from another phone (as streamed listening here). */
    suspend fun addOnlinePlays(plays: List<OnlinePlay>) {
        if (plays.isNotEmpty()) onlinePlayDao.insertAll(plays)
    }

    /** A playlist with its songs in order: the ones with a YouTube id, and the rows of files only on this phone. */
    data class VaultPlaylist(val playlist: Playlist, val songs: List<RemoteTrack>, val phoneOnly: List<Long>)

    suspend fun vaultPlaylists(): List<VaultPlaylist> {
        val tracks = trackDao.all().associateBy { it.id }
        return playlistDao.all().map { p ->
            val local = playlistDao.rows(p.id).mapNotNull { r -> tracks[r.trackId]?.let { r.position to it } }
            val online = playlistOnlineDao.rows(p.id).map { it.position to it }
            val ordered = (local.map { it.first to (it.second as Any) } + online.map { it.first to (it.second as Any) }).sortedBy { it.first }.map { it.second }
            val songs = ordered.mapNotNull { item ->
                when (item) {
                    is Track -> item.videoId?.takeIf { it.isNotBlank() }?.let { v ->
                        RemoteTrack(v, item.sourceUrl ?: "https://music.youtube.com/watch?v=$v", item.title, item.artist, item.album, item.durationMs.takeIf { it > 0 }, item.coverPath?.takeIf { it.startsWith("https://") })
                    }
                    is PlaylistOnline -> RemoteTrack(item.videoId, item.url, item.title, item.artist, item.album, item.durationMs, item.thumbnailUrl, item.catalog)
                    else -> null
                }
            }
            val phoneOnly = ordered.filterIsInstance<Track>().filter { it.videoId.isNullOrBlank() }.map { it.id }
            VaultPlaylist(p, songs, phoneOnly)
        }
    }

    /**
     * A playlist as another phone has it: made here if missing (with its creation time, the same on
     * every phone), its songs replaced otherwise — the files only on this phone stay, at the end.
     */
    suspend fun putPlaylistFromVault(
        createdAt: Long,
        updatedAt: Long,
        name: String,
        description: String?,
        sourceUrl: String?,
        pinned: Boolean,
        songs: List<RemoteTrack>,
        existingId: Long?,
    ) = db.withTransaction {
        val id = existingId ?: playlistDao.insert(Playlist(name = name, description = description, sourceUrl = sourceUrl, pinned = pinned, createdAt = createdAt, updatedAt = updatedAt))
        val keep = if (existingId != null) playlistDao.rows(id).filter { r -> trackDao.get(r.trackId)?.videoId.isNullOrBlank() }.map { it.trackId } else emptyList()
        playlistDao.clearRows(id)
        playlistOnlineDao.clearFor(id)
        var pos = 0
        for (s in songs) {
            val have = trackDao.byVideoId(s.videoId)
            if (have != null) playlistDao.insertRows(listOf(PlaylistTrack(playlistId = id, trackId = have.id, position = pos++)))
            else playlistOnlineDao.insert(PlaylistOnline.of(id, s, pos++))
        }
        if (keep.isNotEmpty()) playlistDao.insertRows(keep.map { PlaylistTrack(playlistId = id, trackId = it, position = pos++) })
        val p = playlistDao.get(id) ?: return@withTransaction
        // Its time stays the other phone's (otherwise each phone would think it changed it).
        playlistDao.update(p.copy(name = name, description = description, pinned = pinned, sourceUrl = sourceUrl, updatedAt = updatedAt))
    }

    suspend fun deletePlaylistFromVault(id: Long) = playlistDao.delete(id)

    suspend fun importedList(): List<ImportedPlaylist> = importedDao.all()

    suspend fun putImportedFromVault(p: ImportedPlaylist) = importedDao.upsert(p)

    suspend fun clearHistory() = db.withTransaction {
        eventDao.clear()
        onlinePlayDao.clear()
    }

    /**
     * Keeps the listening history from growing forever: [HISTORY_KEPT_MS] back (the mixes, the taste
     * and January's look at the year before all read less than that) and at most [HISTORY_MAX_ROWS]
     * of each kind. Returns the listening time removed (the profile's hours keep counting it).
     */
    suspend fun pruneHistory(now: Long = System.currentTimeMillis()): Long = db.withTransaction {
        val before = now - HISTORY_KEPT_MS
        val removed = eventDao.listenedToPrune(before, HISTORY_MAX_ROWS) + onlinePlayDao.listenedToPrune(before, HISTORY_MAX_ROWS)
        eventDao.prune(before, HISTORY_MAX_ROWS)
        onlinePlayDao.prune(before, HISTORY_MAX_ROWS)
        removed
    }

    // ---------------------------------------------------------------- playlists

    val playlists: Flow<List<PlaylistSummary>> = combine(
        playlistDao.observeAll(), playlistDao.observeAllEntries(), tracks, playlistOnlineDao.observeAll(),
    ) { lists, entries, all, online ->
        val byId = all.associateBy { it.id }
        val byPlaylist = entries.groupBy { it.playlistId }
        val onlineByPlaylist = online.groupBy { it.playlistId }
        lists.map { p ->
            // Songs on the phone and streamed ones, in the playlist's order.
            val local = byPlaylist[p.id].orEmpty().mapNotNull { r -> byId[r.trackId]?.let { r.position to it } }
            val streamed = onlineByPlaylist[p.id].orEmpty().map { it.position to it.toTrack() }
            val ts = (local + streamed).sortedBy { it.first }.map { it.second }
            PlaylistSummary(p, ts.size, ts.sumOf { it.durationMs }, ts.mapNotNull { it.coverPath }.distinct().take(4))
        }
    }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun playlist(id: Long): Flow<PlaylistDetail?> = combine(playlists, playlistDao.observeEntries(id), playlistOnlineDao.observeFor(id)) { lists, entries, online ->
        val summary = lists.firstOrNull { it.id == id } ?: return@combine null
        // Streamed rows carry a negative rowId (−their own id): removing and moving tell them apart.
        val streamed = online.map { PlaylistEntry(rowId = -it.rowId, position = it.position, entryAddedAt = it.addedAt, track = it.toTrack()) }
        PlaylistDetail(summary, (entries + streamed).sortedWith(compareBy({ it.position }, { it.entryAddedAt })))
    }

    /** The next free position at the end of a playlist (both kinds of rows). */
    private suspend fun nextPosition(playlistId: Long): Int =
        maxOf(playlistDao.maxPosition(playlistId), playlistOnlineDao.maxPosition(playlistId)) + 1

    /**
     * A streamed song into a playlist, without downloading it (it plays online). A song already on
     * the phone goes in as itself. False when it was already there.
     */
    suspend fun addOnlineToPlaylist(playlistId: Long, song: RemoteTrack): Boolean = db.withTransaction {
        val have = trackDao.byVideoId(song.videoId)
        if (have != null) return@withTransaction addToPlaylist(playlistId, listOf(have.id)) > 0
        if (playlistOnlineDao.countOf(playlistId, song.videoId) > 0) return@withTransaction false
        playlistOnlineDao.insert(PlaylistOnline.of(playlistId, song, nextPosition(playlistId)))
        playlistDao.touch(playlistId, System.currentTimeMillis())
        true
    }

    /**
     * Songs of a list into a playlist, in order: the ones on the phone as themselves, the streamed
     * ones as streamed rows (nothing downloads). Returns how many went in (repeats are skipped).
     */
    suspend fun addSongsToPlaylist(playlistId: Long, tracks: List<Track>): Int = db.withTransaction {
        var added = 0
        tracks.distinctBy { it.videoId ?: "t:${it.id}" }.forEach { t ->
            val online = streamedOf(t)
            added += when {
                online != null -> if (addOnlineToPlaylist(playlistId, online)) 1 else 0
                t.id > 0 -> addToPlaylist(playlistId, listOf(t.id))
                else -> 0
            }
        }
        added
    }

    /** A streamed (transient) song as the online song it is; null for a song in the library. */
    private fun streamedOf(track: Track): RemoteTrack? = track.takeIf { it.id < 0 }?.let { t ->
        t.videoId?.let { id -> RemoteTrack(id, t.sourceUrl ?: t.filePath, t.title, t.artist, t.album, t.durationMs.takeIf { it > 0 }, t.coverPath, (t.sourceUrl ?: "").contains("music.youtube.com")) }
    }

    /** Puts a song back at [position] (undo of a removal): a library row, or a streamed one. */
    suspend fun putBackInPlaylist(playlistId: Long, track: Track, position: Int?) {
        val online = streamedOf(track)
        when {
            online != null && position != null -> addOnlineToPlaylistAt(playlistId, online, position)
            online != null -> addOnlineToPlaylist(playlistId, online)
            position != null -> addToPlaylistAt(playlistId, track.id, position)
            else -> addToPlaylist(playlistId, listOf(track.id))
        }
    }

    /** Puts a streamed song back at [position] (undo of a removal). */
    suspend fun addOnlineToPlaylistAt(playlistId: Long, song: RemoteTrack, position: Int) = db.withTransaction {
        if (playlistOnlineDao.countOf(playlistId, song.videoId) == 0) {
            playlistOnlineDao.insert(PlaylistOnline.of(playlistId, song, position))
            playlistDao.touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun getPlaylist(id: Long): Playlist? = playlistDao.get(id)
    suspend fun playlistBySourceUrl(url: String): Playlist? = playlistDao.bySourceUrl(url)

    // ---------------------------------------------------------------- imported (Spotify) playlists

    /** Playlists imported from Spotify, newest first. They stream; songs download one by one or all. */
    val imported: Flow<List<ImportedPlaylist>> = importedDao.observeAll()

    /** Saves (or refreshes, same url) an imported playlist with its songs as found on YouTube Music. */
    suspend fun saveImported(url: String, name: String, owner: String?, coverUrl: String?, tracks: List<RemoteTrack>) {
        val songs = tracks.map { ImportedSong(it.videoId, it.url, it.title, it.artist, it.album, it.durationMs, it.thumbnailUrl, it.fromMusicCatalog) }
        importedDao.upsert(
            ImportedPlaylist(
                url = url,
                name = name,
                owner = owner,
                coverUrl = coverUrl,
                tracksJson = importedJson.encodeToString(ListSerializer(ImportedSong.serializer()), songs),
                trackCount = songs.size,
            ),
        )
    }

    /** An imported playlist as the online playlist page shows it; null when it was removed. */
    suspend fun importedDetail(url: String): CollectionDetail? {
        val p = importedDao.get(url) ?: return null
        val songs = importedSongs(p)
        return CollectionDetail(
            collection = RemoteCollection(CollectionKind.PLAYLIST, p.url, p.name, p.owner, p.coverUrl, songs.size.toLong()),
            tracks = songs.map { RemoteTrack(it.videoId, it.url, it.title, it.artist, it.album, it.durationMs, it.thumbnailUrl, it.catalog) },
        )
    }

    suspend fun removeImported(url: String) = importedDao.delete(url)

    /** "Editar informações" of an imported playlist: its name and cover here (Spotify's stay as they are). */
    suspend fun editImported(url: String, name: String, coverUrl: String?) {
        val p = importedDao.get(url) ?: return
        importedDao.upsert(p.copy(name = name.trim().ifEmpty { p.name }, coverUrl = coverUrl))
    }

    /** Takes the song at [index] out of an imported playlist (when it is still [videoId]). */
    suspend fun removeFromImported(url: String, index: Int, videoId: String) = db.withTransaction {
        val p = importedDao.get(url) ?: return@withTransaction
        val songs = importedSongs(p).toMutableList()
        val at = if (songs.getOrNull(index)?.videoId == videoId) index else songs.indexOfFirst { it.videoId == videoId }
        if (at < 0) return@withTransaction
        songs.removeAt(at)
        importedDao.upsert(p.copy(tracksJson = importedJson.encodeToString(ListSerializer(ImportedSong.serializer()), songs), trackCount = songs.size))
    }

    /** Puts a song back in an imported playlist at [index] (undo of a removal). */
    suspend fun putBackInImported(url: String, song: RemoteTrack, index: Int) = db.withTransaction {
        val p = importedDao.get(url) ?: return@withTransaction
        val songs = importedSongs(p).toMutableList()
        if (songs.any { it.videoId == song.videoId }) return@withTransaction
        songs.add(index.coerceIn(0, songs.size), ImportedSong(song.videoId, song.url, song.title, song.artist, song.album, song.durationMs, song.thumbnailUrl, song.fromMusicCatalog))
        importedDao.upsert(p.copy(tracksJson = importedJson.encodeToString(ListSerializer(ImportedSong.serializer()), songs), trackCount = songs.size))
    }

    private fun importedSongs(p: ImportedPlaylist): List<ImportedSong> =
        runCatching { importedJson.decodeFromString(ListSerializer(ImportedSong.serializer()), p.tracksJson) }.getOrDefault(emptyList())

    suspend fun createPlaylist(name: String, description: String? = null, coverPath: String? = null, sourceUrl: String? = null): Long =
        playlistDao.insert(Playlist(name = name.trim().ifEmpty { "Playlist" }, description = description, coverPath = coverPath, sourceUrl = sourceUrl))

    suspend fun updatePlaylist(playlist: Playlist) = playlistDao.update(playlist.copy(updatedAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(id: Long, name: String, description: String?) {
        val p = playlistDao.get(id) ?: return
        playlistDao.update(p.copy(name = name.trim().ifEmpty { p.name }, description = description?.takeIf { it.isNotBlank() }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setPlaylistPinned(id: Long, pinned: Boolean) {
        val p = playlistDao.get(id) ?: return
        playlistDao.update(p.copy(pinned = pinned))
    }

    suspend fun setPlaylistCover(id: Long, coverPath: String?) {
        val p = playlistDao.get(id) ?: return
        playlistDao.update(p.copy(coverPath = coverPath, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlaylist(id: Long) = playlistDao.delete(id)

    /** Appends tracks to the end. Duplicates are skipped unless [allowDuplicates]. Returns how many were added. */
    suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>, allowDuplicates: Boolean = false): Int = db.withTransaction {
        var pos = nextPosition(playlistId)
        val now = System.currentTimeMillis()
        val rows = trackIds.distinct().filter { allowDuplicates || playlistDao.countOf(playlistId, it) == 0 }
            .map { PlaylistTrack(playlistId = playlistId, trackId = it, position = pos++, addedAt = now) }
        if (rows.isNotEmpty()) {
            playlistDao.insertRows(rows)
            playlistDao.touch(playlistId, now)
        }
        rows.size
    }

    /** Inserts at a fixed position (playlist imports reserve slots in source order). */
    suspend fun addToPlaylistAt(playlistId: Long, trackId: Long, position: Int) = db.withTransaction {
        if (playlistDao.countOf(playlistId, trackId) == 0) {
            playlistDao.insertRows(listOf(PlaylistTrack(playlistId = playlistId, trackId = trackId, position = position)))
            playlistDao.touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun removeFromPlaylist(playlistId: Long, rowId: Long) {
        if (rowId < 0) playlistOnlineDao.delete(-rowId) else playlistDao.deleteRow(rowId)
        playlistDao.touch(playlistId, System.currentTimeMillis())
    }

    /** Moves the row at [from] to [to] (indexes in the current order, both kinds of rows) and renumbers 0..n. */
    suspend fun movePlaylistEntry(playlistId: Long, from: Int, to: Int) = db.withTransaction {
        // Library rows by their rowId, streamed ones by −rowId (as the screen sees them).
        val rows = (
            playlistDao.rows(playlistId).map { Triple(it.rowId, it.position, it.addedAt) } +
                playlistOnlineDao.rows(playlistId).map { Triple(-it.rowId, it.position, it.addedAt) }
            ).sortedWith(compareBy({ it.second }, { it.third })).map { it.first }.toMutableList()
        if (from !in rows.indices || to !in rows.indices || from == to) return@withTransaction
        rows.add(to, rows.removeAt(from))
        rows.forEachIndexed { i, rowId ->
            if (rowId < 0) playlistOnlineDao.setPosition(-rowId, i) else playlistDao.setPosition(rowId, i)
        }
        playlistDao.touch(playlistId, System.currentTimeMillis())
    }

    val pinned: Flow<List<PinnedItem>> = combine(playlists, albums, albumPins) { lists, albums, pins ->
        val byKey = albums.associateBy { it.key.normalized }
        val items = lists.filter { it.pinned }.map { PinnedItem.PinnedPlaylist(it, it.playlist.updatedAt) } +
            pins.mapNotNull { pin -> byKey[pin.albumKey]?.let { PinnedItem.PinnedAlbum(it, pin.pinnedAt) } }
        items.sortedByDescending { it.pinnedAt }
    }.flowOn(Dispatchers.Default)

    // ---------------------------------------------------------------- edits

    /** A new library song; a streamed like of it becomes the song's own like. */
    suspend fun insertTrack(track: Track): Long = db.withTransaction {
        val id = trackDao.insert(track)
        // Streamed in some playlists: the downloaded song takes its place there.
        track.videoId?.let { videoId ->
            playlistOnlineDao.byVideo(videoId).forEach { row ->
                if (playlistDao.countOf(row.playlistId, id) == 0) {
                    playlistDao.insertRows(listOf(PlaylistTrack(playlistId = row.playlistId, trackId = id, position = row.position, addedAt = row.addedAt)))
                }
                playlistOnlineDao.delete(row.rowId)
            }
        }
        val like = track.videoId?.let { likedOnlineDao.get(it) }
        if (like != null) {
            trackDao.setLiked(id, true, like.likedAt)
            likedOnlineDao.delete(like.videoId)
        }
        id
    }
    suspend fun updateTrack(track: Track) = trackDao.update(track)
    suspend fun setAccentColor(trackId: Long, color: Int?) = trackDao.setAccentColor(trackId, color)
    suspend fun setLyrics(trackId: Long, path: String?, synced: Boolean) = trackDao.setLyrics(trackId, path, synced)
    suspend fun setCover(trackId: Long, path: String?) = trackDao.setCover(trackId, path)

    /** One cover (with its accent color) for several tracks — the songs of an album. */
    suspend fun setCovers(trackIds: List<Long>, path: String?, accentColor: Int?) = trackDao.setCovers(trackIds, path, accentColor)

    /** Puts a track in an album without touching the rest of its row (play counts, likes…). */
    suspend fun setAlbumInfo(trackId: Long, album: String?, albumArtist: String?, trackNumber: Int?, year: Int?) =
        trackDao.setAlbumInfo(trackId, album, albumArtist, trackNumber, year)

    suspend fun editMetadata(
        trackId: Long,
        title: String,
        artist: String,
        album: String?,
        albumArtist: String?,
        genre: String?,
        year: Int?,
        trackNumber: Int?,
    ) {
        val t = trackDao.get(trackId) ?: return
        trackDao.update(
            t.copy(
                title = title.trim().ifEmpty { t.title },
                artist = artist.trim().ifEmpty { t.artist },
                album = album?.trim()?.takeIf { it.isNotEmpty() },
                albumArtist = albumArtist?.trim()?.takeIf { it.isNotEmpty() },
                genre = genre?.trim()?.takeIf { it.isNotEmpty() },
                year = year,
                trackNumber = trackNumber,
            ),
        )
    }

    /** Deletes the rows and the files (audio, lyrics, and the cover if no other track uses it). */
    /**
     * Takes songs off the phone (file, lyrics, unused cover, row). A liked song that came from
     * YouTube stays in Curtidas, streamed: removing a download is not unliking it.
     */
    suspend fun deleteTracks(ids: List<Long>) = withContext(Dispatchers.IO) {
        val doomed = trackDao.getMany(ids)
        doomed.filter { it.liked && !it.videoId.isNullOrBlank() }.forEach { t ->
            val videoId = t.videoId!!
            likedOnlineDao.insert(
                LikedOnline(
                    videoId = videoId,
                    url = t.sourceUrl?.takeIf { it.startsWith("http") } ?: YouTubeLinks.watchUrl(videoId, music = true),
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                    durationMs = t.durationMs.takeIf { it > 0 },
                    thumbnailUrl = db.downloads().thumbnailOf(videoId) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    likedAt = t.likedAt ?: System.currentTimeMillis(),
                ),
            )
        }
        val remainingCovers = trackDao.all().filter { it.id !in ids.toSet() }.mapNotNull { it.coverPath }.toSet()
        doomed.forEach { t ->
            trackDao.delete(t)
            runCatching { File(t.filePath).delete() }
            t.lyricsPath?.let { runCatching { File(it).delete() } }
            t.coverPath?.takeIf { it !in remainingCovers }?.let { runCatching { File(it).delete() } }
        }
    }

    // ---------------------------------------------------------------- search & stats

    fun search(query: String): Flow<LocalSearchResult> = combine(tracks, albums, artists, playlists, genres) { all, albums, artists, lists, genres ->
        val q = query.sortKey()
        if (q.isBlank()) return@combine LocalSearchResult(query, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        fun String?.hit() = this != null && this.sortKey().contains(q)
        LocalSearchResult(
            query = query,
            tracks = all.filter { it.title.hit() || it.artist.hit() || it.album.hit() }
                .sortedWith(compareByDescending<Track> { it.title.sortKey().startsWith(q) }.thenByDescending { it.playCount }),
            albums = albums.filter { it.title.hit() || it.artist.hit() },
            artists = artists.filter { it.name.hit() },
            playlists = lists.filter { it.name.hit() },
            genres = genres.filter { it.name.hit() },
        )
    }.flowOn(Dispatchers.Default)

    val stats: Flow<LibraryStats> = tracks.map { all ->
        LibraryStats(
            trackCount = all.size,
            durationMs = all.sumOf { it.durationMs },
            bytes = all.sumOf { it.fileSize },
            albumCount = all.mapNotNull { it.albumKey?.normalized }.distinct().size,
            artistCount = all.map { it.artist.trim().lowercase() }.distinct().size,
        )
    }.flowOn(Dispatchers.Default)

    companion object {
        /** How far back the listening is kept on the phone (the same as the profile's vault). */
        const val HISTORY_KEPT_MS = com.villamediana.sound.social.VaultMerge.PLAYS_KEPT_MS
        /** At most this many plays of each kind (library, streamed), the newest. */
        const val HISTORY_MAX_ROWS = 50_000

        fun groupAlbums(all: List<Track>): List<AlbumSummary> =
            all.filter { it.hasAlbum }.groupBy { it.albumKey!!.normalized }.values.map { group ->
                val first = group.minWith(compareBy<Track>({ it.trackNumber ?: Int.MAX_VALUE }, { it.addedAt }))
                AlbumSummary(
                    key = AlbumKey(group.mostCommon { it.albumArtistOrArtist.trim() }, group.mostCommon { it.album!!.trim() }),
                    coverPath = first.coverPath ?: group.firstNotNullOfOrNull { it.coverPath },
                    trackCount = group.size,
                    durationMs = group.sumOf { it.durationMs },
                    year = group.mapNotNull { it.year }.maxOrNull(),
                    genre = group.mapNotNull { it.genre }.takeIf { it.isNotEmpty() }?.let { gs -> gs.groupingBy { it }.eachCount().maxBy { it.value }.key },
                    addedAt = group.maxOf { it.addedAt },
                    lastPlayedAt = group.mapNotNull { it.lastPlayedAt }.maxOrNull(),
                    pinned = false,
                    accentColor = first.accentColor ?: group.firstNotNullOfOrNull { it.accentColor },
                )
            }.sortedByDescending { it.addedAt }

        fun sortTracks(list: List<Track>, sort: TrackSort): List<Track> = when (sort) {
            TrackSort.RECENT -> list.sortedByDescending { it.addedAt }
            TrackSort.TITLE -> list.sortedBy { it.title.sortKey() }
            TrackSort.ARTIST -> list.sortedWith(compareBy({ it.artist.sortKey() }, { it.album?.sortKey() ?: "" }, { it.trackNumber ?: 0 }))
            TrackSort.ALBUM -> list.sortedWith(compareBy({ it.album?.sortKey() ?: "￿" }, { it.trackNumber ?: 0 }))
            TrackSort.MOST_PLAYED -> list.sortedWith(compareByDescending<Track> { it.playCount }.thenByDescending { it.lastPlayedAt ?: 0 })
            TrackSort.DURATION -> list.sortedByDescending { it.durationMs }
        }
    }
}

/** Lowercase, accents stripped, leading articles kept — for sorting and accent-insensitive search. */
fun String.sortKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

private fun <T> List<T>.mostCommon(selector: (T) -> String): String =
    groupingBy(selector).eachCount().maxBy { it.value }.key

/** One song of an imported playlist, as stored. */
@kotlinx.serialization.Serializable
private data class ImportedSong(
    val videoId: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val catalog: Boolean = true,
)

private val importedJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Completed (≥ 90 %) / skipped (< 30 s or < 1/3) — the same rule for library and online listens. */
internal fun listenOutcome(listenedMs: Long, durationMs: Long): Pair<Boolean, Boolean> {
    val completed = durationMs > 0 && listenedMs >= durationMs * 0.9
    val skipped = !completed && (listenedMs < 30_000 || (durationMs > 0 && listenedMs < durationMs / 3))
    return completed to skipped
}

/** A streamed playlist row as the transient track the screens show and play. */
internal fun PlaylistOnline.toTrack(): Track =
    RemoteTrack(videoId, url, title, artist, album, durationMs, thumbnailUrl, catalog).asTransientTrack()

internal fun PlaylistOnline.Companion.of(playlistId: Long, song: RemoteTrack, position: Int) = PlaylistOnline(
    playlistId = playlistId,
    videoId = song.videoId,
    url = song.url,
    title = song.title,
    artist = song.artist,
    album = song.album,
    durationMs = song.durationMs,
    thumbnailUrl = song.thumbnailUrl,
    catalog = song.fromMusicCatalog,
    position = position,
)
