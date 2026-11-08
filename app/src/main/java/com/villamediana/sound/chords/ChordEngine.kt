package com.villamediana.sound.chords

import com.villamediana.sound.data.db.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** The song whose chords are wanted: its audio is the stream of [videoId] or the file at [path]. */
data class ChordSubject(
    val key: String,
    val videoId: String?,
    val path: String?,
    val durationMs: Long,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    /** The song's own lyrics file, when it has one. */
    val lyricsPath: String? = null,
) {
    companion object {
        /**
         * The same recording shares one hearing: a song downloaded from a video is that video's
         * audio, so it is keyed by the video. Files from elsewhere go by their path and size.
         */
        fun of(track: Track): ChordSubject? {
            val videoId = track.videoId?.takeIf { it.isNotBlank() }
            val path = track.filePath.takeIf { it.isNotBlank() && !it.startsWith("http") && File(it).isFile }
            return when {
                videoId != null -> ChordSubject("v_" + videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }, videoId, path, track.durationMs, track.title, track.artist, track.album, track.lyricsPath)
                path != null -> ChordSubject("f_" + sha1("$path|${track.fileSize}"), null, path, track.durationMs, track.title, track.artist, track.album, track.lyricsPath)
                else -> null
            }
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}

/** Where a song's chords stand. */
sealed interface ChordState {
    data object Idle : ChordState
    /** [looking]: asking for a written sheet, before anything is heard. */
    data class Working(val progress: Float, val looking: Boolean = false) : ChordState
    data class Ready(val chart: ChordChart) : ChordState
    data class Failed(val reason: Reason) : ChordState

    enum class Reason { OFFLINE, TOO_LONG, NOTHING_HEARD, ERROR }
}

class ChordFailure(val reason: ChordState.Reason, cause: Throwable? = null) : Exception(reason.name, cause)

/** Charts heard before, one JSON file per song, the least recently used going past [maxFiles]. */
class ChordStore(private val dir: File, private val maxFiles: Int = 400) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(key: String): ChordChart? {
        val f = File(dir, "$key.json")
        if (!f.isFile) return null
        val chart = runCatching { json.decodeFromString(ChordChart.serializer(), f.readText()) }.getOrNull()
        if (chart == null || chart.version != ChordChart.VERSION) {
            f.delete()
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        return chart
    }

    fun write(key: String, chart: ChordChart) {
        dir.mkdirs()
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(json.encodeToString(ChordChart.serializer(), chart))
        tmp.renameTo(File(dir, "$key.json"))
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (files.size > maxFiles) files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    fun bytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }
}

/**
 * Gets the chords of the songs asked for, one at a time on a background thread, and keeps them:
 * on disk (a song is done once) and in memory for the session. A sheet written by a player is
 * asked for first ([find]); only a song without one is heard ([analyze]). Asking for another song
 * while one is on its way drops the first — the listener moved on.
 */
class ChordEngine(
    private val scope: CoroutineScope,
    private val store: ChordStore,
    private val worker: CoroutineDispatcher,
    /** Hears one song: the chart, or a [ChordFailure]. Reports 0 → 1. */
    private val analyze: suspend (ChordSubject, (Float) -> Unit) -> ChordChart,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Looks for a written sheet of the song; none set, songs are only heard. */
    private val find: (suspend (ChordSubject) -> SheetLookup)? = null,
) {
    private val states = MutableStateFlow<Map<String, ChordState>>(emptyMap())
    private val lock = Mutex()
    private var running: Pair<String, Job>? = null

    /** Songs heard without an answer about a sheet whose sheet was asked for again this session. */
    private val askedAgain = HashSet<String>()

    fun state(key: String): Flow<ChordState> = states.map { it[key] ?: ChordState.Idle }.distinctUntilChanged()

    fun current(key: String): ChordState = states.value[key] ?: ChordState.Idle

    /**
     * Makes sure [subject]'s chords are on their way: already known, read from disk, or heard now.
     * A failure stays until [force] (the "try again", or the network coming back).
     */
    fun request(subject: ChordSubject, force: Boolean = false) {
        scope.launch {
            lock.withLock {
                when (val now = current(subject.key)) {
                    is ChordState.Ready -> {
                        askAgain(subject, now.chart)
                        return@withLock
                    }
                    is ChordState.Working -> return@withLock
                    is ChordState.Failed -> if (!force) return@withLock
                    ChordState.Idle -> Unit
                }
                withContext(io) { runCatching { store.read(subject.key) }.getOrNull() }?.let { chart ->
                    set(subject.key, ChordState.Ready(chart))
                    askAgain(subject, chart)
                    return@withLock
                }
                running?.let { (key, job) ->
                    if (key == subject.key && job.isActive) {
                        // Already being heard (its state was dropped from memory meanwhile).
                        set(key, ChordState.Working(0f))
                        return@withLock
                    }
                    if (key != subject.key) {
                        job.cancel()
                        if (current(key) is ChordState.Working) set(key, ChordState.Idle)
                    }
                }
                set(subject.key, ChordState.Working(0f, looking = find != null))
                val job = scope.launch(worker) { hear(subject) }
                running = subject.key to job
            }
        }
    }

    /**
     * A chart heard while the sheet couldn't be asked for (no connection then): asked again, once a
     * session, while the chords are open; a sheet found takes its place. Call with [lock] held.
     */
    private fun askAgain(subject: ChordSubject, chart: ChordChart) {
        val find = find ?: return
        if (chart.sheet != null || chart.looked || !askedAgain.add(subject.key)) return
        running?.let { (key, job) ->
            if (job.isActive) {
                if (key == subject.key) return
                job.cancel()
                if (current(key) is ChordState.Working) set(key, ChordState.Idle)
            }
        }
        val job = scope.launch(worker) {
            val me = currentCoroutineContext()[Job]
            try {
                when (val found = ask(find, subject)) {
                    is SheetLookup.Found -> {
                        runCatching { store.write(subject.key, found.chart) }
                        if (currentCoroutineContext().isActive) set(subject.key, ChordState.Ready(found.chart))
                    }
                    SheetLookup.Missing -> runCatching { store.write(subject.key, chart.copy(looked = true)) }
                    SheetLookup.Unreachable -> lock.withLock { askedAgain.remove(subject.key) }
                }
            } finally {
                lock.withLock { if (running?.second === me) running = null }
            }
        }
        running = subject.key to job
    }

    private suspend fun ask(find: suspend (ChordSubject) -> SheetLookup, subject: ChordSubject): SheetLookup = try {
        find(subject)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        SheetLookup.Unreachable
    }

    /** Stops hearing [key] if it is being heard (the chords were closed): nothing runs unseen. */
    fun stop(key: String) {
        scope.launch {
            lock.withLock {
                val (k, job) = running ?: return@withLock
                if (k != key) return@withLock
                job.cancel()
                running = null
                // A sheet being asked for again over a chart already shown leaves that chart.
                if (current(k) is ChordState.Working) set(k, ChordState.Idle)
            }
        }
    }

    /** Brings back [subject]'s chords if they were heard before. Never hears anything. */
    fun recall(subject: ChordSubject) {
        if (current(subject.key) != ChordState.Idle) return
        scope.launch {
            lock.withLock {
                if (current(subject.key) != ChordState.Idle) return@withLock
                withContext(io) { runCatching { store.read(subject.key) }.getOrNull() }?.let { set(subject.key, ChordState.Ready(it)) }
            }
        }
    }

    private suspend fun hear(subject: ChordSubject) {
        val me = currentCoroutineContext()[Job]
        var last = -1
        val result = try {
            val found = find?.let { ask(it, subject) }
            if (found is SheetLookup.Found && !found.chart.empty) {
                runCatching { store.write(subject.key, found.chart) }
                ChordState.Ready(found.chart)
            } else {
                progress(subject.key, 0f)
                val heard = analyze(subject) { p ->
                    val pct = (p.coerceIn(0f, 1f) * 100).toInt()
                    if (pct != last) {
                        last = pct
                        progress(subject.key, pct / 100f)
                    }
                }
                if (heard.chords.isEmpty()) throw ChordFailure(ChordState.Reason.NOTHING_HEARD)
                val chart = heard.copy(looked = found == SheetLookup.Missing)
                runCatching { store.write(subject.key, chart) }
                ChordState.Ready(chart)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChordFailure) {
            ChordState.Failed(e.reason)
        } catch (e: AudioDecoder.TooLong) {
            ChordState.Failed(ChordState.Reason.TOO_LONG)
        } catch (t: Throwable) {
            ChordState.Failed(ChordState.Reason.ERROR)
        }
        // A hearing dropped meanwhile leaves its song alone (a chart that made it is still good).
        if (result is ChordState.Ready || currentCoroutineContext().isActive) set(subject.key, result)
        lock.withLock { if (running?.second === me) running = null }
    }

    /**
     * How far the hearing of [key] is — only while it is still on: a hearing dropped for another
     * song (its state back to Idle) keeps reporting until it notices, and must not bring it back.
     */
    private fun progress(key: String, value: Float) = states.update { map ->
        if (map[key] !is ChordState.Working) map else LinkedHashMap(map).apply { put(key, ChordState.Working(value)) }
    }

    private fun set(key: String, state: ChordState) {
        states.update { map ->
            val next = LinkedHashMap(map)
            next.remove(key)
            next[key] = state
            // Only the latest few charts stay in memory; the rest are on disk.
            while (next.size > MEMORY) next.remove(next.keys.first())
            next
        }
    }

    fun bytes(): Long = store.bytes()

    suspend fun clear() = lock.withLock {
        running?.second?.cancel()
        running = null
        askedAgain.clear()
        states.value = emptyMap()
        withContext(io) { store.clear() }
    }

    private companion object {
        const val MEMORY = 24
    }
}
