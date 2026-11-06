package com.villamediana.sound.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Title cleanup ([TitleCleaner]) + iTunes Search API lookup for genre, album, year, track number
 * and 1200 px artwork. Requests are serialized and spaced out; when Apple says "slow down" the
 * enricher backs off and simply returns the cleaned input. Never throws.
 */
class CatalogMetadataEnricher(
    private val http: OkHttpClient,
    private val country: String = defaultCountry(),
    private val baseUrl: String = "https://itunes.apple.com",
    private val clock: () -> Long = System::currentTimeMillis,
) : MetadataEnricher {

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > CACHE_SIZE
    }
    private var lastRequestAt = 0L
    private var minGapMs = BASE_GAP_MS
    private var cooldownUntil = 0L
    private var backoffMs = FIRST_BACKOFF_MS

    /** Cached lookup result; [track] null = "the catalog doesn't have it". */
    private class CacheEntry(val track: ITunesTrack?)

    override suspend fun enrich(input: MetaInput): TrackMeta {
        val cleaned = try {
            TitleCleaner.clean(input.title, input.artist, input.fromMusicCatalog)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            TitleCleaner.Cleaned(input.title.trim(), input.artist.trim())
        }
        val base = TrackMeta(
            title = cleaned.title,
            artist = cleaned.artist,
            album = input.album?.trim()?.ifEmpty { null },
            albumArtist = input.albumArtist?.let(TitleCleaner::cleanArtist)?.ifEmpty { null },
            trackNumber = input.trackNumber,
            year = input.year,
        )
        return try {
            val match = lookup(cleaned, input) ?: return base
            merge(base, cleaned, input, match)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            base
        }
    }

    private suspend fun lookup(cleaned: TitleCleaner.Cleaned, input: MetaInput): Pick? {
        val primary = ITunesMatcher.Query(cleaned.title, cleaned.artist, input.album, input.durationMs)
        // "L - R" with no clue which side is the artist: also try it the other way round.
        val swapped = if (cleaned.ambiguousSplit) ITunesMatcher.Query(cleaned.artist, cleaned.title, input.album, input.durationMs) else null

        val key = cacheKey(primary)
        val cached = mutex.withLock { cache[key] }
        val candidates: List<ITunesTrack> = if (cached != null) {
            listOfNotNull(cached.track)
        } else {
            val results = search(ITunesMatcher.term(primary), LIMIT)
                ?: return null // rate limited / offline: don't cache, try again next time
            val fallback = if (results.isEmpty()) search(primary.baseTitle, LIMIT * 2).orEmpty() else emptyList()
            results + fallback
        }

        val straight = ITunesMatcher.best(primary, candidates)
        val reversed = swapped?.let { ITunesMatcher.best(it, candidates) }
        val pick = when {
            reversed != null && (straight == null || reversed.score > straight.score + 0.05) -> Pick(reversed.track, swapped = true)
            straight != null -> Pick(straight.track, swapped = false)
            else -> null
        }
        if (cached == null) mutex.withLock { cache[key] = CacheEntry(pick?.track) }
        return pick
    }

    private class Pick(val track: ITunesTrack, val swapped: Boolean)

    private fun merge(base: TrackMeta, cleaned: TitleCleaner.Cleaned, input: MetaInput, pick: Pick): TrackMeta {
        val m = pick.track
        var title = if (pick.swapped) cleaned.artist else base.title
        var artist = if (pick.swapped) cleaned.title else base.artist
        if (!input.fromMusicCatalog) {
            // Same words, better spelling: "SODA STEREO - DE MUSICA LIGERA" → "De Música Ligera" by "Soda Stereo".
            m.trackName?.let { if (sameWords(it, title)) title = it }
            m.artistName?.let { if (sameWords(it, artist)) artist = it }
        }
        val catalogAlbum = ITunesMatcher.albumTitle(m.collectionName)
        val albumFromInput = base.album != null
        val sameAlbum = albumFromInput && catalogAlbum != null && TextMatch.similarity(base.album!!, catalogAlbum) >= 0.85
        val album = base.album ?: catalogAlbum
        val albumArtist = base.albumArtist ?: if (album != null && (!albumFromInput || sameAlbum)) {
            (m.collectionArtistName ?: m.artistName)?.let(TitleCleaner::cleanArtist)
        } else null
        // Track numbers and dates only make sense for the album they come from.
        val numberFits = (!albumFromInput && catalogAlbum != null) || sameAlbum
        return base.copy(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = m.primaryGenreName?.trim()?.ifEmpty { null },
            year = base.year ?: ITunesMatcher.year(m.releaseDate)?.takeIf { !albumFromInput || sameAlbum },
            trackNumber = base.trackNumber ?: m.trackNumber?.takeIf { numberFits && it > 0 },
            artworkUrl = ITunesMatcher.artwork(m.artworkUrl100),
        )
    }

    private fun sameWords(a: String, b: String): Boolean {
        val ka = TextMatch.key(a)
        return ka.isNotEmpty() && (ka == TextMatch.key(b) || ka.replace(" ", "") == TextMatch.key(b).replace(" ", ""))
    }

    /** null = could not ask (cooling down, refused, offline); empty = asked, nothing found. */
    private suspend fun search(term: String, limit: Int): List<ITunesTrack>? {
        if (term.isBlank()) return emptyList()
        return mutex.withLock {
            val now = clock()
            if (now < cooldownUntil) return@withLock null
            val wait = lastRequestAt + minGapMs - now
            if (wait > 0) delay(wait)
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("media", "music")
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("country", country)
                .build()
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(request).execute().use { r ->
                        when {
                            r.code == 403 || r.code == 429 -> Outcome.Throttled
                            !r.isSuccessful -> Outcome.Failed
                            else -> Outcome.Ok(ITunesMatcher.parse(r.body.string()))
                        }
                    }
                }.getOrElse { if (it is CancellationException) throw it else Outcome.Failed }
            }
            lastRequestAt = clock()
            when (result) {
                is Outcome.Ok -> { backoffMs = FIRST_BACKOFF_MS; result.tracks }
                Outcome.Throttled -> {
                    cooldownUntil = lastRequestAt + backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    // Apple allows roughly 20 calls a minute: from now on pace the queue instead of bursting.
                    minGapMs = THROTTLED_GAP_MS
                    null
                }
                Outcome.Failed -> null
            }
        }
    }

    private sealed interface Outcome {
        class Ok(val tracks: List<ITunesTrack>) : Outcome
        data object Throttled : Outcome
        data object Failed : Outcome
    }

    private fun cacheKey(q: ITunesMatcher.Query): String =
        "${TextMatch.key(q.title)}|${TextMatch.key(q.artist)}|${TextMatch.key(q.album.orEmpty())}|${(q.durationMs ?: -1) / 5000}"

    companion object {
        const val USER_AGENT = "Sound/1.0 (Android music player)"
        private const val LIMIT = 10
        private const val CACHE_SIZE = 256
        private const val BASE_GAP_MS = 300L
        private const val THROTTLED_GAP_MS = 3_000L
        private const val FIRST_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        fun defaultCountry(locale: Locale = Locale.getDefault()): String =
            locale.country.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase(Locale.ROOT) ?: "US"
    }
}
