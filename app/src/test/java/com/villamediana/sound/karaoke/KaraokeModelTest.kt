package com.villamediana.sound.karaoke

import com.villamediana.sound.online.OnlineFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The model comes once, whole and checked, and stays. */
class KaraokeModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val voice = "the voice's network"
    private val band = "the band's network, a little longer"
    private val parts = listOf(
        KaraokeModel.Part("vocals.onnx", "https://example.test/v.onnx", voice.length.toLong(), sha(voice)),
        KaraokeModel.Part("accompaniment.onnx", "https://example.test/b.onnx", band.length.toLong(), sha(band)),
    )

    @Test fun `it is fetched the first time and never again`() = runBlocking {
        val http = OnlineFixtures.FakeHttp { req -> 200 to if (req.url.encodedPath == "/v.onnx") voice else band }
        val dir = File(tmp.root, "model")
        val model = KaraokeModel(http.client, dir, files = parts)
        assertFalse(model.present)
        val seen = ArrayList<Float>()
        model.fetch { seen += it }
        assertTrue(model.present)
        assertEquals(voice, model.voice().readText())
        assertEquals(band, model.band().readText())
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals((voice.length + band.length).toLong(), model.bytes())
        model.fetch { fail("nothing to fetch") }
        assertEquals(2, http.requests.size)
        // Half of it there (the app was closed midway): only the rest comes.
        model.band().delete()
        model.fetch { }
        assertEquals(3, http.requests.size)
        model.clear()
        assertFalse(model.present)
    }

    @Test fun `a file that isn't the model is not kept`() = runBlocking {
        val http = OnlineFixtures.FakeHttp { 200 to "something else entirely" }
        val model = KaraokeModel(http.client, File(tmp.root, "model"), files = parts)
        try {
            model.fetch { }
            fail("kept a wrong file")
        } catch (e: IOException) {
            assertFalse(e is KaraokeOffline)
        }
        assertFalse(model.present)
        assertEquals(0L, model.bytes())
    }

    @Test fun `without a connection it says so`() = runBlocking {
        val refused = OnlineFixtures.FakeHttp { throw IOException("no route") }
        for (model in listOf(
            KaraokeModel(refused.client, File(tmp.root, "a"), files = parts),
            KaraokeModel(refused.client, File(tmp.root, "b"), online = { false }, files = parts),
        )) {
            try {
                model.fetch { }
                fail("no connection, and yet")
            } catch (e: KaraokeOffline) {
                assertFalse(model.present)
            }
        }
        assertEquals(1, refused.requests.size)
        // The server saying no is not "offline".
        val missing = OnlineFixtures.FakeHttp { 404 to "" }
        try {
            KaraokeModel(missing.client, File(tmp.root, "c"), files = parts).fetch { }
            fail("404, and yet")
        } catch (e: IOException) {
            assertFalse(e is KaraokeOffline)
        }
    }

    @Test fun `the model shipped is the one checked here`() {
        // What the app fetches: two networks of about 20 MB, by name, with their checksums.
        assertEquals(listOf("vocals.onnx", "accompaniment.onnx"), KaraokeModel.PARTS.map { it.name })
        assertTrue(KaraokeModel.PARTS.all { it.url.startsWith("https://github.com/Villamediana/FreeSong/releases/download/") && it.sha256.length == 64 && it.bytes in 15_000_000L..25_000_000L })
    }
}
