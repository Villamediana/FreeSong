package com.villamediana.sound.online

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** iTunes Search API (`/search?entity=song`) response — only what Sound uses. */
@Serializable
internal data class ITunesResponse(
    val resultCount: Int = 0,
    val results: List<ITunesTrack> = emptyList(),
)

@Serializable
internal data class ITunesTrack(
    val wrapperType: String? = null,
    val kind: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val collectionArtistName: String? = null,
    val primaryGenreName: String? = null,
    val releaseDate: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackTimeMillis: Long? = null,
    val artworkUrl100: String? = null,
)

/** Pure matching logic between a YouTube song and iTunes candidates. */
internal object ITunesMatcher {
    const val ACCEPT = 0.72
    const val DURATION_TOLERANCE_MS = 10_000L

    data class Query(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
    ) {
        val baseTitle = TitleCleaner.baseTitle(title)
        val versions = TitleCleaner.versions(title)
        val primaryArtist = TitleCleaner.primaryArtist(artist)
    }

    data class Match(val track: ITunesTrack, val score: Double)

    /** Search term: first artist + song name without versions/feat (the API is a keyword search). */
    fun term(q: Query): String = "${q.primaryArtist} ${q.baseTitle}".replace(Regex("\\s+"), " ").trim()

    fun parse(json: String): List<ITunesTrack> =
        OnlineJson.decodeFromString(ITunesResponse.serializer(), json).results
            .filter { (it.wrapperType == null || it.wrapperType == "track") && (it.kind == null || it.kind == "song") }
            .filter { !it.trackName.isNullOrBlank() && !it.artistName.isNullOrBlank() }

    /** Best acceptable candidate (iTunes relevance order breaks ties), or null. */
    fun best(q: Query, candidates: List<ITunesTrack>): Match? {
        var best: Match? = null
        candidates.forEachIndexed { index, c ->
            val s = score(q, c) ?: return@forEachIndexed
            val adjusted = s - index * 0.002
            if (adjusted >= ACCEPT && (best == null || adjusted > best!!.score)) best = Match(c, adjusted)
        }
        return best
    }

    /** 0..~1.1, or null when the candidate is clearly another song. */
    fun score(q: Query, c: ITunesTrack): Double? {
        val cTitle = c.trackName ?: return null
        val cArtist = c.artistName ?: return null

        val titleSim = maxOf(
            TextMatch.similarity(q.baseTitle, TitleCleaner.baseTitle(cTitle)),
            TextMatch.similarity(q.title, cTitle),
        )
        if (titleSim < 0.75) return null

        val cPrimary = TitleCleaner.primaryArtist(cArtist)
        val artistSim = maxOf(
            TextMatch.similarity(q.artist, cArtist),
            TextMatch.similarity(q.primaryArtist, cPrimary),
            if (TextMatch.contains(cArtist, q.primaryArtist) || TextMatch.contains(q.artist, cPrimary)) 0.9 else 0.0,
        )
        if (artistSim < 0.6) return null

        val qd = q.durationMs
        val cd = c.trackTimeMillis
        val durationScore = if (qd != null && qd > 0 && cd != null && cd > 0) {
            val diff = abs(qd - cd)
            if (diff > DURATION_TOLERANCE_MS) return null
            1.0 - diff.toDouble() / DURATION_TOLERANCE_MS
        } else {
            // Without durations only near-exact names are trusted.
            if (titleSim < 0.9 || artistSim < 0.8) return null
            0.5
        }

        var score = 0.5 * titleSim + 0.35 * artistSim + 0.15 * durationScore

        // "Song" must not become "Song (Live)" / a remix / a karaoke cover, and vice versa.
        val cVersions = TitleCleaner.versions(cTitle) + TitleCleaner.versions(c.collectionName.orEmpty())
            .filter { it == TitleCleaner.Version.LIVE || it == TitleCleaner.Version.KARAOKE }
        for (v in TitleCleaner.Version.entries) {
            val inQuery = v in q.versions
            val inCandidate = v in cVersions
            if (inQuery == inCandidate) continue
            score -= when (v) {
                TitleCleaner.Version.KARAOKE, TitleCleaner.Version.INSTRUMENTAL -> if (inCandidate) 0.4 else 0.1
                TitleCleaner.Version.LIVE -> 0.15
                else -> 0.12
            }
        }

        // Prefer the artist's own release over "Various Artists" compilations.
        val collectionArtist = c.collectionArtistName.orEmpty()
        if (collectionArtist.isNotBlank() && TextMatch.similarity(collectionArtist, cArtist) < 0.6) score -= 0.04
        if (q.album != null && c.collectionName != null) {
            val albumSim = TextMatch.similarity(albumTitle(q.album) ?: q.album, albumTitle(c.collectionName) ?: c.collectionName)
            if (albumSim >= 0.85) score += 0.08
        }
        return score
    }

    /** "Envolver - Single" → null (a single isn't an album); "Languis - EP" → "Languis". */
    fun albumTitle(collectionName: String?): String? {
        val name = collectionName?.trim()?.ifEmpty { null } ?: return null
        if (Regex("\\s[-–]\\s*Single$", RegexOption.IGNORE_CASE).containsMatchIn(name)) return null
        return name.replace(Regex("\\s[-–]\\s*EP$", RegexOption.IGNORE_CASE), "").trim()
    }

    fun year(releaseDate: String?): Int? =
        releaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    /** artworkUrl100 → 1200×1200 (the CDN renders any size on the fly). */
    fun artwork(url100: String?): String? =
        url100?.takeIf { it.isNotBlank() }?.replace(Regex("/\\d+x\\d+(bb|cc)?(-\\d+)?\\.(jpg|png|webp)$"), "/1200x1200bb.jpg")
}
