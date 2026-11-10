package com.villamediana.sound.data

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.playback.stream.AudioCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ajustes › Armazenamento: "Limpar" really empties each cache on disk, the sizes say so right
 * after, and the big number is always the downloads plus what is kept (the two never disagree).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCachesTest {

    @Test
    fun clearingEmptiesEachCacheAndTheNumbersAddUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SoundApplication>()
        val graph = SoundApplication.graph
        val caches = graph.caches
        val start = caches.measure()

        // A downloaded song (it stays) and what is kept to spare the internet (it goes).
        graph.storage.audioFile("keep1234567", "m4a").writeBytes(ByteArray(200_000))
        val audio = AudioCache.get(app)
        audio.put("sound://stream/aaaaaaaaaaa?q=BEST", 300_000)
        audio.put("sound://stream/bbbbbbbbbbb?q=BEST", 120_000)
        val disk = SingletonImageLoader.get(app).diskCache!!
        disk.openEditor("https://i.ytimg.com/cover.jpg")!!.apply {
            disk.fileSystem.write(data) { write(ByteArray(50_000)) }
            commit()
        }
        app.filesDir.resolve("covers.json").writeText("{\"x\":\"" + "c".repeat(1_000) + "\"}")
        app.filesDir.resolve("lyrics-translations.json").writeText("{\"x\":\"" + "t".repeat(2_000) + "\"}")
        app.filesDir.resolve("discover.json").writeText("{\"lists\":{}," + " ".repeat(3_000) + "}")
        app.filesDir.resolve("chords").apply { mkdirs() }.resolve("song.json").writeText("{" + " ".repeat(4_000) + "}")

        val before = caches.measure()
        assertEquals(start.downloads + 200_000, before.downloads)
        assertEquals(start.caches.getValue(CacheKind.SONGS) + 420_000, before.caches.getValue(CacheKind.SONGS))
        assertTrue(before.caches.getValue(CacheKind.COVERS) >= 51_000)
        assertTrue(before.caches.getValue(CacheKind.LYRICS) >= 2_000)
        assertTrue(before.caches.getValue(CacheKind.HOME) >= 3_000)
        assertTrue(before.caches.getValue(CacheKind.CHORDS) >= 4_000)
        assertEquals(before.downloads + before.caches.values.sum(), before.used)

        // One kind: only it goes, its files are gone, and the total drops by exactly that much.
        caches.clear(CacheKind.SONGS)
        val songsGone = caches.measure()
        assertEquals(0L, songsGone.caches.getValue(CacheKind.SONGS))
        assertTrue(app.cacheDir.resolve("audio").walkTopDown().none { it.isFile && it.name.endsWith(".exo") })
        assertEquals(before.used - before.caches.getValue(CacheKind.SONGS), songsGone.used)
        assertEquals(before.caches.getValue(CacheKind.COVERS), songsGone.caches.getValue(CacheKind.COVERS))

        // Everything: nothing kept, the downloads untouched, the big number is just them.
        caches.clearAll()
        val after = caches.measure()
        CacheKind.entries.forEach { assertEquals("$it after Limpar tudo", 0L, after.caches.getValue(it)) }
        assertEquals(before.downloads, after.downloads)
        assertEquals(after.downloads, after.used)
        assertTrue(graph.storage.audioFile("keep1234567", "m4a").isFile)
    }

    /** A streamed song's bytes, as the player leaves them in the cache. */
    private fun Cache.put(key: String, length: Int) {
        val hole = startReadWrite(key, 0, length.toLong())
        val file = startFile(key, 0, length.toLong())
        file.writeBytes(ByteArray(length))
        commitFile(file, length.toLong())
        releaseHoleSpan(hole)
    }
}
