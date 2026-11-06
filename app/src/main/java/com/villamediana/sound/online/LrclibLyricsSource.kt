package com.villamediana.sound.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Lyrics from lrclib.net: exact lookup by artist/title/album/duration, then a search picking the
 * closest duration. Synced LRC is preferred over plain text; instrumentals have none. Never throws.
 */
class LrclibLyricsSource(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://lrclib.net",
) : LyricsSource {

    override suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? {
        if (title.isBlank() || artist.isBlank()) return null
        return try {
            withContext(Dispatchers.IO) { lookup(title.trim(), artist.trim(), album?.trim()?.ifEmpty { null }, durationMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun lookup(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? {
        val seconds = durationMs?.takeIf { it > 0 }?.let { (it + 500) / 1000 }
        val exact = get(title, artist, album, seconds)
        when (exact) {
            is Fetch.Found -> {
                if (exact.record.instrumental) return null
                LrclibMatcher.lyricsOf(exact.record)?.let { return it }
            }
            Fetch.Failed -> return null // offline or server trouble: don't hammer the search endpoint too
            Fetch.NotFound -> Unit
        }
        val baseTitle = TitleCleaner.baseTitle(title)
        val primaryArtist = TitleCleaner.primaryArtist(artist)
        val results = search(baseTitle, primaryArtist) ?: return null
        return LrclibMatcher.pick(results, baseTitle, primaryArtist, durationMs)
    }

    private sealed interface Fetch {
        class Found(val record: LrclibRecord) : Fetch
        data object NotFound : Fetch
        data object Failed : Fetch
    }

    private fun get(title: String, artist: String, album: String?, seconds: Long?): Fetch {
        val url = "$baseUrl/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .apply {
                if (album != null) addQueryParameter("album_name", album)
                if (seconds != null) addQueryParameter("duration", seconds.toString())
            }
            .build()
        return http.newCall(request(url.toString())).execute().use { r ->
            when {
                r.code == 404 -> Fetch.NotFound
                !r.isSuccessful -> Fetch.Failed
                else -> Fetch.Found(OnlineJson.decodeFromString(LrclibRecord.serializer(), r.body.string()))
            }
        }
    }

    private fun search(title: String, artist: String): List<LrclibRecord>? {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        return http.newCall(request(url.toString())).execute().use { r ->
            if (!r.isSuccessful) null
            else OnlineJson.decodeFromString(ListSerializer(LrclibRecord.serializer()), r.body.string())
        }
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Lrclib-Client", USER_AGENT)
        .header("Accept", "application/json")
        .build()

    companion object {
        const val USER_AGENT = "Sound/1.0 (Android music player)"
    }
}

@Serializable
internal data class LrclibRecord(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

internal object LrclibMatcher {
    const val DURATION_TOLERANCE_S = 5.0

    fun lyricsOf(r: LrclibRecord): Lyrics? {
        if (r.instrumental) return null
        r.syncedLyrics?.takeIf { it.isNotBlank() }?.let { return Lyrics(it.trim(), synced = true) }
        r.plainLyrics?.takeIf { it.isNotBlank() }?.let { return Lyrics(it.trim(), synced = false) }
        return null
    }

    /**
     * From /api/search results: same song (fuzzy title/artist), within ±5 s when the duration is
     * known, closest duration first, synced over plain.
     */
    fun pick(results: List<LrclibRecord>, title: String, artist: String, durationMs: Long?): Lyrics? {
        val seconds = durationMs?.takeIf { it > 0 }?.div(1000.0)
        val candidates = results.asSequence()
            .filter { !it.instrumental && lyricsOf(it) != null }
            .filter { TextMatch.similarity(TitleCleaner.baseTitle(it.trackName.orEmpty()), title) >= 0.8 }
            .filter {
                val a = it.artistName.orEmpty()
                TextMatch.similarity(TitleCleaner.primaryArtist(a), artist) >= 0.7 || TextMatch.contains(a, artist)
            }
            .filter { seconds == null || it.duration == null || abs(it.duration - seconds) <= DURATION_TOLERANCE_S }
            .toList()
        if (candidates.isEmpty()) return null
        val best = candidates.minWithOrNull(
            compareBy<LrclibRecord> { r -> if (seconds != null && r.duration != null) abs(r.duration - seconds) else DURATION_TOLERANCE_S + 1 }
                .thenBy { if (it.syncedLyrics.isNullOrBlank()) 1 else 0 },
        ) ?: return null
        // A synced version within a second of the best one beats plain text.
        val synced = if (best.syncedLyrics.isNullOrBlank() && seconds != null) {
            candidates.filter { !it.syncedLyrics.isNullOrBlank() && it.duration != null }
                .minByOrNull { abs(it.duration!! - seconds) }
                ?.takeIf { best.duration == null || abs(it.duration!! - seconds) <= abs(best.duration - seconds) + 1.0 }
        } else null
        return lyricsOf(synced ?: best)
    }
}
