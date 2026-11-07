package com.villamediana.sound.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.min

/** Finds a Spotify song among YouTube Music results: same title (versions aside) by the same artist. */
object SpotifyMatch {
    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")

    /** "Dreams - 2004 Remaster" / "Dreams (2004 Remaster)" / "Song (feat. X)" → "Dreams" / "Song". */
    fun baseTitle(title: String): String = title.substringBefore(" - ").replace(BRACKETS, "").trim().ifEmpty { title.trim() }

    fun artists(artist: String): List<String> = artist.split(",", "&", " feat.", " ft.", " x ", " e ", " y ").map { it.trim() }.filter { it.isNotEmpty() }

    /** What to search: the bare title and the first artist. */
    fun query(t: SpotifyTrack): String = listOfNotNull(baseTitle(t.title), artists(t.artist).firstOrNull()).joinToString(" ")

    /**
     * The best candidate, or null when none is the same song: the title must be the same (or one
     * the start of the other: "Song" / "Song Remastered") and one of the artists must be in it.
     * Exact titles first, then the one closest in length to the Spotify one.
     */
    fun pick(t: SpotifyTrack, candidates: List<RemoteTrack>): RemoteTrack? {
        val title = CoverMatch.key(baseTitle(t.title))
        if (title.isEmpty()) return null
        val names = artists(t.artist).map(CoverMatch::key).filter { it.isNotEmpty() }
        data class Scored(val track: RemoteTrack, val exact: Boolean, val closeLength: Boolean)
        val ok = candidates.mapNotNull { c ->
            val ct = CoverMatch.key(baseTitle(c.title))
            if (ct.isEmpty()) return@mapNotNull null
            val exact = ct == title
            val prefix = min(ct.length, title.length) >= 4 && (ct.startsWith(title) || title.startsWith(ct))
            if (!exact && !prefix) return@mapNotNull null
            val ca = CoverMatch.key(c.artist)
            val artistOk = names.isEmpty() || (ca.isNotEmpty() && names.any { a -> ca.contains(a) || a.contains(ca) })
            if (!artistOk) return@mapNotNull null
            val d = t.durationMs
            val close = d == null || c.durationMs == null || abs(c.durationMs - d) <= 15_000
            Scored(c, exact, close)
        }
        return (ok.firstOrNull { it.exact && it.closeLength } ?: ok.firstOrNull { it.exact } ?: ok.firstOrNull { it.closeLength } ?: ok.firstOrNull())?.track
    }
}

/**
 * Brings a Spotify playlist in: each song is looked up on YouTube Music, then the found ones become
 * a local playlist with the same name, downloaded in the Spotify order (like saving a YouTube
 * playlist). Runs on the app's scope: closing the sheet does not stop it.
 */
class SpotifyImporter(
    private val search: suspend (String) -> List<RemoteTrack>,
    private val save: suspend (CollectionDetail) -> Long?,
    private val scope: CoroutineScope,
    private val parallel: Int = 3,
) {
    sealed interface State {
        data object Idle : State
        data class Matching(val playlist: SpotifyPlaylist, val done: Int, val found: Int) : State
        data class Done(val playlist: SpotifyPlaylist, val playlistId: Long?, val found: Int, val missing: List<SpotifyTrack>) : State

        /** Nothing found ([offline]: no search answered). */
        data class Failed(val playlist: SpotifyPlaylist, val offline: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null

    fun start(playlist: SpotifyPlaylist) {
        if (_state.value is State.Matching) return
        _state.value = State.Matching(playlist, 0, 0)
        job = scope.launch { _state.value = run(playlist) }
    }

    /** Back to the start (not while it runs). */
    fun reset() {
        if (_state.value !is State.Matching) _state.value = State.Idle
    }

    private suspend fun run(playlist: SpotifyPlaylist): State {
        val gate = Semaphore(parallel)
        var failures = 0
        val matches: List<RemoteTrack?> = coroutineScope {
            playlist.tracks.map { t ->
                async {
                    gate.withPermit {
                        val found = runCatching { SpotifyMatch.pick(t, search(SpotifyMatch.query(t)).take(8)) }
                            .onFailure { synchronized(this@SpotifyImporter) { failures++ } }
                            .getOrNull()
                        _state.update { s ->
                            if (s is State.Matching) s.copy(done = s.done + 1, found = s.found + if (found != null) 1 else 0) else s
                        }
                        found
                    }
                }
            }.awaitAll()
        }
        val found = matches.filterNotNull().distinctBy { it.videoId }
        if (found.isEmpty()) return State.Failed(playlist, offline = failures == playlist.tracks.size && failures > 0)
        val missing = playlist.tracks.filterIndexed { i, _ -> matches[i] == null }
        val id = runCatching {
            save(
                CollectionDetail(
                    collection = RemoteCollection(
                        kind = CollectionKind.PLAYLIST,
                        url = playlist.url,
                        title = playlist.name,
                        author = playlist.owner,
                        thumbnailUrl = playlist.coverUrl,
                        itemCount = found.size.toLong(),
                    ),
                    tracks = found,
                ),
            )
        }.getOrElse { return State.Failed(playlist, offline = false) }
        return State.Done(playlist, id, found.size, missing)
    }
}
