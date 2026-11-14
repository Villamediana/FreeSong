package com.villamediana.sound.online

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Letras' search and pages (made-up song, the site's markup as it is in 2026). */
class LetrasLyricsSourceTest {
    private fun doc(art: String, txt: String, dns: String, url: String, t: String = "2") =
        """{"art":"$art","dns":"$dns","full_txt":"$txt $art","txt":"$txt","t":"$t","url":"$url","score":12.5}"""

    private fun answer(vararg docs: String) = """LetrasSug({"response":{"numFound":${docs.size},"start":0,"docs":[${docs.joinToString(",")}]}})"""

    private val search = answer(
        doc("Os Inventados", "", "os-inventados", "", t = "1"),
        doc("Banda de Outro", "A Rua Lá Fora", "banda-de-outro", "998877"),
        doc("Os Inventados", "A Rua Lá Fora ", "os-inventados", "112233"),
    )

    /** Verses separated by line breaks, a stanza per paragraph, inside other boxes. */
    private val page = """
        <html><body><div class="lyric"><div class="lyric-header"><h1>A Rua Lá Fora</h1></div>
        <div class="lyric-original"> <p>O dia acordou devagar<br>E a rua l&aacute; fora&nbsp;sem voc&ecirc;<br/>  </p><div class="ad"><div>anúncio</div></div><p>Quem &quot;diria&quot;<br>Que eu ia ficar</p></div>
        <div class="lyric-footer"><p>Composição: Alguém</p></div></div></body></html>
    """.trimIndent()

    @Test fun readsTheSongsOfASearchAnswer() {
        val hits = LetrasPage.hits(search)
        assertEquals(listOf("Banda de Outro", "Os Inventados"), hits.map { it.artist })
        assertEquals("A Rua Lá Fora", hits[1].title)
        assertEquals("https://www.letras.com/os-inventados/112233/", hits[1].url)
        // The same answer without the wrapping; and anything else is no answer.
        assertEquals(hits, LetrasPage.hits(search.removePrefix("LetrasSug(").removeSuffix(")")))
        assertTrue(LetrasPage.hits("<html>Access Denied</html>").isEmpty())
    }

    @Test fun readsTheWordsLineByLineWithStanzas() {
        assertTrue(LetrasPage.hasLyrics(page))
        assertEquals(
            "O dia acordou devagar\nE a rua lá fora sem você\n\nQuem \"diria\"\nQue eu ia ficar",
            LetrasPage.lyrics(page),
        )
    }

    @Test fun aLineInAnotherAlphabetComesWithoutItsReading() {
        val verses = """
            <div class="lyric-original font --lyrics"><p><span class="verse"><span>きみのなまえ</span><span class="romanization">kimi no namae</span></span><span class="verse"><span>そらのいろ</span><span class="romanization">sora no iro</span></span></p>
            <p><span class="verse"><span>かぜ</span><span class="romanization">kaze</span></span></p></div>
        """.trimIndent()
        assertEquals("きみのなまえ\nそらのいろ\n\nかぜ", LetrasPage.lyrics(verses))
    }

    @Test fun aTranslatedPageGivesTheOriginalSide() {
        val translated = """
            <div class="lyric-translation"><div class="lyric-translation-left"><p><span>First line</span><br><span>Second line</span></p></div>
            <div class="lyric-translation-right"><p><span>Primeira linha</span><br><span>Segunda linha</span></p></div></div>
        """.trimIndent()
        assertEquals("First line\nSecond line", LetrasPage.lyrics(translated))
    }

    @Test fun aPageWithoutWordsHasNone() {
        assertFalse(LetrasPage.hasLyrics("<html><title>Access Denied</title></html>"))
        assertNull(LetrasPage.lyrics("<div class=\"lyric-original-note\"><p>não é a letra</p></div>"))
        assertNull(LetrasPage.lyrics("<div class=\"lyric-original\"><p> <br> </p></div>"))
    }

