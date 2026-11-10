package com.villamediana.sound.playback.crossfade

import android.util.LruCache
import androidx.media3.common.MediaItem
import com.villamediana.sound.playback.Crossfader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Measures a crossfade ([Mixing.mix]) off the main thread: the end of the song going out and the
 * start of the next ([AudioScanner]). A song heard again (the loop of "repeat one", a playlist
 * that comes around) isn't decoded twice: the last few measures stay in memory (a few KB each).
 */
internal class CrossfadeMeasure(
    private val scope: CoroutineScope,
    private val scanner: AudioScanner,
) : Crossfader.Measure {
    private val ends = LruCache<String, Envelope>(KEEP)
    private val starts = LruCache<String, Envelope>(KEEP)

    override fun start(current: MediaItem, durationMs: Long, next: MediaItem, fadeMs: Long, done: (Mix) -> Unit): () -> Unit {
        val job = scope.launch(Dispatchers.IO) {
            // Both at once: a measure asked for late (a seek near the end of a song never heard) comes sooner.
            val out = async { end(current, durationMs) }
            val inc = async { start(next) }
            val mix = Mixing.mix(fadeMs, durationMs, out.await(), inc.await())
            withContext(Dispatchers.Main) { if (isActive) done(mix) }
        }
        return { job.cancel() }
    }

    private suspend fun end(item: MediaItem, durationMs: Long): Envelope? {
        val uri = item.localConfiguration?.uri ?: return null
        val key = "$uri|$durationMs"
        ends.get(key)?.let { return it }
        val from = (durationMs - Mixing.TAIL_WINDOW_MS).coerceAtLeast(0)
        val context = coroutineContext
        return scanner.envelope(uri, from, durationMs + PAST_END_MS) { !context.isActive }?.also { ends.put(key, it) }
    }

    private suspend fun start(item: MediaItem): Envelope? {
        val uri = item.localConfiguration?.uri ?: return null
        val key = uri.toString()
        starts.get(key)?.let { return it }
        val context = coroutineContext
        return scanner.envelope(uri, 0, Mixing.HEAD_WINDOW_MS) { !context.isActive }?.also { starts.put(key, it) }
    }

    private companion object {
        const val KEEP = 12

        /** The end is read to the end of the file, whatever its exact length. */
        const val PAST_END_MS = 10_000L
    }
}
