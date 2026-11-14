package com.villamediana.sound.karaoke

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A song the karaoke can work on: which recording it is and how its audio is read. */
class KaraokeSong(
    /** The recording ([com.villamediana.sound.chords.ChordSubject.key]): streamed or downloaded, the same masks. */
    val key: String,
    /** Opens the song's audio (the worker thread closes it). Throws [IOException] when it can't be read. */
    val open: () -> SongPcm,
)

/** Where a song's karaoke stands. */
sealed interface KaraokeState {
    /** Nobody asked for this song without its voice. */
    data object Idle : KaraokeState

    /** The model that tells the voice apart is on its way to the phone (once, the first time). */
    data class Fetching(val progress: Float) : KaraokeState

    /** The song is being heard, part by part ([progress] of it done). */
    data class Working(val progress: Float) : KaraokeState

    data object Ready : KaraokeState

    data class Failed(val reason: Reason) : KaraokeState

    enum class Reason { OFFLINE, TOO_LONG, UNSUPPORTED, ERROR }
}

/** The model could not be had because there is no connection. */
class KaraokeOffline(cause: Throwable? = null) : IOException("offline", cause)

/**
 * Takes the voice out of songs, on the phone: the song playing first — from the moment it is at,
 * so the voice goes within a second or two — then the rest of it, then the song that comes next,
 * so a queue is sung through without waiting. What is worked out is kept ([VoiceMasks] files in
 * [dir], the least recently sung giving way past [maxBytes]): a song sung again is ready at once.
 *
 * Nothing runs unless asked ([sing]); [stop] ends whatever is running and lets the model go.
 *
 * [sing], [stop] and [clear] come from the main thread; the work is done on [worker], one song at
 * a time; [masks] is asked from the playback thread.
 */
