package com.villamediana.sound.discover

import com.villamediana.sound.online.CatalogMetadataEnricher
import com.villamediana.sound.smart.normalizeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * The genre of the artists the listener streams (YouTube doesn't say it): asked once per artist of
 * the iTunes catalog (the same the downloads' details come from), kept on disk for [KEEP_MS]. A few
 * artists at a time, the most listened first, so the genres learn themselves without a burst.
 */
class ArtistGenres(
    private val http: OkHttpClient,
    private val file: File,
    private val country: String = CatalogMetadataEnricher.defaultCountry(),
    private val baseUrl: String = "https://itunes.apple.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** genre = "" when the catalog had no such artist (not asked again until it expires). */
    @Serializable
    private data class Entry(val genre: String = "", val at: Long = 0)

    @Serializable
    private data class ITunesArtist(val wrapperType: String? = null, val artistName: String? = null, val primaryGenreName: String? = null)

    @Serializable
    private data class ITunesArtists(val results: List<ITunesArtist> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Mutex()
    private var cache: MutableMap<String, Entry>? = null

    private suspend fun load(): MutableMap<String, Entry> = cache ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<Map<String, Entry>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    }.also { cache = it }

    /** Artist key → genre name, for the artists already known. */
    suspend fun known(): Map<String, String> = lock.withLock {
        load().filterValues { it.genre.isNotBlank() }.mapValues { it.value.genre }
    }

    /**
     * Looks up up to [max] of [artists] (key → name as written, most listened first) not known yet
     * or expired. True when something new was learned.
     */
    suspend fun learn(artists: List<Pair<String, String>>, max: Int = 6): Boolean = lock.withLock {
        val now = clock()
        val map = load()
        val missing = artists.filter { (key, _) -> map[key]?.let { now - it.at > KEEP_MS } ?: true }.take(max)
        if (missing.isEmpty()) return@withLock false
        var learned = false
        for ((i, pair) in missing.withIndex()) {
            val (key, name) = pair
            if (i > 0) delay(GAP_MS)
            val genre = try {
                lookup(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: break // offline or refused: the rest waits for next time
            map[key] = Entry(genre, now)
            if (genre.isNotBlank()) learned = true
        }
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString<Map<String, Entry>>(map))
            }
        }
        learned
    }

    /** The artist's genre ("" = not in the catalog); null when the catalog couldn't be asked. */
    private suspend fun lookup(name: String): String? {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", name)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "musicArtist")
            .addQueryParameter("limit", "5")
            .addQueryParameter("country", country)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", CatalogMetadataEnricher.USER_AGENT).header("Accept", "application/json").build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { r -> if (!r.isSuccessful) null else pick(r.body.string(), name) }
        }
    }

    /** The genre of the result named like [name] (accents and case aside); "" when none is. */
    internal fun pick(body: String, name: String): String {
        val want = normalizeText(name)
        val results = runCatching { json.decodeFromString(ITunesArtists.serializer(), body).results }.getOrDefault(emptyList())
        return results.firstOrNull { normalizeText(it.artistName) == want }?.primaryGenreName.orEmpty()
    }

    private companion object {
        const val KEEP_MS = 60L * 24 * 3_600_000L
        /** Apple allows about 20 calls a minute: these few go calmly. */
        const val GAP_MS = 700L
    }
}