    @Test fun findsTheSameSongByTheSameArtist() = runTest {
        val fake = OnlineFixtures.FakeHttp { req ->
            if (req.url.host == "solr.sscdn.co") 200 to search else 200 to page
        }
        val found = LetrasLyricsSource(fake.client, browser = null).find("A Rua Lá Fora (Ao Vivo)", "Os Inventados", null, 200_000)!!
        assertFalse(found.synced)
        assertTrue(found.text.startsWith("O dia acordou devagar\n"))
        val (asked, read) = fake.requests
        assertEquals("/letras/m1/", asked.url.encodedPath)
        assertEquals("Os Inventados A Rua Lá Fora", asked.url.queryParameter("q"))
        assertEquals("https://www.letras.com/os-inventados/112233/", read.url.toString())
        // The site turns away a request without these.
        assertTrue(read.header("Accept")!!.startsWith("text/html"))
        assertTrue(read.header("Accept-Language")!!.isNotBlank())
    }

    @Test fun aPageTurnedAwayIsReadThroughTheBrowser() = runTest {
        val fake = OnlineFixtures.FakeHttp { req ->
            if (req.url.host == "solr.sscdn.co") 200 to search else 403 to "<html><title>Access Denied</title></html>"
        }
        val opened = mutableListOf<String>()
        val source = LetrasLyricsSource(fake.client) { url -> opened += url; page }
        assertTrue(source.find("A Rua Lá Fora", "Os Inventados", null, null)!!.text.contains("Que eu ia ficar"))
        assertEquals(listOf("https://www.letras.com/os-inventados/112233/"), opened)
        // No browser at hand (or it found nothing): no lyrics, never an error.
        assertNull(LetrasLyricsSource(fake.client, browser = null).find("A Rua Lá Fora", "Os Inventados", null, null))
        assertNull(LetrasLyricsSource(fake.client) { "" }.find("A Rua Lá Fora", "Os Inventados", null, null))
    }

    @Test fun anotherArtistsSongIsNotThisOne() = runTest {
        val pages = mutableListOf<String>()
        val fake = OnlineFixtures.FakeHttp { req ->
            if (req.url.host == "solr.sscdn.co") 200 to answer(doc("Banda de Outro", "A Rua Lá Fora", "banda-de-outro", "998877"))
            else { pages += req.url.toString(); 200 to page }
        }
        assertNull(LetrasLyricsSource(fake.client, browser = null).find("A Rua Lá Fora", "Os Inventados", null, null))
        assertTrue(pages.isEmpty())
        // Asked with the artist, then with the title alone.
        assertEquals(listOf("Os Inventados A Rua Lá Fora", "A Rua Lá Fora"), fake.requests.map { it.url.queryParameter("q") })
        // Without a title or an artist nothing is asked at all.
        assertNull(LetrasLyricsSource(fake.client, browser = null).find("(Ao Vivo)", "Os Inventados", null, null))
        assertNull(LetrasLyricsSource(fake.client, browser = null).find("A Rua Lá Fora", " ", null, null))
        assertEquals(2, fake.requests.size)
    }

    @Test fun letrasIsAskedOnlyForWhatTheFirstSourceLacks() = runTest {
        val asked = mutableListOf<String>()
        fun source(name: String, answer: Lyrics?) = object : LyricsSource {
            override suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? {
                asked += name
                return answer
            }
        }
        val synced = Lyrics("[00:01.00]Uno", synced = true)
        val plain = Lyrics("Uno", synced = false)
        assertEquals(synced, LyricsSources(source("lrclib", synced), source("letras", plain)).find("t", "a", null, null))
        assertEquals(listOf("lrclib"), asked)
        asked.clear()
        assertEquals(plain, LyricsSources(source("lrclib", null), source("letras", plain)).find("t", "a", null, null))
        assertEquals(listOf("lrclib", "letras"), asked)
        assertNull(LyricsSources(source("lrclib", null), source("letras", null)).find("t", "a", null, null))
    }
}
