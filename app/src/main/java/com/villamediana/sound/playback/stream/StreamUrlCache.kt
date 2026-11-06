package com.villamediana.sound.playback.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolved stream urls (googlevideo), per key: resolved once and reused for [ttlMs] — or until the
 * url's own `expire` time, if sooner — and resolved again on demand when the server refuses one.
 *
 * Asks for the same key share one resolution: a prefetch and the player's own request never extract
 * twice. Resolutions run in [scope], so a caller that gives up (the player skipped the song) doesn't
 * cancel the work for the others.
 */
internal class StreamUrlCache<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val urlOf: (V) -> String,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resolves [key]; [previous] is the last value (also an expired or refused one), to keep e.g. the same quality. */
    private val resolve: suspend (key: K, previous: V?) -> V,
) {
    private class Entry<V>(val value: V, val expiresAt: Long)

    private val lock = Any()
    private val entries = ConcurrentHashMap<K, Entry<V>>()
    private val inflight = HashMap<K, Deferred<V>>()

    /** The cached value while it's still good, without resolving. */
    fun peek(key: K): V? = entries[key]?.takeIf { it.expiresAt > clock() }?.value

    /** The value for [key]: cached, or resolved now. [fresh] = the cached one was refused; resolve again. */
    suspend fun get(key: K, fresh: Boolean = false): V {
        if (!fresh) peek(key)?.let { return it }
        val job = synchronized(lock) {
            inflight[key] ?: run {
                val previous = entries[key]?.value
                if (fresh) entries.remove(key)
                val created = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        resolve(key, previous).also { entries[key] = Entry(it, expiryOf(it)) }
                    } finally {
                        synchronized(lock) { inflight.remove(key) }
                    }
                }
                inflight[key] = created
                created
            }
        }
        job.start()
        return job.await()
    }

    /** Resolves [key] in the background if it isn't cached yet (the next song, before it's needed). */
    fun prefetch(key: K) {
        if (peek(key) != null) return
        scope.launch { runCatching { get(key) } }
    }

    fun invalidate(key: K) {
        entries.remove(key)
    }

    private fun expiryOf(value: V): Long {
        val now = clock()
        val byTtl = now + ttlMs
        val byUrl = urlExpiryMs(urlOf(value))?.minus(EXPIRY_MARGIN_MS)?.takeIf { it > now }
        return if (byUrl != null) minOf(byTtl, byUrl) else byTtl
    }

    companion object {
        /** YouTube signs stream urls for ~6 h; 4 h keeps well clear of that. */
        const val DEFAULT_TTL_MS = 4 * 60 * 60_000L
        private const val EXPIRY_MARGIN_MS = 5 * 60_000L

        /** The `expire` (unix seconds) query parameter of a googlevideo url, in ms. */
        fun urlExpiryMs(url: String): Long? =
            url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
    }
}