class KaraokeEngine(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val dir: File,
    /** The model, fetched if it isn't on the phone yet ([KaraokeOffline] without a connection). */
    private val model: suspend (progress: (Float) -> Unit) -> StemModel,
    private val maxBytes: Long = 260L * 1024 * 1024,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<KaraokeState>>()
    private val open = ConcurrentHashMap<String, VoiceMasks>()
    private val opened = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The work on the song being sung (and the next); null when nobody sings. */
    private var job: Job? = null
    /** The last thing handed to the worker: whatever comes after waits for it, so nothing overlaps. */
    private var tail: Job? = null
    @Volatile private var current: KaraokeSong? = null
    @Volatile private var next: KaraokeSong? = null
    @Volatile private var focusMs = 0L
    /** Touched by the worker only. */
    private var loaded: StemModel? = null

    /** A song's masks became readable (its first parts may still be on their way): the players may use them. */
    val changes: SharedFlow<String> get() = opened

    fun state(key: String): StateFlow<KaraokeState> = flow(key)

    private fun flow(key: String) = states.getOrPut(key) { MutableStateFlow(KaraokeState.Idle) }

    /** The masks of [key] as far as they are worked out; null when there are none at hand. */
    fun masks(key: String): VoiceMasks? = open[key]

    /**
     * [song] is playing, at [positionMs], and is to be sung: its voice goes from there on first.
     * [after] is what plays next: readied once [song] is done.
     */
    fun sing(song: KaraokeSong, positionMs: Long, after: KaraokeSong?) {
        focusMs = positionMs
        val same = current?.key == song.key && job?.isActive == true
        current = song
        // The same song (a seek, or the queue behind it changed): the work goes on, from the new moment.
        if (same && next?.key == after?.key) return
        next = after
        if (same) return
        restart()
    }

    /** Nobody is singing anymore: the work stops where it is (what was done is kept). */
    fun stop() {
        job?.cancel()
        job = null
        current = null
        next = null
        val before = tail
        tail = scope.launch(worker) {
            before?.join()
            release()
            for ((_, state) in states) if (state.value is KaraokeState.Working || state.value is KaraokeState.Fetching) state.value = KaraokeState.Idle
        }
    }

    private fun restart() {
        // The song being worked on stops at its next part; the new one starts when it has.
        job?.cancel()
        val before = tail
        val work = scope.launch(worker) {
            before?.join()
            val first = current ?: return@launch
            if (!work(first, followFocus = true)) return@launch
            // The next one in the queue, quietly, while this one is sung.
            val then = next ?: return@launch
            work(then, followFocus = false)
        }
        job = work
        tail = work
    }

    /** Works [song] out to the end; false when it failed (or was told to stop). */
    private suspend fun work(song: KaraokeSong, followFocus: Boolean): Boolean {
        val state = flow(song.key)
        // Sung before, all of it: ready without touching its audio (or the network).
        val had = open[song.key] ?: VoiceMasks.reopen(File(dir, "${song.key}.masks"))?.also { adopt(song.key, it) }
        if (had?.complete == true) {
            had.file.setLastModified(System.currentTimeMillis())
            state.value = KaraokeState.Ready
            return true
        }
        var pcm: SongPcm? = null
        try {
            if (loaded == null) {
                state.value = KaraokeState.Fetching(0f)
                loaded = model { state.value = KaraokeState.Fetching(it.coerceIn(0f, 1f)) }
            }
            val remover = VocalRemover(loaded!!)
            // Going on from what a previous time left, when it left something.
            state.value = KaraokeState.Working(had?.let { it.done.toFloat() / it.parts } ?: 0f)
            pcm = song.open()
            val audio = ModelAudio(pcm)
            if (audio.frames > MAX_SECONDS * VoiceMasks.RATE) {
                state.value = KaraokeState.Failed(KaraokeState.Reason.TOO_LONG)
                return false
            }
            if (audio.frames <= 0) throw IOException("empty song")
            val masks = masksFor(song.key, VoiceMasks.framesFor(audio.frames))
            state.value = if (masks.complete) KaraokeState.Ready else KaraokeState.Working(masks.done.toFloat() / masks.parts)
            while (!masks.complete) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val part = nextPart(masks, if (followFocus) focusMs else 0L)
                masks.write(part, remover.part(part) { side, from, into -> audio.read(side, from, into) })
                state.value = if (masks.complete) KaraokeState.Ready else KaraokeState.Working(masks.done.toFloat() / masks.parts)
            }
            masks.flush()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: KaraokeOffline) {
            state.value = KaraokeState.Failed(KaraokeState.Reason.OFFLINE)
        } catch (e: UnsatisfiedLinkError) {
            state.value = KaraokeState.Failed(KaraokeState.Reason.UNSUPPORTED)
        } catch (e: IOException) {
            val offline = generateSequence(e as Throwable) { it.cause }.take(8).any {
                it is KaraokeOffline || it is java.net.UnknownHostException ||
                    (it as? com.villamediana.sound.playback.stream.StreamResolveException)?.offline == true
            }
            state.value = KaraokeState.Failed(if (offline) KaraokeState.Reason.OFFLINE else KaraokeState.Reason.ERROR)
        } catch (e: Throwable) {
            state.value = KaraokeState.Failed(KaraokeState.Reason.ERROR)
        } finally {
            runCatching { pcm?.close() }
        }
        return false
    }

    /** The first part not done from the moment being played on; all done there, the first one not done at all. */
    private fun nextPart(masks: VoiceMasks, positionMs: Long): Int {
        val from = masks.partAt(positionMs)
        for (p in from until masks.parts) if (!masks.isReady(p)) return p
        for (p in 0 until from) if (!masks.isReady(p)) return p
        return 0
    }

    private fun masksFor(key: String, frames: Int): VoiceMasks {
        open[key]?.let { if (it.frames == frames) return it else close(key) }
        val file = File(dir, "$key.masks")
        makeRoom(frames.toLong() * VoiceMasks.FRAME_BYTES, keep = file)
        val masks = VoiceMasks.open(file, frames)
        file.setLastModified(System.currentTimeMillis())
        adopt(key, masks)
        return masks
    }

    /** [masks] are [key]'s from now on, for the players too. Only the song being sung and the next one stay open. */
    private fun adopt(key: String, masks: VoiceMasks) {
        for (other in open.keys.toList()) if (other != key && other != current?.key && other != next?.key) close(other)
        open[key] = masks
        opened.tryEmit(key)
    }

    private fun close(key: String) {
        open.remove(key)?.close()
    }

    /** The least recently sung songs give way until [incoming] more bytes fit under [maxBytes]. */
    private fun makeRoom(incoming: Long, keep: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".masks") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() } + if (keep.isFile) 0 else incoming
        for (f in files) {
            if (total <= maxBytes) break
            val key = f.name.removeSuffix(".masks")
            if (f == keep || key == current?.key || key == next?.key) continue
            close(key)
            total -= f.length()
            f.delete()
            states[key]?.value = KaraokeState.Idle
        }
    }

    private fun release() {
        runCatching { loaded?.close() }
        loaded = null
        for (key in open.keys.toList()) close(key)
    }

    /** What the songs worked out take on the phone (Ajustes › Armazenamento). */
    fun bytes(): Long = dir.listFiles { f -> f.isFile && f.name.endsWith(".masks") }.orEmpty().sumOf { it.length() }

    /** Forgets every song worked out (not the one being sung right now). */
    fun clear() {
        scope.launch(worker) {
            for (f in dir.listFiles { f -> f.isFile && f.name.endsWith(".masks") }.orEmpty()) {
                val key = f.name.removeSuffix(".masks")
                if (key == current?.key || key == next?.key) continue
                close(key)
                f.delete()
                states[key]?.value = KaraokeState.Idle
            }
        }
    }

    companion object {
        /** Fifteen minutes: past that it is a live set or a mix, not a song to sing. */
        const val MAX_SECONDS = 15L * 60
    }
}
