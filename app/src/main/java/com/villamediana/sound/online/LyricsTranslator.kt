package com.villamediana.sound.online

import kotlinx.coroutines.Dispatchers
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
    private val lock = Mutex()
    /** key → translated lines; an empty list = already in the phone's language. */
    private var cache: LinkedHashMap<String, List<String>>? = null

    /**
     * The translation of [lines] (same size, null for blank lines), or null when the song is
     * already in the phone's language or it could not be translated.
     */
    suspend fun translate(lines: List<String>): List<String?>? {
        if (lines.none { it.isNotBlank() }) return null
        val lang = target()
        val key = lang + ":" + lines.joinToString("\n").hashCode().toString(16) + ":" + lines.size
        lock.withLock { load()[key] }?.let { return it.ifEmpty { null }?.let { t -> align(lines, t) } }
        val result = runCatching { request(lines, lang) }.getOrNull() ?: return null
        lock.withLock {
            val c = load()
            c[key] = result
            while (c.size > MAX_ENTRIES) c.remove(c.keys.first())
            save(c)
        }
        return result.ifEmpty { null }?.let { align(lines, it) }
    }

    /** Forgets every translation (Ajustes › Armazenamento). */
    suspend fun clear() = lock.withLock {
        cache = LinkedHashMap()
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    fun bytes(): Long = file.takeIf { it.isFile }?.length() ?: 0L

    /** Blank lines (pauses, ♪) stay without a translation. */
    private fun align(lines: List<String>, translated: List<String>): List<String?> =
        lines.mapIndexed { i, l -> if (l.isBlank()) null else translated.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && !it.equals(l.trim(), ignoreCase = true) } }

    /** Translated lines, or an empty list when the lyrics are already in [lang]. Throws on failure. */
    private suspend fun request(lines: List<String>, lang: String): List<String> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("q", lines.joinToString("\n")).build()
        val request = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$lang&dt=t")
            .post(body)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val text = http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            r.body.string()
        }
        parse(text, lang, lines.size) ?: error("Unexpected translation")
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
