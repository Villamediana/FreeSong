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
 * changes through the day and still shows the last lists offline. Genre shelves and playlists are
 * drawn from a pool per genre, a new draw each session.
 */
class DiscoverRepository(
    private val youTube: YouTubeSource,
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Varies the Home's draws from one app start to the next. */
    private val session: Long = kotlin.random.Random.nextLong(),
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
        // Kept per size: a short radio saved for the Home must not cut a longer one asked later.
        cachedOrFetch(if (size == SHELF_SIZE) "related:$videoId" else "related:$videoId:$size", false, { it.songs.map { s -> s.toRemote() } }) {
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

    /** The artist's page if it was found before, from memory (no waiting): their picture shows at once. */
    fun peekArtist(name: String): RemoteCollection? = cache?.artists?.get(name.trim().lowercase())?.toRemote()

    /** The page of an artist by name (searched once, then kept). */
    suspend fun artist(name: String): RemoteCollection? = runCatching { findArtist(name) }.getOrNull()

    /** Like [artist], but a failed search throws: a page can tell "offline / try again" from "not found". */
    suspend fun findArtist(name: String): RemoteCollection? {
        val k = name.trim().lowercase()
        if (k.isEmpty()) return null
        lock.withLock { load() }.artists[k]?.toRemote()?.let { return it }
        val found = gate.withPermit { youTube.search(name, SearchFilter.ARTISTS).items }
            .filterIsInstance<SearchItem.Collection>().map { it.collection }
            .firstOrNull { it.kind == CollectionKind.ARTIST } ?: return null
        update { it.copy(artists = it.artists + (k to found.toCol())) }
        return found
    }

    /** The online page of [album] by [artist] (YouTube Music), or null when no album has that name. */
    suspend fun album(album: String, artist: String): RemoteCollection? = runCatching { findAlbum(album, artist) }.getOrNull()

    /**
     * Like [album], but a failed search throws. An album credited to [artist] wins over one that
     * only shares the name; with [sameArtist] only those count.
     */
    suspend fun findAlbum(album: String, artist: String, sameArtist: Boolean = false): RemoteCollection? {
        val want = com.villamediana.sound.online.CoverMatch.key(album)
        if (want.isEmpty()) return null
        val found = gate.withPermit { youTube.search("$album $artist".trim(), SearchFilter.ALBUMS).items }
            .filterIsInstance<SearchItem.Collection>().map { it.collection }
            .filter { it.kind == CollectionKind.ALBUM }
        return AlbumPick.best(found, album, artist, sameArtist)
    }

    /** What is cached under [key] right now, without the network (instant first paint). */
    suspend fun cachedSongs(key: String): List<RemoteTrack> = lock.withLock { load() }.lists[key]?.songs.orEmpty().map { it.toRemote() }

    suspend fun cachedCollections(key: String): List<RemoteCollection> = lock.withLock { load() }.lists[key]?.cols.orEmpty().mapNotNull { it.toRemote() }

    // ------------------------------------------------------------------ genre shelves and playlists

    /**
     * A genre's shelf ("Rock pra você"), drawn fresh from the genre's pool: every 6-hour slot
     * searches the genre in two wordings and adds what it finds to the pool (the newest
     * [POOL_SONGS] kept); every session — opening the app, or half an hour later — draws another
     * shelf from it, half from this slot's songs and half from the earlier ones. It changes often
     * and stays in the genre. Offline: drawn from what is cached.
     */
    suspend fun shelf(genre: GenreDef, force: Boolean = false): List<RemoteTrack> {
        val fresh = cachedOrFetch(shelfKey(genre), force, { it.songs.map { s -> s.toRemote() } }) {
            val found = coroutineScope {
                GenreCatalog.liveQueries(genre, slot()).map { q -> async { runCatching { gate.withPermit { searchSongs(q, SHELF_FETCH) } }.getOrDefault(emptyList()) } }.awaitAll()
            }
            Entry(slot(), songs = found.flatten().distinctBy { it.videoId })
        }
        val pool = poolSongs("pool:" + genre.id, fresh)
        return draw(fresh, pool, SHELF_SIZE, "shelf:" + genre.id) { it.videoId }
    }

    suspend fun cachedShelf(genre: GenreDef): List<RemoteTrack> = cachedSongs(shelfKey(genre))

    /**
     * Everything the genre's shelf can draw from: this slot's songs (searched like [shelf]) and the
     * pool of the earlier ones. The listener's order is put on it elsewhere ([ForYou.shelf]).
     */
    suspend fun shelfPool(genre: GenreDef, force: Boolean = false): List<RemoteTrack> {
        val fresh = cachedOrFetch(shelfKey(genre), force, { it.songs.map { s -> s.toRemote() } }) {
            val found = coroutineScope {
                GenreCatalog.liveQueries(genre, slot()).map { q -> async { runCatching { gate.withPermit { searchSongs(q, SHELF_FETCH) } }.getOrDefault(emptyList()) } }.awaitAll()
            }
            Entry(slot(), songs = found.flatten().distinctBy { it.videoId })
        }
        return (fresh + poolSongs("pool:" + genre.id, fresh)).distinctBy { it.videoId }
    }

    private fun shelfKey(genre: GenreDef) = "shelf:" + genre.id

    /** "Playlists pra você" of a genre: drawn from its pool like [shelf], with the wording rotating each slot. */
    suspend fun playlists(genre: GenreDef, size: Int = 3, force: Boolean = false): List<RemoteCollection> {
        val fresh = cachedOrFetch("playlists:" + genre.id, force, { it.cols.mapNotNull { c -> c.toRemote() } }) {
            val found = gate.withPermit { youTube.search(GenreCatalog.playlistQuery(genre, slot()), SearchFilter.PLAYLISTS).items }
                .filterIsInstance<SearchItem.Collection>().map { it.collection }
                .filter { it.kind == CollectionKind.PLAYLIST }
                .distinctBy { it.url }.take(PLAYLIST_FETCH)
            Entry(slot(), cols = found.map { it.toCol() })
        }
        val pool = poolCols("plpool:" + genre.id, fresh)
        return draw(fresh, pool, size, "playlists:" + genre.id) { it.url }
    }

    private suspend fun poolSongs(key: String, fresh: List<RemoteTrack>): List<RemoteTrack> {
        val current = lock.withLock { load() }.lists[key]?.songs.orEmpty()
        val merged = (fresh.map { it.toSong() } + current).distinctBy { it.videoId }.take(POOL_SONGS)
        if (merged != current) update { it.copy(lists = it.lists + (key to Entry(slot(), songs = merged))) }
        return merged.map { it.toRemote() }
    }

    private suspend fun poolCols(key: String, fresh: List<RemoteCollection>): List<RemoteCollection> {
        val current = lock.withLock { load() }.lists[key]?.cols.orEmpty()
        val merged = (fresh.map { it.toCol() } + current).distinctBy { it.url }.take(POOL_COLS)
        if (merged != current) update { it.copy(lists = it.lists + (key to Entry(slot(), cols = merged))) }
        return merged.mapNotNull { it.toRemote() }
    }

    /** Pulled down to refresh: the next draws are new ones, even with nothing new from the network. */
    fun reshuffle() {
        draws++
    }

    @Volatile private var draws = 0L

    /** The seed of this session's draws under [key]: new at every app start, every half hour and after a pull to refresh. */
    fun drawSeed(key: String): Long = session * 31 + (clock() / SESSION_MS) * 17 + draws * 1_000_003 + key.hashCode()

    /**
     * New songs to recommend next to some of yours ([seeds], video ids): their radios compared, the
     * ones they agree on first (the same pick as the made-for-you mixes), none you have.
     */
    suspend fun recommend(seeds: List<String>, inLibrary: Set<String>, count: Int): List<RemoteTrack> {
        if (seeds.isEmpty()) return emptyList()
        val radios = coroutineScope {
            seeds.map { id -> async { runCatching { related(id, RECOMMEND_RADIO) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        return com.villamediana.sound.smart.MadeForYou.pickNew(radios, inLibrary + seeds, count)
    }

    /** [size] items: half from [fresh], the rest from the [pool] around it, mixed — another draw each session. */
    private fun <T> draw(fresh: List<T>, pool: List<T>, size: Int, key: String, id: (T) -> String): List<T> {
        val rnd = kotlin.random.Random(drawSeed(key))
        val freshIds = fresh.mapTo(HashSet(), id)
        val now = fresh.shuffled(rnd).take((size + 1) / 2)
        val before = pool.filter { id(it) !in freshIds }.shuffled(rnd).take(size - now.size)
        // A pool still young (the first slot): the rest from this slot too.
        val fill = fresh.filter { it !in now }.shuffled(rnd).take(size - now.size - before.size)
        return (now + before + fill).shuffled(rnd)
    }

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
        /** Songs asked per wording of a genre (two wordings a slot). */
        const val SHELF_FETCH = 24
        const val PLAYLIST_FETCH = 10
        /** Songs asked of each radio behind a page's recommendations. */
        const val RECOMMEND_RADIO = 30
        const val POOL_SONGS = 120
        const val POOL_COLS = 40
        /** A new draw of the Home's shelves at most this often (and at every app start). */
        const val SESSION_MS = 30L * 60_000L
        const val MAX_SONG_MS = 9L * 60_000L
    }
}
