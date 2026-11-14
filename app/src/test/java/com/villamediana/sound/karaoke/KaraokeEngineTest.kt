package com.villamediana.sound.karaoke

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The order songs are worked out in, what is kept, and what happens when it can't be done. */
class KaraokeEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val thread = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob())

    @After fun stop() {
        scope.cancel()
        thread.shutdownNow()
    }

    /** A song of [seconds] at 44.1 kHz whose audio says nothing. */
    private class Quiet(seconds: Int, private val opened: AtomicInteger? = null) : SongPcm {
        override val sampleRate = VoiceMasks.RATE
        override val frames = seconds.toLong() * VoiceMasks.RATE
        init { opened?.incrementAndGet() }
        override fun read(from: Long, count: Int, left: FloatArray, right: FloatArray) = Unit
        override fun close() = Unit
    }

    /** A model that takes [delayMs] per stretch and calls nothing voice. */
    private inner class Model(private val delayMs: Long = 0) : StemModel {
        var closed = false
        override fun stems(mix: FloatArray): StemModel.Stems {
            if (delayMs > 0) Thread.sleep(delayMs)
            return StemModel.Stems(band = mix.copyOf(), voice = FloatArray(mix.size))
        }
        override fun close() { closed = true }
    }

    private val loads = AtomicInteger()
    private var lastModel: Model? = null

    private fun engine(dir: File = tmp.newFolder(), delayMs: Long = 0, maxBytes: Long = Long.MAX_VALUE, model: (suspend ((Float) -> Unit) -> StemModel)? = null) =
        KaraokeEngine(scope, thread.asCoroutineDispatcher(), dir, model ?: { progress ->
            loads.incrementAndGet()
            progress(0.5f)
            Model(delayMs).also { lastModel = it }
        }, maxBytes)

    private fun await(engine: KaraokeEngine, key: String, timeoutMs: Long = 20_000, done: (KaraokeState) -> Boolean): KaraokeState =
        runBlocking { withTimeout(timeoutMs) { engine.state(key).first(done) } }

    /** Waits for something the worker does on its own time. */
    private fun until(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val limit = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < limit) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test fun `the song is worked out from where it is playing, then the next one`() {
        val dir = tmp.newFolder()
        val engine = engine(dir)
        val order = Collections.synchronizedList(ArrayList<String>())
        // 60 s: six parts. It is playing at 0:35 (the fourth part).
        val watch = scope.let {
            Thread {
                var seen = 0
                while (!Thread.currentThread().isInterrupted) {
                    val masks = engine.masks("a")
                    if (masks != null) {
                        for (p in 0 until masks.parts) if (masks.isReady(p) && "a$p" !in order) order += "a$p"
                    }
                    if (order.size != seen) seen = order.size
                    try { Thread.sleep(1) } catch (e: InterruptedException) { return@Thread }
                }
            }.apply { isDaemon = true; start() }
        }
        assertNull(engine.masks("a"))
        engine.sing(KaraokeSong("a") { Quiet(60) }, 35_000, KaraokeSong("b") { Quiet(25) })
        assertEquals(KaraokeState.Ready, await(engine, "a") { it == KaraokeState.Ready })
        assertEquals(KaraokeState.Ready, await(engine, "b") { it == KaraokeState.Ready })
        watch.interrupt()
        val masks = engine.masks("a")!!
        assertTrue(masks.complete)
        assertEquals(6, masks.parts)
        // What was seen done first is from the part being played on; the song's start came last.
        val seen = order.toList()
        assertTrue("order $seen", seen.indexOf("a3") < seen.indexOf("a0") || seen.size < 6)
        assertTrue(engine.masks("b")!!.complete)
        assertTrue(File(dir, "a.masks").isFile && File(dir, "b.masks").isFile)
        assertEquals(1, loads.get())
    }

    @Test fun `a song sung before is ready without its audio or the model`() {
        val dir = tmp.newFolder()
        val opened = AtomicInteger()
        engine(dir).let { first ->
            first.sing(KaraokeSong("a") { Quiet(30, opened) }, 0, null)
            await(first, "a") { it == KaraokeState.Ready }
            first.stop()
            until { first.masks("a") == null }
        }
        assertEquals(1, opened.get())
        val again = engine(dir, model = { error("the model isn't needed") })
        again.sing(KaraokeSong("a") { error("the audio isn't needed") }, 12_000, null)
        assertEquals(KaraokeState.Ready, await(again, "a") { it == KaraokeState.Ready })
        assertTrue(again.masks("a")!!.complete)
    }

    @Test fun `stopping keeps what was done, and singing again goes on from there`() {
        val dir = tmp.newFolder()
        val engine = engine(dir, delayMs = 60)
        engine.sing(KaraokeSong("long") { Quiet(120) }, 0, null)
        await(engine, "long") { (it as? KaraokeState.Working)?.progress?.let { p -> p > 0f } == true }
        engine.stop()
        runBlocking { withTimeout(10_000) { engine.state("long").first { it == KaraokeState.Idle } } }
        // The model is let go and the players get no more masks from here.
        until { engine.masks("long") == null && lastModel?.closed == true }
        val kept = VoiceMasks.reopen(File(dir, "long.masks"))!!
        val done = kept.done
        kept.close()
        assertTrue("done $done of ${kept.parts}", done in 1 until kept.parts)

        val fast = engine(dir)
        fast.sing(KaraokeSong("long") { Quiet(120) }, 0, null)
        // It starts from what was kept, not from nothing.
        val first = await(fast, "long") { it is KaraokeState.Working || it == KaraokeState.Ready }
        assertTrue(first == KaraokeState.Ready || (first as KaraokeState.Working).progress >= done.toFloat() / kept.parts - 0.01f)
        assertEquals(KaraokeState.Ready, await(fast, "long") { it == KaraokeState.Ready })
    }

    @Test fun `what can't be done says why`() {
        val engine = engine()
        engine.sing(KaraokeSong("gone") { throw IOException("no such file") }, 0, null)
        assertEquals(KaraokeState.Failed(KaraokeState.Reason.ERROR), await(engine, "gone") { it is KaraokeState.Failed })
        engine.sing(KaraokeSong("set") { Quiet(40 * 60) }, 0, null)
        assertEquals(KaraokeState.Failed(KaraokeState.Reason.TOO_LONG), await(engine, "set") { it is KaraokeState.Failed })
        assertNull(engine.masks("set"))

        // No connection the first time: the model can't come.
        val offline = engine(model = { throw KaraokeOffline() })
        offline.sing(KaraokeSong("a") { Quiet(30) }, 0, null)
        assertEquals(KaraokeState.Failed(KaraokeState.Reason.OFFLINE), await(offline, "a") { it is KaraokeState.Failed })
        // "Tentar de novo" with the connection back.
        var online = false
        val later = engine(model = { if (online) Model() else throw KaraokeOffline() })
        later.sing(KaraokeSong("a") { Quiet(30) }, 0, null)
        await(later, "a") { it is KaraokeState.Failed }
        online = true
        later.sing(KaraokeSong("a") { Quiet(30) }, 0, null)
        assertEquals(KaraokeState.Ready, await(later, "a") { it == KaraokeState.Ready })

        // A phone the model's runtime isn't there for.
        val other = engine(model = { throw UnsatisfiedLinkError("no onnxruntime") })
        other.sing(KaraokeSong("a") { Quiet(30) }, 0, null)
        assertEquals(KaraokeState.Failed(KaraokeState.Reason.UNSUPPORTED), await(other, "a") { it is KaraokeState.Failed })
    }

    @Test fun `the songs sung longest ago give way when the room runs out`() {
        val dir = tmp.newFolder()
        val one = VoiceMasks.framesFor(30L * VoiceMasks.RATE).toLong() * VoiceMasks.FRAME_BYTES
        // Room for two songs of 30 s and a little.
        val engine = engine(dir, maxBytes = one * 2 + one / 2)
        for ((i, key) in listOf("a", "b", "c").withIndex()) {
            engine.sing(KaraokeSong(key) { Quiet(30) }, 0, null)
            await(engine, key) { it == KaraokeState.Ready }
            File(dir, "$key.masks").setLastModified(1_000_000L * (i + 1))
        }
        assertFalse(File(dir, "a.masks").exists())
        assertTrue(File(dir, "b.masks").isFile && File(dir, "c.masks").isFile)
        assertEquals(KaraokeState.Idle, engine.state("a").value)
        assertTrue(engine.bytes() <= one * 2 + one / 2)
        // Ajustes › Armazenamento: everything but what is being sung.
        engine.clear()
        until { !File(dir, "b.masks").exists() }
        assertTrue(File(dir, "c.masks").isFile)
        assertNotNull(engine.masks("c"))
    }
}
