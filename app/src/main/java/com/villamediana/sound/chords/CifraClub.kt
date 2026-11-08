package com.villamediana.sound.chords

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Chord sheets from Cifra Club, written by players — asked before the song is heard. Its search
 * answers anyone; its pages turn an HTTP library away by the shape of the connection (Akamai), so
 * a page that is refused is read through [browser], the phone's own browser engine, as the Luna does.
 * Only a sheet of this very song counts: the same title by the same artist — or, when the site only
 * has someone else's version (a cover, or the original of a cover), one whose words are the song's
 * own lyrics.
 */
internal class CifraClub(
    private val http: OkHttpClient,
    private val browser: (suspend (String) -> String)?,
) {
    /** [lyrics]: the song's own, asked for only to check another artist's version. */
    suspend fun lookup(title: String, artist: String, lyrics: suspend () -> com.villamediana.sound.data.ParsedLyrics? = { null }): SheetLookup = withContext(Dispatchers.IO) {
        if (CifraMatch.cleanTitle(title).isBlank()) return@withContext SheetLookup.Missing
        val hits = try {
            // The search wants every word: a name the site doesn't know (a cover's singer) finds
            // nothing, so the title alone is asked next.
            val both = search(CifraMatch.query(title, artist))
            if (CifraMatch.pick(both, title, artist) != null) both
            else (both + search(CifraMatch.cleanTitle(title))).distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SheetLookup.Unreachable
        }
        CifraMatch.pick(hits, title, artist)?.let { hit ->
            val html = page(hit.url) ?: return@withContext SheetLookup.Unreachable
            val page = CifraClubPage.parse(html) ?: return@withContext SheetLookup.Missing
            return@withContext SheetLookup.Found(chart(page, hit, versionOf = ""))
        }
        val others = CifraMatch.sameTitle(hits, title).take(2)
        if (others.isEmpty()) return@withContext SheetLookup.Missing
        val ours = lyrics()?.takeIf { l -> l.lines.any { it.text.isNotBlank() } } ?: return@withContext SheetLookup.Missing
        var reached = false
        for (hit in others) {
            val html = page(hit.url) ?: continue
            reached = true
            val page = CifraClubPage.parse(html) ?: continue
            val sheet = WrittenSheet(page.lines, SOURCE, hit.url, page.capo, versionOf = hit.artist)
            if (SheetLayout.fits(ours, sheet)) return@withContext SheetLookup.Found(chart(page, hit, versionOf = hit.artist))
        }
        if (reached) SheetLookup.Missing else SheetLookup.Unreachable
    }

    private fun chart(page: CifraClubPage.Page, hit: CifraMatch.Hit, versionOf: String) = ChordChart(
        chords = emptyList(),
        key = page.key,
        sheet = WrittenSheet(page.lines, SOURCE, hit.url, page.capo, versionOf),
        looked = true,
    )

    private fun search(query: String): List<CifraMatch.Hit> {
        val body = get("https://solr.sscdn.co/cc/h2/?q=${URLEncoder.encode(query, "UTF-8")}&wt=json")
            ?: throw java.io.IOException("search refused")
        return CifraMatch.hits(body)
    }

    /** The page: asked plainly first (in case it's let through), then through the browser engine. */
    private suspend fun page(url: String): String? {
        runCatching { get(url) }.getOrNull()?.takeIf { "<pre" in it }?.let { return it }
        val read = browser ?: return null
        return try {
            withTimeoutOrNull(PAGE_TIMEOUT_MS) { read(url) }?.takeIf { "<pre" in it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "pt-BR,pt;q=0.9,es;q=0.8,en;q=0.7")
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body.string()
        }
    }

    companion object {
        const val SOURCE = "Cifra Club"
        private const val PAGE_TIMEOUT_MS = 25_000L
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}

/** Which of the search's answers is the song playing. */
internal object CifraMatch {
    data class Hit(val artist: String, val title: String, val url: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The songs in a search answer (wrapped in parentheses, JSONP style), in the site's order. */
    fun hits(body: String): List<Hit> {
        val text = body.trim().removePrefix("(").removeSuffix(")")
        val docs = runCatching { json.parseToJsonElement(text).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray }.getOrNull()
            ?: return emptyList()
        return docs.mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = d[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            // t = 2: a song (1 is an artist).
            if (s("t") != "2" || s("d").isEmpty() || s("u").isEmpty()) return@mapNotNull null
            Hit(s("a"), s("m"), "https://www.cifraclub.com.br/${s("d")}/${s("u")}/")
        }
    }

    fun query(title: String, artist: String): String = "${cleanArtist(artist)} ${cleanTitle(title)}".trim()

    /** The first answer with the same title and a matching artist; none when nothing is this song. */
    fun pick(hits: List<Hit>, title: String, artist: String): Hit? {
        val a = cleanArtist(artist)
        return sameTitle(hits, title).firstOrNull { sameArtist(a, it.artist) }
    }

    /** The answers with the same title, whoever sings them. */
    fun sameTitle(hits: List<Hit>, title: String): List<Hit> {
        val t = norm(cleanTitle(title))
        return hits.filter { sameTitle(t, norm(cleanTitle(it.title))) }
    }

    /** The title without what versions add: "(Ao Vivo)", "[Remastered]", "- Acústico", "feat. …". */
    fun cleanTitle(title: String): String = title
        .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
        .replace(Regex("(?i)\\s+(feat\\.?|ft\\.?|part\\.)\\s.*$"), "")
        .replace(Regex("\\s+[-–—|]\\s+.*$"), "")
        .trim()

    private fun cleanArtist(artist: String): String = artist
        .replace(Regex("(?i)\\s*-\\s*topic$"), "")
        .replace(Regex("(?i)vevo$"), "")
        .trim()

    private fun sameTitle(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b || a.replace(" ", "") == b.replace(" ", "")) return true
        val wa = a.split(' ')
        val wb = b.split(' ')
        val common = wa.count { it in wb }
        return 2.0 * common / (wa.size + wb.size) >= 0.8
    }

    private val STOP = setOf("e", "y", "and", "the", "de", "da", "do", "los", "las", "la", "el", "feat", "ft", "part", "com", "con", "with")

    private fun sameArtist(ours: String, theirs: String): Boolean {
        val a = norm(ours)
        val b = norm(theirs)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.replace(" ", "") == b.replace(" ", "")) return true
        val wa = a.split(' ').filter { it.length >= 2 && it !in STOP }.toSet()
        val wb = b.split(' ').filter { it.length >= 2 && it !in STOP }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        return (wa intersect wb).size.toDouble() / minOf(wa.size, wb.size) >= 0.5
    }

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

/** A Cifra Club page read: its sheet, the key its chords are written in and the capo. */
internal object CifraClubPage {
    data class Page(val lines: List<SheetLine>, val key: MusicKey?, val capo: Int)

    private val PRE = Regex("<pre[^>]*>([\\s\\S]*?)</pre>")
    private val TOM = Regex("Tom(?:<!--\\s*-->)?:\\s*</span>\\s*<[^>]+>\\s*([A-G][#b]?m?)(?:<!--\\s*-->)?\\s*(?:\\(com forma de ([A-G][#b]?m?)\\))?")
    private val CAPO = Regex("Capotraste(?:<!--\\s*-->)?:\\s*</span>[\\s\\S]{0,200}?(\\d{1,2})ª")

    /** Null when the page has no chords (a tab only, or no sheet at all). */
    fun parse(html: String): Page? {
        val pre = PRE.find(html)?.groupValues?.get(1) ?: return null
        val lines = sheet(pre)
        if (lines.none { it.chords.isNotEmpty() }) return null
        val tom = TOM.find(html)
        // With a capo the sheet is written in the shapes' key ("com forma de G"): that's how its names are spelled.
        val written = (tom?.groupValues?.get(2)?.takeIf { it.isNotEmpty() } ?: tom?.groupValues?.get(1))?.let(::key)
        val capo = CAPO.find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: 0
        val key = written ?: Keys.ofChords(lines.flatMap { l -> l.chords.mapNotNull { Chord.closest(it.name) } })
        return Page(lines, key, capo)
    }

    private fun key(name: String): MusicKey? {
        val pc = Chord.pitchClass(name.removeSuffix("m")) ?: return null
        return MusicKey(pc, name.endsWith("m"))
    }

    private data class Raw(val plain: String, val chords: List<SheetChord>, val onlyChords: Boolean)

    private val SECTION = Regex("^\\[(.+?)\\]\\s*(.*)$")
    private val TAB = Regex("^\\s*[A-Ga-g]?[#b]?\\s*\\|.*$")
    private val CHORD_B = Regex("<b>([^<]*)</b>")
    private val LEFTOVER = Regex("^[\\s()|xX0-9\\-/.%:*~>]*$")

    /** "Parte 1 de 4": how the site numbers the pieces of a fingerpicking part. */
    private val PART = Regex("(?i)^parte \\d+ de \\d+$")

    /**
     * The sheet's lines. A line of chords alone goes over the line of words under it, the chords
     * keeping their columns; tabs and fingerpicking parts are left out (they aren't chords and don't
     * fit a phone), and so are the chords written over them.
     */
    fun sheet(pre: String): List<SheetLine> {
        val clean = pre
            .replace(Regex("<b\\s[^>]*>"), "<b>")
            .replace(Regex("\\n?</div>"), "\n")
            .replace(Regex("<(?!/?b>)[^>]+>"), "")
        val raws = HtmlEntities.decode(clean)
            .replace("\u200B", "").replace('\u00A0', ' ').replace("\r", "")
            .split('\n')
            .map(::raw)

        fun tab(r: Raw) = TAB.matches(r.plain) && r.plain.count { it == '-' } >= 3
        fun words(r: Raw) = r.plain.isNotBlank() && !r.onlyChords && !tab(r) &&
            SECTION.find(r.plain.trim()) == null && !PART.matches(r.plain.trim())
        val out = mutableListOf<SheetLine>()
        // In a tab or fingerpicking part, until its next part or until the words come back (some
        // sheets go straight from the tabs to the verse).
        var skipping = false
        var i = 0
        while (i < raws.size) {
            val r = raws[i]
            val next = raws.getOrNull(i + 1)
            val section = SECTION.find(r.plain.trim())
            if (skipping && section == null) {
                if (words(r) || (r.onlyChords && next != null && words(next))) skipping = false
                else {
                    i++
                    continue
                }
            }
            when {
                section != null && (r.chords.isEmpty() || r.onlyChords) -> {
                    val name = CifraMatch.norm(section.groupValues[1])
                    skipping = name.startsWith("tab") || name.startsWith("dedilhado") || name.startsWith("riff")
                    if (!skipping && r.chords.isNotEmpty()) out += SheetLine("", r.chords)
                }
                tab(r) || PART.matches(r.plain.trim()) -> Unit
                // Chords over a tab belong to the tab.
                r.onlyChords && next != null && tab(next) -> Unit
                r.onlyChords -> {
                    if (next != null && words(next)) {
                        val margin = minOf(r.plain.takeWhile { it == ' ' }.length, next.plain.takeWhile { it == ' ' }.length)
                        out += SheetLine(next.plain.drop(margin).trimEnd(), r.chords.map { it.copy(column = (it.column - margin).coerceAtLeast(0)) })
                        i++
                    } else {
                        out += SheetLine("", r.chords)
                    }
                }
                r.plain.isBlank() -> if (out.isNotEmpty() && out.last() != SheetLine("")) out += SheetLine("")
                else -> out += SheetLine(r.plain.trim())
            }
            i++
        }
        return out.dropLastWhile { it == SheetLine("") }
    }

    private fun raw(line: String): Raw {
        val chords = mutableListOf<SheetChord>()
        val plain = StringBuilder()
        var at = 0
        for (m in CHORD_B.findAll(line)) {
            plain.append(line, at, m.range.first)
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty() && name[0] in 'A'..'G') chords += SheetChord(plain.length, name)
            plain.append(m.groupValues[1])
            at = m.range.last + 1
        }
        plain.append(line.substring(at).replace(Regex("</?b>"), ""))
        val text = plain.toString().trimEnd()
        // Chords alone: what's left once their names are out is nothing, bars, repeats ("2x").
        val rest = StringBuilder(text)
        for (c in chords.reversed()) rest.replace(c.column, minOf(rest.length, c.column + c.name.length), " ".repeat(c.name.length))
        val only = chords.isNotEmpty() && LEFTOVER.matches(rest.toString().replace(Regex("\\[[^\\]]*\\]"), ""))
        return Raw(text, chords, only)
    }
}

