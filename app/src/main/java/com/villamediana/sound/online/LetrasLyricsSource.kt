package com.villamediana.sound.online

import com.villamediana.sound.chords.CifraMatch
import com.villamediana.sound.chords.HtmlEntities
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

/**
 * The first of [sources] that has the song's lyrics, asked in order. A source that couldn't be
 * asked (offline, server trouble) doesn't count as not having them: what a later one finds then is
 * [Lyrics.provisional] — shown, not kept, so the song gets the first one's (synced) words once it
 * is back. An instrumental (found, no words) ends the search. Never throws.
 */
class LyricsSources(private vararg val sources: LyricsSource) : LyricsSource {
    override suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? {
        var missedOne = false
        for (source in sources) {
            when (val got = source.lookup(title, artist, album, durationMs)) {
                is LyricsLookup.Found -> return got.lyrics.takeIf { it.text.isNotBlank() }?.let { if (missedOne) it.copy(provisional = true) else it }
                LyricsLookup.Unreachable -> missedOne = true
                LyricsLookup.Missing -> Unit
            }
        }
        return null
    }
}

/**
 * Lyrics from Letras (letras.com), for the songs LRCLIB doesn't have. Its search answers anyone; the
 * page is asked plainly first and, when that is turned away, read through [browser] — the phone's
 * own browser engine, as the chords do with Cifra Club. Only the same title by the same artist
 * counts. The words come without timing. Never throws.
 */
class LetrasLyricsSource(
    private val http: OkHttpClient,
    private val browser: (suspend (String) -> String)?,
) : LyricsSource {

    override suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? {
        if (CifraMatch.cleanTitle(title).isBlank() || artist.isBlank()) return null
        return try {
            withContext(Dispatchers.IO) { lookup(title.trim(), artist.trim()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun lookup(title: String, artist: String): Lyrics? {
        // The search wants every word: an artist written another way finds nothing, so the title
        // alone is asked next (and still only this artist's song is taken).
        val hit = CifraMatch.pick(search(CifraMatch.query(title, artist)), title, artist)
            ?: CifraMatch.pick(search(CifraMatch.cleanTitle(title)), title, artist)
            ?: return null
        val html = page(hit.url) ?: return null
        return LetrasPage.lyrics(html)?.let { Lyrics(it, synced = false) }
    }

    private fun search(query: String): List<CifraMatch.Hit> {
        val body = get("https://solr.sscdn.co/letras/m1/?q=${URLEncoder.encode(query, "UTF-8")}&wt=json") ?: return emptyList()
        return LetrasPage.hits(body)
    }

    /** The page: asked plainly first (in case it's let through), then through the browser engine. */
    private suspend fun page(url: String): String? {
        runCatching { get(url) }.getOrNull()?.takeIf { LetrasPage.hasLyrics(it) }?.let { return it }
        val read = browser ?: return null
        return try {
            withTimeoutOrNull(PAGE_TIMEOUT_MS) { read(url) }?.takeIf { LetrasPage.hasLyrics(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String): String? {
        // The site turns away a request without Accept and Accept-Language.
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
        /** The element that holds the words: the page is read as soon as it is there. */
        const val READY = ".lyric-original, .lyric-translation-left"

        /** The site answers under both names (letras.mus.br is the Brazilian one): the browser may follow either. */
        val SITES = setOf("letras.com", "letras.mus.br")
        private const val PAGE_TIMEOUT_MS = 25_000L
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}

/** Reading Letras: its search's answers and a song page's words. */
internal object LetrasPage {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The songs in a search answer (plain JSON, or wrapped JSONP style), in the site's order. */
    fun hits(body: String): List<CifraMatch.Hit> {
        val open = body.indexOf('{')
        val close = body.lastIndexOf('}')
        if (open < 0 || close <= open) return emptyList()
        val docs = runCatching {
            json.parseToJsonElement(body.substring(open, close + 1)).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return docs.mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = d[k]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            // t = 2: a song (1 is an artist, 3 an album).
            if (s("t") != "2" || s("dns").isEmpty() || s("url").isEmpty()) return@mapNotNull null
            CifraMatch.Hit(s("art"), s("txt"), "https://www.letras.com/${s("dns")}/${s("url")}/")
        }
    }

    // The words' own box (beside its translation on a translated page, alone otherwise).
    private val BOX = Regex("<div\\b[^>]*\\bclass=\"[^\"]*(?<![\\w-])(?:lyric-translation-left|lyric-original)(?![\\w-])[^\"]*\"[^>]*>")
    private val DIV = Regex("<div\\b|</div\\s*>")
    private val STANZA = Regex("<p\\b[^>]*>([\\s\\S]*?)</p\\s*>")
    // How a line in another alphabet is read: written right after the line, not part of the words.
    private val READING = Regex("<span\\b[^>]*\\bclass=\"[^\"]*(?<![\\w-])romanization(?![\\w-])[^\"]*\"[^>]*>[\\s\\S]*?</span\\s*>")
    private val VERSE = Regex("<span\\b[^>]*\\bclass=\"[^\"]*(?<![\\w-])verse(?![\\w-])[^\"]*\"[^>]*>")
    private val BREAK = Regex("<br\\b[^>]*>")
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("[ \\t\\u00A0\\u200B]+")

    fun hasLyrics(html: String): Boolean = BOX.containsMatchIn(html)

    /**
     * The song's words: one line per verse, a blank line between stanzas. Null when the page has
     * none (an instrumental, another kind of page).
     */
    fun lyrics(html: String): String? {
        val box = box(html) ?: return null
        val blocks = STANZA.findAll(box).map { it.groupValues[1] }.toList().ifEmpty { listOf(box) }
        val stanzas = blocks.map { block ->
            val marked = VERSE.replace(READING.replace(block, ""), "\n")
            HtmlEntities.decode(TAG.replace(BREAK.replace(marked, "\n"), ""))
                .lines()
                .map { SPACES.replace(it, " ").trim() }
                .filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
        if (stanzas.isEmpty()) return null
        return stanzas.joinToString("\n\n") { it.joinToString("\n") }
    }

    /** What is inside the words' box, up to the tag that closes it. */
    private fun box(html: String): String? {
        val start = BOX.find(html) ?: return null
        val from = start.range.last + 1
        var depth = 1
        for (m in DIV.findAll(html, from)) {
            if (m.value.startsWith("</")) depth-- else depth++
            if (depth == 0) return html.substring(from, m.range.first)
        }
        return html.substring(from)
    }
}
