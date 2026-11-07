package com.villamediana.sound.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTranslatorTest {
    private val answer = """[[["Estou no negócio da miséria\n","I'm in the business of misery\n",null,null,10],["Vamos começar do início\n\n","Let's take it from the top\n\n",null,null,10],["Ela tem um corpo","She's got a body",null,null,10]],null,"en",null,null,null,1.0]"""

    @Test fun linesComeBackInPlace() {
        val lines = LyricsTranslator.parse(answer, "pt", 4)
        assertEquals(listOf("Estou no negócio da miséria", "Vamos começar do início", "", "Ela tem um corpo"), lines)
    }

    @Test fun alreadyInThePhonesLanguageNeedsNothing() {
        val same = """[[["Eu sei","Eu sei",null,null,10]],null,"pt",null]"""
        assertTrue(LyricsTranslator.parse(same, "pt", 1)!!.isEmpty())
    }

    @Test fun linesThatDontLineUpAreDropped() {
        assertNull(LyricsTranslator.parse(answer, "pt", 3))
        assertNull(LyricsTranslator.parse("nada", "pt", 1))
    }

    /** Live (SOUND_LIVE=1): a real song comes back line by line in Portuguese, and Portuguese stays as it is. */
    @Test fun liveTranslation() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("sound.live") == "1")
        val file = java.io.File.createTempFile("lyrics-tr", ".json").apply { delete() }
        val t = LyricsTranslator(okhttp3.OkHttpClient(), file, kotlinx.coroutines.flow.flowOf(true), target = { "pt" })
        val out = t.translate(listOf("I'm in the business of misery", "", "Let's take it from the top"))!!
        println("LIVE translation: $out")
        assertEquals(3, out.size)
        assertNull(out[1])
        assertTrue(out[0]!!.contains("miséria", ignoreCase = true))
        assertNull(t.translate(listOf("Eu sei que vou te amar", "Por toda a minha vida")))
    }
}
