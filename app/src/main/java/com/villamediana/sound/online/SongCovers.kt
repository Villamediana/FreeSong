package com.villamediana.sound.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

/**
 * The album cover of songs that came as video uploads (playlists, the YouTube side of a search):
 * their thumbnail is a frame of the video. The same song is looked up in the YouTube Music catalog
 * (square album art) — lazily, for what is on screen, two at a time — and the answer is kept on disk
 * (also "none", so a song without a catalog match isn't asked again).
 */
class SongCovers(
    private val youTube: () -> YouTubeSource,
    private val file: File,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** videoId → album cover url ("" = looked up, no match). */
    private val _found = MutableStateFlow<Map<String, String>>(emptyMap())
    val found: StateFlow<Map<String, String>> = _found.asStateFlow()

    private val gate = Semaphore(2)
    private val asked = HashSet<String>()
    private var loaded = false
    private var saveJob: Job? = null

    /** The album cover found for [videoId], if any. */
    fun coverFor(videoId: String): String? {
        load()
        return _found.value[videoId]?.takeIf { it.isNotEmpty() }
    }

    /** Looks [title] by [artist] up once (no-op when known or on its way). */
    fun request(videoId: String, title: String, artist: String) {
        synchronized(asked) {
            if (!asked.add(videoId)) return
        }
        scope.launch(Dispatchers.IO) {
            load()
            if (_found.value.containsKey(videoId)) return@launch
            val cover = gate.withPermit { runCatching { lookUp(title, artist) }.getOrNull() } ?: run {
                synchronized(asked) { asked.remove(videoId) } // a failed request may be tried again later
                return@launch
            }
            _found.update { it + (videoId to cover) }
            scheduleSave()
        }
    }

    /** The catalog's square art for the song, "" when no result is that song. Null = request failed. */
    private suspend fun lookUp(title: String, artist: String): String? {
        val want = CoverMatch.key(title)
        if (want.isEmpty()) return ""
        val items = youTube().search("$artist $title".trim(), SearchFilter.SONGS).items
        val songs = items.filterIsInstance<SearchItem.Song>().map { it.track }
        val hit = songs.take(6).firstOrNull { CoverMatch.same(title, artist, it.title, it.artist) && !it.thumbnailUrl.isNullOrBlank() }
        return hit?.thumbnailUrl.orEmpty()
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val saved = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
            _found.update { saved + it }
            loaded = true
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1_500)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(serializer, _found.value))
            }
        }
    }
}

/** Whether a catalog result is the very song: same title (ignoring "(feat …)", accents, case) and artist. */
object CoverMatch {
    fun key(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("\\((feat|ft|with)[^)]*\\)"), "")
        .replace(Regex("[^a-z0-9]+"), "")

    fun same(title: String, artist: String, candidateTitle: String, candidateArtist: String): Boolean {
        val t = key(title)
        val ct = key(candidateTitle)
        if (t.isEmpty() || ct.isEmpty()) return false
        val titleOk = t == ct || (t.length >= 4 && ct.startsWith(t)) || (ct.length >= 4 && t.startsWith(ct))
        if (!titleOk) return false
        val a = key(artist.split(",", "&", " feat", " ft.", " x ", " e ", " y ").first())
        val ca = key(candidateArtist)
        return a.isEmpty() || (ca.isNotEmpty() && (ca.contains(a) || a.contains(ca)))
    }
}
