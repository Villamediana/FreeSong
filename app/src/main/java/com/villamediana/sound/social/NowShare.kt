package com.villamediana.sound.social

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.playback.OnlineItem
import com.villamediana.sound.playback.PlaybackPulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The mini player together with the computer's (FreeSong on the web): what plays here goes to the
 * relay, sealed with the vault's key (the relay can't read it), and what plays there comes here.
 * Whoever stands still shows what the other played last, paused where it is now: play goes on from
 * there. Whoever is playing changes nothing.
 *
 * Sent: a song started, play or pause, a jump to another point. Not sent: a song only set up (the
 * computer's, or the queue put back when the app opens), or one would keep sending the other back
 * what it just got.
 */
@OptIn(FlowPreview::class)
internal class NowShare(
    private val scope: CoroutineScope,
    private val api: () -> RelayApi,
    /** The vault's data key (null: no vault yet, nothing to seal with). */
    private val key: suspend () -> ByteArray?,
    /** A profile ready to talk to the relay. */
    private val ready: () -> Boolean,
    /** Whether the mini player may change now (on screen, not in a jam). */
    private val canFollow: () -> Boolean,
    /** When this phone last played, before the player said so (the queue saved when the app was closed). */
    private val savedAt: suspend () -> Long,
    /** Sets [track] up here, paused at that point. */
    private val follow: (Track, Long) -> Unit,
    /** This phone among the profile's devices (what it sent itself is not followed). */
    private val device: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var seenRev = -1L
    private var sentPlaying = false

    fun start() {
        scope.launch {
            PlaybackPulse.now
                .filterNotNull()
                .distinctUntilChangedBy { Triple(it.videoId, it.playing, it.seeks) }
                .debounce(SETTLE_MS)
                .collect { runCatching { publish(it) } }
        }
    }

    private suspend fun publish(n: PlaybackPulse.Now) {
        val id = n.videoId?.takeIf { VIDEO.matches(it) } ?: return
        // Set up only (the computer's song, the queue put back): nothing new to tell.
        if (!n.playing && !sentPlaying) return
        if (!ready()) return
        val k = key() ?: return
        val blob = NowBlob(
            dev = device, i = id, t = n.title, a = n.artist, al = n.album, d = n.durationMs.coerceAtLeast(0),
            c = n.cover, p = n.positionMs.coerceAtLeast(0), s = n.playing, at = n.at,
        )
        val sealed = VaultCrypto.sealNow(Codec.encodeToString(NowBlob.serializer(), blob).encodeToByteArray(), k)
        withContext(Dispatchers.IO) { api().putNow(sealed) }
        sentPlaying = n.playing
    }

    /** What plays on the computer: shown here when this phone stands still and played before it. */
    suspend fun pull() = lock.withLock {
        if (!ready() || !canFollow()) return@withLock
        val r = withContext(Dispatchers.IO) { api().now() }
        if (r.rev == seenRev) return@withLock
        seenRev = r.rev
        val data = r.data ?: return@withLock
        val k = key() ?: return@withLock
        val blob = VaultCrypto.openNow(data, k)?.let { runCatching { Codec.decodeFromString(NowBlob.serializer(), it.decodeToString()) }.getOrNull() }
            ?: return@withLock
        if (blob.dev == device || !VIDEO.matches(blob.i)) return@withLock
        if (PlaybackPulse.playing.value) return@withLock
        val here = PlaybackPulse.now.value?.at ?: savedAt()
        if (blob.at <= here) return@withLock
        val item = OnlineItem(
            videoId = blob.i,
            title = blob.t.ifBlank { "…" },
            artist = blob.a,
            album = blob.al,
            durationMs = blob.d.takeIf { it > 0 },
            thumbnailUrl = blob.c ?: "https://i.ytimg.com/vi/${blob.i}/hqdefault.jpg",
            url = "https://music.youtube.com/watch?v=${blob.i}",
        )
        val at = positionNow(blob, r.now - r.at)
        withContext(Dispatchers.Main) { follow(item.toTrack(), at) }
    }

    companion object {
        private const val SETTLE_MS = 600L
        private val VIDEO = Regex("^[A-Za-z0-9_-]{11}$")

        /** Where a song that was playing is now (it went on since it was sent); paused, where it stopped. */
        internal fun positionNow(blob: NowBlob, elapsedMs: Long): Long {
            if (!blob.s) return blob.p
            val p = blob.p + elapsedMs.coerceAtLeast(0)
            return if (blob.d > 0) minOf(p, (blob.d - 3_000).coerceAtLeast(0)) else p
        }

        internal val Codec = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}

/** What plays, sealed for the relay (web/src/core/now.js: nowOf): the same short names. */
@Serializable
internal data class NowBlob(
    val v: Int = 1,
    val dev: String,
    val i: String,
    val t: String = "",
    val a: String = "",
    val al: String? = null,
    val d: Long = 0,
    val c: String? = null,
    val p: Long = 0,
    val s: Boolean = false,
    val at: Long = 0,
)