/**
 * HTML character references as chord sites write their lyrics: numeric ones (&#233; &#xE9;) and
 * the named ones, Latin-1 among them, so "voc&ecirc;" reads "você". An unknown name stays as it is.
 */
internal object HtmlEntities {
    private val ENTITY = Regex("&#[xX]([0-9a-fA-F]+);|&#(\\d+);|&([a-zA-Z][a-zA-Z0-9]*);")

    private val LATIN1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
        "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
        "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
        "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
        "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
        "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml")
        .split(' ').mapIndexed { i, name -> name to (0xA0 + i).toChar().toString() }

    private val NAMED = (LATIN1 + listOf(
        "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "amp" to "&",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "minus" to "−", "sharp" to "♯", "flat" to "♭",
    )).toMap()

    /** Until nothing changes, a few rounds at most: some pages escape twice ("&amp;ecirc;"). */
    fun decode(s: String): String {
        var text = s
        repeat(3) {
            if ('&' !in text) return text
            val next = ENTITY.replace(text) { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault(m.value)
                    m.groupValues[2].isNotEmpty() -> runCatching { String(Character.toChars(m.groupValues[2].toInt())) }.getOrDefault(m.value)
                    else -> NAMED[m.groupValues[3]] ?: m.value
                }
            }
            if (next == text) return text
            text = next
        }
        return text
    }
}
