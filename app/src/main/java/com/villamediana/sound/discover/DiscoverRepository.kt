package com.villamediana.sound.discover

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
 * Live suggestions for the Home: genre covers for the picker and a shelf of songs per favourite
 * genre. Everything is cached on disk (covers for a week, shelves for their 6-hour slot), so the
 * Home opens instantly and offline shows the last shelves.
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
    private data class Cache(
        val covers: Map<String, List<String>> = emptyMap(),
        val coversAt: Map<String, Long> = emptyMap(),
        val shelves: Map<String, List<Song>> = emptyMap(),
        /** genre id → slot the shelf was fetched for. */
        val shelvesSlot: Map<String, Long> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val gate = Semaphore(4)
    private var cache: Cache? = null

    private suspend fun load(): Cache = cache ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Cache.serializer(), file.readText()) }.getOrDefault(Cache())
    }.also { cache = it }

    private suspend fun save(c: Cache) {
        cache = c
        withContext(Dispatchers.IO) { runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(Cache.serializer(), c)) } }
    }

    /** Up to 4 cover urls per genre (cached for a week; stale ones are refreshed when online). */
    suspend fun covers(genres: List<GenreDef>, refresh: Boolean = true): Map<String, List<String>> {
        val now = clock()
        val c = lock.withLock { load() }
        val stale = if (refresh) genres.filter { g -> (c.coversAt[g.id] ?: 0L) < now - WEEK_MS || c.covers[g.id].isNullOrEmpty() } else emptyList()
        if (stale.isEmpty()) return c.covers
        val fetched = coroutineScope {
            stale.map { g -> async { g.id to runCatching { gate.withPermit { songs(g.query, 8) } }.getOrNull() } }.awaitAll()
        }.mapNotNull { (id, songs) -> songs?.mapNotNull { it.thumbnailUrl }?.distinct()?.take(4)?.takeIf { it.isNotEmpty() }?.let { id to it } }
        if (fetched.isEmpty()) return c.covers
        return lock.withLock {
            val cur = load()
            val next = cur.copy(covers = cur.covers + fetched, coversAt = cur.coversAt + fetched.associate { it.first to now })
            save(next)
            next.covers
        }
    }

    /** Songs for a favourite genre in the current slot; cached shelf when offline or on failure. */
    suspend fun shelf(genre: GenreDef, force: Boolean = false): List<RemoteTrack> {
        val slot = GenreCatalog.slotOf(clock())
        val c = lock.withLock { load() }
        val cached = c.shelves[genre.id]
        if (!force && cached != null && c.shelvesSlot[genre.id] == slot) return cached.map { it.toRemote() }
        val fresh = runCatching { gate.withPermit { songs(GenreCatalog.liveQuery(genre, slot), SHELF_SIZE) } }.getOrNull()
        if (fresh.isNullOrEmpty()) return cached.orEmpty().map { it.toRemote() }
        lock.withLock {
            val cur = load()
            save(cur.copy(shelves = cur.shelves + (genre.id to fresh), shelvesSlot = cur.shelvesSlot + (genre.id to slot)))
        }
        return fresh.map { it.toRemote() }
    }

    /** Last shelves on disk, without touching the network (instant first paint). */
    suspend fun cachedShelf(genre: GenreDef): List<RemoteTrack> = lock.withLock { load() }.shelves[genre.id].orEmpty().map { it.toRemote() }

    private suspend fun songs(query: String, limit: Int): List<Song> =
        youTube.search(query, SearchFilter.SONGS).items
            .filterIsInstance<SearchItem.Song>()
            .map { it.track }
            // Songs, not hour-long DJ mixes ("Enganchado", "Mix 2026"…).
            .filter { (it.durationMs ?: 0L) <= MAX_SONG_MS }
            .distinctBy { it.videoId }
            .take(limit)
            .map { Song(it.videoId, it.url, it.title, it.artist, it.album, it.durationMs, it.thumbnailUrl, it.fromMusicCatalog) }

    private fun Song.toRemote() = RemoteTrack(videoId, url, title, artist, album, durationMs, thumbnailUrl, catalog)

    private companion object {
        const val WEEK_MS = 7L * 24 * 3_600_000L
        const val SHELF_SIZE = 16
        const val MAX_SONG_MS = 9L * 60_000L
    }
}
