package com.villamediana.sound.data

import android.content.Context
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import coil3.SingletonImageLoader
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.playback.stream.AudioCache
import com.villamediana.sound.playback.video.VideoCanvas
import com.villamediana.sound.ui.player.PlayerLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the app keeps on the phone to spare the internet; all of it comes back by itself when needed. */
enum class CacheKind {
    /** Songs streamed lately (replays, going back, the crossfade's end). */
    SONGS,

    /** The music-video loops behind the cover. */
    VIDEOS,

    /** Pictures (covers, artists) and the album art found for video uploads. */
    COVERS,

    /** Lyrics of streamed songs and their translations. */
    LYRICS,

    /** The Home's suggestion lists. */
    HOME,

    /** Chords heard from the songs (hearing them again takes a few seconds each). */
    CHORDS,
}

/**
 * What the app keeps on the phone, as Ajustes › Armazenamento shows it: the downloaded songs and
 * what is kept to spare the internet ([caches]). [used] is exactly the two added up, so the big
 * number, its two parts and the "Liberar espaço" sheet always agree.
 */
data class StorageUse(val downloads: Long, val caches: Map<CacheKind, Long>, val free: Long?) {
    val kept: Long get() = caches.values.sum()
    val used: Long get() = downloads + kept
}

/** Sizes and clean-up of every cache (Ajustes › Armazenamento), one kind at a time or all. */
@OptIn(UnstableApi::class)
class AppCaches(private val context: Context) {
    private val graph get() = SoundApplication.graph

    suspend fun sizes(): Map<CacheKind, Long> = withContext(Dispatchers.IO) {
        CacheKind.entries.associateWith { runCatching { size(it) }.getOrDefault(0L) }
    }

    /** Downloads, every cache and the room left on the phone, measured together. */
    suspend fun measure(): StorageUse = withContext(Dispatchers.IO) {
        StorageUse(
            downloads = runCatching { graph.storage.usedBytes() }.getOrDefault(0L),
            caches = sizes(),
            free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
        )
    }

    private fun size(kind: CacheKind): Long = when (kind) {
        CacheKind.SONGS -> AudioCache.get(context).cacheSpace
        CacheKind.VIDEOS -> VideoCanvas.sharedCache(context).cacheSpace
        CacheKind.COVERS -> (SingletonImageLoader.get(context).diskCache?.size ?: 0L) + graph.covers.bytes()
        CacheKind.LYRICS -> PlayerLyrics.cache.bytes() + graph.lyricsTranslator.bytes()
        CacheKind.HOME -> graph.discover.bytes()
        CacheKind.CHORDS -> graph.chords.bytes()
    }

    suspend fun clear(kind: CacheKind) = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                CacheKind.SONGS -> AudioCache.get(context).removeAll()
                CacheKind.VIDEOS -> VideoCanvas.sharedCache(context).removeAll()
                CacheKind.COVERS -> {
                    SingletonImageLoader.get(context).let { loader ->
                        loader.diskCache?.clear()
                        loader.memoryCache?.clear()
                    }
                    graph.covers.clear()
                }
                CacheKind.LYRICS -> {
                    PlayerLyrics.cache.clear()
                    graph.lyricsTranslator.clear()
                }
                CacheKind.HOME -> graph.discover.clear()
                CacheKind.CHORDS -> graph.chords.clear()
            }
        }
        Unit
    }

    suspend fun clearAll() = CacheKind.entries.forEach { clear(it) }

    /** Every cached song/clip; a part still being read is let go by the player on its own. */
    private fun Cache.removeAll() {
        keys.toList().forEach { key -> runCatching { removeResource(key) } }
    }
}
