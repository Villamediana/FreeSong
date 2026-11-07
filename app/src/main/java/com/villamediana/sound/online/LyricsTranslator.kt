package com.villamediana.sound.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

/**
 * Lyrics in another language, translated line by line into the phone's language (Google's public
 * translate endpoint, no account). The whole song goes in one request and comes back with the
 * same line breaks. Results are kept on disk, so a song is translated once.
 */
class LyricsTranslator(
    private val http: OkHttpClient,
    private val file: File,
    /** Settings.translateLyrics. */
    val enabled: Flow<Boolean>,
    private val target: () -> String = { Locale.getDefault().language },
) {
    private val json = Json { ignoreUnknownKeys = true }
    /** Google answers 429 to this endpoint over HTTP/2 from OkHttp; over HTTP/1.1 it answers. */
    private val client: OkHttpClient = http.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
    private val lock = Mutex()
    /** key → translated lines; an empty list = already in the phone's language. */
    private var cache: LinkedHashMap<String, List<String>>? = null
    /** Songs being translated right now: a second view asking waits for the same answer. */
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<List<String>?>>()

    /**
     * The translation of [lines] (same size, null for blank lines), or null when the song is
     * already in the phone's language or it could not be translated.
     */
    suspend fun translate(lines: List<String>): List<String?>? {
        // Only the lines with words go out: blank ones (pauses, ♪) at the start or the end are
        // dropped by the translator, and then the lines no longer line up.
        val worded = lines.indices.filter { lines[it].isNotBlank() }
        if (worded.isEmpty()) return null
        val texts = worded.map { lines[it].trim() }
        val lang = target()
        val key = lang + ":v2:" + texts.joinToString("\n").hashCode().toString(16) + ":" + texts.size
        lock.withLock { load()[key] }?.let { return place(lines, worded, it) }
        val job = lock.withLock {
            inFlight.getOrPut(key) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                    val result = runCatching { requestAll(texts, lang) }.getOrNull()
                    lock.withLock {
                        inFlight.remove(key)
                        if (result != null) {
                            val c = load()
                            c[key] = result
                            while (c.size > MAX_ENTRIES) c.remove(c.keys.first())
                            save(c)
                        }
                    }
                    result
                }
            }
        }
        val result = job.await() ?: return null
        return place(lines, worded, result)
    }

    /** The worded lines' translations put back at their lines; null when there is nothing to show. */
    private fun place(lines: List<String>, worded: List<Int>, translated: List<String>): List<String?>? {
        if (translated.isEmpty()) return null
        val out = arrayOfNulls<String>(lines.size)
        worded.forEachIndexed { k, i ->
            out[i] = translated.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() && !it.equals(lines[i].trim(), ignoreCase = true) }
        }
        return out.toList().takeIf { list -> list.any { it != null } }
    }

    /**
     * The whole song in one request; when the lines don't come back one for one, in small parts
     * (a part that still doesn't line up stays untranslated, the rest shows). Empty = already in [lang].
     */
    private suspend fun requestAll(texts: List<String>, lang: String): List<String> {
        when (val whole = request(texts, lang)) {
            Answer.Same -> return emptyList()
            is Answer.Lines -> return whole.lines
            Answer.Misaligned -> Unit
        }
        val out = ArrayList<String>(texts.size)
        texts.chunked(CHUNK).forEach { part ->
            out += when (val a = request(part, lang)) {
                is Answer.Lines -> a.lines
                else -> List(part.size) { "" }
            }
        }
        return out
    }

    /** Forgets every translation (Ajustes › Armazenamento). */
    suspend fun clear() = lock.withLock {
        cache = LinkedHashMap()
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    fun bytes(): Long = file.takeIf { it.isFile }?.length() ?: 0L

    private sealed interface Answer {
        data object Same : Answer
        data object Misaligned : Answer
        data class Lines(val lines: List<String>) : Answer
    }

    /** One request for [lines]. Throws on a network failure. */
    private suspend fun request(lines: List<String>, lang: String): Answer = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("q", lines.joinToString("\n")).build()
        val request = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$lang&dt=t")
            .post(body)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val text = client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            r.body.string()
        }
        when (val parsed = parse(text, lang, lines.size)) {
            null -> Answer.Misaligned
            else -> if (parsed.isEmpty()) Answer.Same else Answer.Lines(parsed)
        }
    }

    private fun load(): LinkedHashMap<String, List<String>> = cache ?: LinkedHashMap<String, List<String>>().also { map ->
        runCatching {
            map.putAll(json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), file.readText()))
        }
        cache = map
    }

    private fun save(map: Map<String, List<String>>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), map))
        }
    }

    companion object {
        private const val MAX_ENTRIES = 300
        private const val CHUNK = 8

        /**
         * Reads the endpoint's answer: `[[["translated","original",…],…], null, "en", …]`. Empty
         * list when the detected language is [lang] already; null when the lines don't line up.
         */
        fun parse(text: String, lang: String, lineCount: Int): List<String>? {
            val root = runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrNull() ?: return null
            val detected = root.getOrNull(2)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (detected != null && detected.substringBefore('-').equals(lang.substringBefore('-'), ignoreCase = true)) return emptyList()
            val segments = root.getOrNull(0) as? JsonArray ?: return null
            val joined = segments.joinToString("") { seg ->
                ((seg as? JsonArray)?.getOrNull(0))?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
            }
            val out = joined.split("\n")
            return out.takeIf { it.size == lineCount }
        }
    }
}
