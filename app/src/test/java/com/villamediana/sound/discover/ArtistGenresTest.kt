package com.villamediana.sound.discover

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The genre of streamed artists, asked once of the iTunes catalog and kept. */
class ArtistGenresTest {
    @get:Rule val tmp = TemporaryFolder()

    private val asked = mutableListOf<String>()
    private var offline = false

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        if (offline) throw java.io.IOException("offline")
        val term = chain.request().url.queryParameter("term").orEmpty()
        asked += term
        val body = when (term) {
            "Henrique & Juliano" -> """{"results":[{"wrapperType":"artist","artistName":"Henrique e Juliano","primaryGenreName":"Pop"},{"wrapperType":"artist","artistName":"Henrique & Juliano","primaryGenreName":"Sertanejo"}]}"""
            "Djavan" -> """{"results":[{"wrapperType":"artist","artistName":"Djavan","primaryGenreName":"MPB"}]}"""
            else -> """{"results":[{"wrapperType":"artist","artistName":"Somebody Else","primaryGenreName":"Rock"}]}"""
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(body.toResponseBody(null)).build()
    }.build()

    private var now = 1_800_000_000_000L
    private fun genres() = ArtistGenres(client, tmp.root.resolve("g.json"), country = "BR", clock = { now })

    @Test
    fun eachArtistIsAskedOnceAndOnlyTheSameNameCounts() = runBlocking {
        val g = genres()
        assertTrue(g.learn(listOf("henrique e juliano" to "Henrique & Juliano", "djavan" to "Djavan", "x" to "Unknown Band")))
        // The result with the same name, not just the first one.
        assertEquals(mapOf("henrique e juliano" to "Sertanejo", "djavan" to "MPB"), g.known())
        // Kept on disk: another start doesn't ask again (an artist not found isn't asked again either).
        asked.clear()
        val again = genres()
        assertFalse(again.learn(listOf("djavan" to "Djavan", "x" to "Unknown Band")))
        assertTrue(asked.isEmpty())
        assertEquals("MPB", again.known()["djavan"])
        // Two months later, asked again.
        now += 61L * 24 * 3_600_000L
        again.learn(listOf("djavan" to "Djavan"))
        assertEquals(listOf("Djavan"), asked)
    }

    @Test
    fun offlineNothingIsLearnedAndNothingIsForgotten() = runBlocking {
        offline = true
        val g = genres()
        assertFalse(g.learn(listOf("djavan" to "Djavan")))
        assertTrue(g.known().isEmpty())
        // Back online: it is asked then (offline wasn't kept as "not found").
        offline = false
        assertTrue(g.learn(listOf("djavan" to "Djavan")))
    }

    @Test
    fun aFewAtATime() = runBlocking {
        val g = genres()
        g.learn((1..10).map { "a$it" to "Artist $it" }, max = 3)
        assertEquals(3, asked.size)
    }
}
