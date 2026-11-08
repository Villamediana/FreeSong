package com.villamediana.sound.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading Cifra Club's pages and search (made-up song, the site's markup as it is in 2026). */
class CifraClubTest {
    private fun b(name: String, trailing: String = "  ") =
        "<b data-chord-name=\"$name\" data-chord-index=\"0\" data-chord-original-text=\"$name\" data-chord-original-trailing=\"$trailing\">$name</b>"

    /** A page: key G played with E shapes at the 3rd fret, intro, a tab part straight into the verse. */
    private val page = buildString {
        append("<div class=\"IERZz\"><span>Tom<!-- -->: </span> <button type=\"button\" class=\"eVroG\">G<!-- --> (com forma de E)</button></div>")
        append("<div class=\"IERZz\"><span>Capotraste<!-- -->:</span> <button type=\"button\">3ª casa</button></div>")
        append("<pre class=\"_crVx\" data-chord-content=\"true\">")
        append("<div class=\"kvMV\">[Intro] ${b("E")}  ${b("C#m7")}  ${b("A7M(9)")}  ${b("B4")}\n</div>")
        append("<div class=\"kvMV\">\n</div>")
        append("<div class=\"kvMV\">[Tab - Intro]\n</div>")
        append("<div class=\"kvMV\">   ${b("E")}\nE|-----0-----0-----| \nB|---0-----0-------| \nG|-1-----1---------| \n\n</div>")
        append("<div class=\"kvMV\">[Primeira Parte]\n</div>")
        append("<div class=\"kvMV\">\n</div>")
        append("<div class=\"kvMV\">[Dedilhado - Primeira Parte]\n</div>")
        append("<div class=\"kvMV\">Parte 1 De 2\n   ${b("E")}\nE|--0--0--0--| \n\n</div>")
        // The verse comes right after the tabs, no part name in between; every line starts with a
        // zero-width space, and the chord's attribute carries the line under it.
        append("<div class=\"kvMV\">​       ${b("E", "\n​O dia acordou\n")}\n​O dia acordou devagar\n</div>")
        append("<div class=\"kvMV\">​${b("C#m7", "        ")}        ${b("A7M(9)")}\n​E a rua lá fora&nbsp;sem voc&ecirc;\n</div>")
        append("<div class=\"kvMV\">​Uma linha sem acorde\n</div>")
        append("<div class=\"kvMV\">\n</div>")
        append("<div class=\"kvMV\">[Solo] ${b("A")}  ${b("B")}  ${b("E")}  ${b("E")}\n</div>")
        append("</pre>")
    }

    @Test fun readsTheSheetTheKeyAndTheCapo() {
        val read = CifraClubPage.parse(page)!!
        assertEquals(MusicKey(4, false), read.key) // written in E's shapes
        assertEquals(3, read.capo)
        assertEquals(
            listOf(
                SheetLine("", listOf(SheetChord(8, "E"), SheetChord(11, "C#m7"), SheetChord(17, "A7M(9)"), SheetChord(25, "B4"))),
                SheetLine(""),
                SheetLine("O dia acordou devagar", listOf(SheetChord(7, "E"))),
                SheetLine("E a rua lá fora sem você", listOf(SheetChord(0, "C#m7"), SheetChord(12, "A7M(9)"))),
                SheetLine("Uma linha sem acorde"),
                SheetLine(""),
                SheetLine("", listOf(SheetChord(7, "A"), SheetChord(10, "B"), SheetChord(13, "E"), SheetChord(16, "E"))),
            ),
            read.lines,
        )
    }

    @Test fun aPageWithATabOnlyHasNoSheet() {
        val tabOnly = "<pre><div class=\"kvMV\">[Tab - Riff]\n   ${b("E")}\nE|---0---| \n</div></pre>"
        assertNull(CifraClubPage.parse(tabOnly))
        assertNull(CifraClubPage.parse("<html>nothing</html>"))
    }

    @Test fun readsTheSearchAnswer() {
        val body = """({"response":{"docs":[
            {"t":"1","a":"Legião Urbana","d":"legiao-urbana","u":""},
            {"t":"2","m":"Tempo Perdido","a":"Legião Urbana","d":"legiao-urbana","u":"tempo-perdido"},
            {"t":"2","m":"Tempo Perdido (Ao Vivo)","a":"Outra Banda","d":"outra-banda","u":"tempo-perdido"}]}})"""
        assertEquals(
            listOf(
                CifraMatch.Hit("Legião Urbana", "Tempo Perdido", "https://www.cifraclub.com.br/legiao-urbana/tempo-perdido/"),
                CifraMatch.Hit("Outra Banda", "Tempo Perdido (Ao Vivo)", "https://www.cifraclub.com.br/outra-banda/tempo-perdido/"),
            ),
            CifraMatch.hits(body),
        )
        assertEquals(emptyList<CifraMatch.Hit>(), CifraMatch.hits("not json"))
    }

    @Test fun picksTheSameSongByTheSameArtist() {
        val hits = listOf(
            CifraMatch.Hit("Banda Errada", "Evidências", "u0"),
            CifraMatch.Hit("Chitãozinho & Xororó", "Evidências", "u1"),
            CifraMatch.Hit("Chitãozinho & Xororó", "Evidências (Ao Vivo)", "u2"),
        )
        assertEquals("u1", CifraMatch.pick(hits, "Evidências", "Chitãozinho e Xororó")?.url)
        assertEquals("u1", CifraMatch.pick(hits, "Evidencias - Ao Vivo", "Chitãozinho & Xororó - Topic")?.url)
        assertEquals("u1", CifraMatch.pick(hits, "Evidências (Remastered)", "ChitaozinhoXororoVEVO")?.url)
        assertNull(CifraMatch.pick(hits, "Evidências", "Outro Cantor"))
        assertNull(CifraMatch.pick(hits, "Outra Música", "Chitãozinho & Xororó"))
        // Someone else's version of the same title is still a candidate, to be checked by its words.
        assertEquals(listOf("u0", "u1", "u2"), CifraMatch.sameTitle(hits, "Evidências").map { it.url })
    }

    @Test fun titlesLoseWhatVersionsAdd() {
        assertEquals("Let It Be", CifraMatch.cleanTitle("Let It Be (Remastered 2009)"))
        assertEquals("Garota de Ipanema", CifraMatch.cleanTitle("Garota de Ipanema - Ao Vivo"))
        assertEquals("Perfect", CifraMatch.cleanTitle("Perfect feat. Beyoncé"))
        assertEquals("Ai Se Eu Te Pego", CifraMatch.cleanTitle("Ai Se Eu Te Pego [Clipe Oficial]"))
        assertEquals("Ed Sheeran Perfect", CifraMatch.query("Perfect (Official Audio)", "Ed Sheeran"))
    }
}
