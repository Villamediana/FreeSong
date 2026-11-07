package com.villamediana.sound.discover

import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.YouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Live suggestions for the Home: genre covers for the picker, and any list of songs or
 * playlists the Home shows (genre shelves, mixes, trending, "porque você ouviu…"). Everything is
 * cached on disk — covers for a week, lists for their 6-hour slot — so the Home opens instantly,
 * changes through the day and still shows the last lists offline.
 */
class DiscoverRepository(
    private val youTube: YouTubeSource,
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Song(
        val videoId: String,
        val url: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
        val thumbnailUrl: String? = null,
        val catalog: Boolean = true,
    )

    @Serializable
    private data class Col(
        val kind: String,
        val url: String,
        val title: String,
        val author: String? = null,
        val thumbnailUrl: String? = null,
        val itemCount: Long? = null,
        val year: Int? = null,
    )

    @Serializable
    private data class Entry(val slot: Long, val songs: List<Song> = emptyList(), val cols: List<Col> = emptyList())

    @Serializable
    private data class Cache(
        val covers: Map<String, List<String>> = emptyMap(),
        val coversAt: Map<String, Long> = emptyMap(),
        val lists: Map<String, Entry> = emptyMap(),
        /** Artist name (lowercase) → its page, found once. */
        val artists: Map<String, Col> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val gate = Semaphore(4)
    private var cache: Cache? = null

    /** Forgets every list and cover (Ajustes › Armazenamento): the Home asks again. */
    suspend fun clear() = lock.withLock {
        cache = Cache()
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    /** Bytes kept on disk. */
    fun bytes(): Long = file.takeIf { it.isFile }?.length() ?: 0L

    private suspend fun load(): Cache = cache ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Cache.serializer(), file.readText()) }.getOrDefault(Cache())
    }.also { cache = it }

    private suspend fun update(change: (Cache) -> Cache) = lock.withLock {
        val next = change(load())
        cache = next
        withContext(Dispatchers.IO) { runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(Cache.serializer(), next)) } }
    }

    private fun slot(): Long = GenreCatalog.slotOf(clock())

    // ------------------------------------------------------------------ covers

    /** Up to 4 cover urls per genre (cached for a week; stale ones are refreshed when [refresh]). */
    suspend fun covers(genres: List<GenreDef>, refresh: Boolean = true): Map<String, List<String>> {
        val now = clock()
        val c = lock.withLock { load() }
        val stale = if (refresh) genres.filter { g -> (c.coversAt[g.id] ?: 0L) < now - WEEK_MS || c.covers[g.id].isNullOrEmpty() } else emptyList()
        if (stale.isEmpty()) return c.covers
        val fetched = coroutineScope {
            stale.map { g -> async { g.id to runCatching { gate.withPermit { searchSongs(g.query, 8) } }.getOrNull() } }.awaitAll()
        }.mapNotNull { (id, songs) -> songs?.mapNotNull { it.thumbnailUrl }?.distinct()?.take(4)?.takeIf { it.isNotEmpty() }?.let { id to it } }
        if (fetched.isEmpty()) return c.covers
        update { cur -> cur.copy(covers = cur.covers + fetched, coversAt = cur.coversAt + fetched.associate { it.first to now }) }
        return lock.withLock { load() }.covers
    }

    // ------------------------------------------------------------------ songs

    /**
     * Songs for [query], cached under [key] for the current slot. Offline / on failure: the last
     * list cached under [key] (any slot).
     */
    suspend fun songs(key: String, query: String, size: Int = SHELF_SIZE, force: Boolean = false): List<RemoteTrack> =
        cachedOrFetch(key, force, { it.songs.map { s -> s.toRemote() } }) {
            Entry(slot(), songs = gate.withPermit { searchSongs(query, size) })
        }

    /** "Up next" radio of a song (YouTube Music), cached per song and slot. */
    suspend fun related(videoId: String, size: Int = SHELF_SIZE): List<RemoteTrack> =
        cachedOrFetch("related:$videoId", false, { it.songs.map { s -> s.toRemote() } }) {
            val list = gate.withPermit { youTube.related(videoId, size + 4) }
                .filter { it.videoId != videoId && (it.durationMs ?: 0L) <= MAX_SONG_MS }
                .distinctBy { it.videoId }.take(size)
            Entry(slot(), songs = list.map { it.toSong() })
        }

    /**
     * A list made once for [day] under [key] (the made-for-you mixes' new songs): the same all day,
     * remade the next. Offline / on failure: the last one made.
     */
    suspend fun daily(key: String, day: Long, make: suspend () -> List<RemoteTrack>): List<RemoteTrack> {
        val cached = lock.withLock { load() }.lists[key]
        if (cached != null && cached.slot == day) return cached.songs.map { it.toRemote() }
        val fresh = runCatching { make() }.getOrNull().orEmpty()
        if (fresh.isEmpty()) return cached?.songs?.map { it.toRemote() }.orEmpty()
        update { it.copy(lists = it.lists + (key to Entry(day, songs = fresh.map { s -> s.toSong() }))) }
        return fresh
    }

    /** Albums/playlists for [query] ([filter] ALBUMS or PLAYLISTS), cached like [songs]. */
    suspend fun collections(key: String, query: String, filter: SearchFilter, size: Int = 6): List<RemoteCollection> =
        cachedOrFetch(key, false, { it.cols.mapNotNull { c -> c.toRemote() } }) {
            val found = gate.withPermit { youTube.search(query, filter).items }
                .filterIsInstance<SearchItem.Collection>().map { it.collection }
                .distinctBy { it.url }.take(size)
            Entry(slot(), cols = found.map { it.toCol() })
        }

    /** The page of an artist by name (searched once, then kept). */
    suspend fun artist(name: String): RemoteCollection? {
        val k = name.trim().lowercase()
        lock.withLock { load() }.artists[k]?.toRemote()?.let { return it }
        val found = runCatching {
            gate.withPermit { youTube.search(name, SearchFilter.ARTISTS).items }
                .filterIsInstance<SearchItem.Collection>().map { it.collection }
                .firstOrNull { it.kind == CollectionKind.ARTIST }
        }.getOrNull() ?: return null
        update { it.copy(artists = it.artists + (k to found.toCol())) }
        return found
    }

    /** The online page of [album] by [artist] (YouTube Music), or null when no album has that name. */
    suspend fun album(album: String, artist: String): RemoteCollection? {
        val want = com.villamediana.sound.online.CoverMatch.key(album)
        if (want.isEmpty()) return null
        val found = runCatching {
            gate.withPermit { youTube.search("$album $artist".trim(), SearchFilter.ALBUMS).items }
                .filterIsInstance<SearchItem.Collection>().map { it.collection }
                .filter { it.kind == CollectionKind.ALBUM }
        }.getOrNull().orEmpty()
        return found.firstOrNull { com.villamediana.sound.online.CoverMatch.key(it.title) == want }
            ?: found.firstOrNull { val k = com.villamediana.sound.online.CoverMatch.key(it.title); k.startsWith(want) || want.startsWith(k) }
    }

    /** What is cached under [key] right now, without the network (instant first paint). */
    suspend fun cachedSongs(key: String): List<RemoteTrack> = lock.withLock { load() }.lists[key]?.songs.orEmpty().map { it.toRemote() }

    suspend fun cachedCollections(key: String): List<RemoteCollection> = lock.withLock { load() }.lists[key]?.cols.orEmpty().mapNotNull { it.toRemote() }

    // ------------------------------------------------------------------ genre shelves (kept API)

    suspend fun shelf(genre: GenreDef, force: Boolean = false): List<RemoteTrack> =
        songs(shelfKey(genre), GenreCatalog.liveQuery(genre, slot()), SHELF_SIZE, force)

    suspend fun cachedShelf(genre: GenreDef): List<RemoteTrack> = cachedSongs(shelfKey(genre))

    private fun shelfKey(genre: GenreDef) = "shelf:" + genre.id

    // ------------------------------------------------------------------ internals

    private suspend fun <T> cachedOrFetch(key: String, force: Boolean, read: (Entry) -> List<T>, fetch: suspend () -> Entry): List<T> {
        val cached = lock.withLock { load() }.lists[key]
        if (!force && cached != null && cached.slot == slot()) return read(cached)
        val fresh = runCatching { fetch() }.getOrNull()
        if (fresh == null || (fresh.songs.isEmpty() && fresh.cols.isEmpty())) return cached?.let(read).orEmpty()
        update { it.copy(lists = it.lists + (key to fresh)) }
        return read(fresh)
    }

    private suspend fun searchSongs(query: String, limit: Int): List<Song> =
        youTube.search(query, SearchFilter.SONGS).items
            .filterIsInstance<SearchItem.Song>()
            .map { it.track }
            // Songs, not hour-long DJ mixes ("Enganchado", "Mix 2026"…).
            .filter { (it.durationMs ?: 0L) <= MAX_SONG_MS }
            .distinctBy { it.videoId }
            .take(limit)
            .map { it.toSong() }

    private fun RemoteTrack.toSong() = Song(videoId, url, title, artist, album, durationMs, thumbnailUrl, fromMusicCatalog)
    private fun Song.toRemote() = RemoteTrack(videoId, url, title, artist, album, durationMs, thumbnailUrl, catalog)
    private fun RemoteCollection.toCol() = Col(kind.name, url, title, author, thumbnailUrl, itemCount, year)
    private fun Col.toRemote(): RemoteCollection? =
        runCatching { CollectionKind.valueOf(kind) }.getOrNull()?.let { RemoteCollection(it, url, title, author, thumbnailUrl, itemCount, year) }

    private companion object {
        const val WEEK_MS = 7L * 24 * 3_600_000L
        const val SHELF_SIZE = 16
        const val MAX_SONG_MS = 9L * 60_000L
    }
}
