package com.villamediana.sound.chords

import com.villamediana.sound.data.Lrc
import com.villamediana.sound.online.LrclibLyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real Cifra Club sheets over real lyrics (LRCLIB), end to end on this machine: SOUND_LIVE=1 and
 * SOUND_CIFRA_DIR=dir with the pages saved as `<slug>.html` (read with a browser — the site turns
 * an HTTP library away; see tools/chords/README.md). Prints each song as the phone shows it, and
 * checks the search picks the right sheet (another artist's only when its words are the song's).
 */
class CifraClubLiveTest {
    private val dir = System.getProperty("sound.cifra.dir").orEmpty()

    private data class Song(val slug: String, val title: String, val artist: String, val url: String)

    private val songs = listOf(
        // Miguel's version is Jorge Luis Chacín's; Cifra Club only has Ricardo Arjona's.
        Song("si-usted-la-viera", "Si Usted la Viera", "Jorge Luis Chacín", "https://www.cifraclub.com.br/arjona-ricardo/si-usted-la-viera/"),
        Song("tempo-perdido", "Tempo Perdido", "Legião Urbana", "https://www.cifraclub.com.br/legiao-urbana/tempo-perdido/"),
        Song("let-it-be", "Let It Be (Remastered 2009)", "The Beatles", "https://www.cifraclub.com.br/the-beatles/let-it-be/"),
        Song("evidencias", "Evidências", "Chitãozinho & Xororó", "https://www.cifraclub.com.br/chitaozinho-e-xororo/evidencias/"),
        Song("perfect", "Perfect", "Ed Sheeran", "https://www.cifraclub.com.br/ed-sheeran/perfect/"),
    )

    @Test fun sheetsFitTheLyrics() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1" && dir.isNotBlank())
        val http = OkHttpClient()
        val lrclib = LrclibLyricsSource(http)
        for (song in songs) {
            val html = File(dir, "${song.slug}.html").takeIf { it.isFile }?.readText() ?: continue
            val page = CifraClubPage.parse(html) ?: error("${song.slug}: no sheet")
            val found = lrclib.find(CifraMatch.cleanTitle(song.title), song.artist, null, null) ?: error("${song.slug}: no lyrics")
            val lyrics = Lrc.parse(found.text)
            val sheet = WrittenSheet(page.lines, CifraClub.SOURCE, song.url, page.capo)
            val chart = ChordChart(emptyList(), page.key, sheet = sheet, looked = true)
            val view = ChordLayout.view(lyrics, chart, 0)
            val names = chart.labels()
            val sung = view.rows.filterIsInstance<ChordRow.Lyric>().filter { it.text.isNotBlank() }
            val withChords = sung.count { it.chords.isNotEmpty() }
            val placed = view.rows.sumOf { r -> if (r is ChordRow.Lyric) r.chords.size else (r as ChordRow.Instrumental).chords.size }
            println("===== ${song.slug}: key ${page.key?.name()} capo ${page.capo}, synced ${lyrics.synced}, follows ${view.follows}")
            println("      ${sung.size} lines, $withChords with chords; ${names.size} chords written, $placed shown; fits ${SheetLayout.fits(lyrics, sheet)}")
            for (r in view.rows.take(40)) {
                when (r) {
                    is ChordRow.Instrumental -> println("  ♪ " + r.chords.joinToString("  ") { names[it] })
                    is ChordRow.Lyric -> {
                        if (r.chords.isNotEmpty()) {
                            val line = StringBuilder()
                            for (c in r.chords) {
                                val at = maxOf(c.at, if (line.isEmpty()) 0 else line.length + 1)
                                while (line.length < at) line.append(' ')
                                line.append(names[c.index])
                            }
                            println("    $line")
                        }
                        println("    ${r.text}")
                    }
                }
            }
            // Sheets write a verse's chords once and leave the next verses bare: what counts is that
            // the chords written found their words.
            val written = page.lines.sumOf { it.chords.size }
            assertTrue("${song.slug}: $placed of $written chords shown", placed >= written * 0.8)
        }
    }

    @Test fun searchPicksTheSheet() = runBlocking {
        assumeTrue(System.getProperty("sound.live") == "1" && dir.isNotBlank())
        val http = OkHttpClient()
        val lrclib = LrclibLyricsSource(http)
        // The page read from the saved file, as the phone's browser would.
        val club = CifraClub(http) { url -> File(dir, url.trimEnd('/').substringAfterLast('/') + ".html").readText() }
        for (song in songs) {
            if (!File(dir, "${song.slug}.html").isFile) continue
            val result = club.lookup(song.title, song.artist) {
                lrclib.find(CifraMatch.cleanTitle(song.title), song.artist, null, null)?.let { Lrc.parse(it.text) }
            }
            val sheet = (result as? SheetLookup.Found)?.chart?.sheet
            println("${song.slug}: $result".take(160) + " → ${sheet?.url} ${sheet?.versionOf}")
            assertEquals(song.slug, song.url, sheet?.url)
        }
        // A song Cifra Club doesn't have.
        assertEquals(SheetLookup.Missing, club.lookup("Uma Canção Que Ninguém Escreveu", "Ninguém Sabe") { null })
    }
}
