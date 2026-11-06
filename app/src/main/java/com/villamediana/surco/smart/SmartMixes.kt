package com.villamediana.surco.smart

import com.villamediana.surco.data.db.Track
import java.util.TimeZone
import kotlin.math.ln
import kotlin.random.Random

/**
 * Builds the Home mixes from the offline library: Mix do dia, No repeat, Curtidas, one mix per top
 * genre, Novidades, Redescubra, artist mixes and decades. Mixes without enough songs are left out.
 *
 * Deterministic: the randomness is seeded by the mix id and the local day of [Taste.now], so a mix
 * stays the same all day (recompositions, reopening it, playing it via [byId]) and changes the next.
 */
object SmartMixes {
    const val ID_DAILY = "daily"
    const val ID_LIKED = "liked"
    const val ID_ON_REPEAT = "on-repeat"
    const val ID_REDISCOVER = "rediscover"
    const val ID_FRESH = "fresh"
    const val PREFIX_GENRE = "genre:"
    const val PREFIX_DECADE = "decade:"
    const val PREFIX_ARTIST = "artist:"

    internal const val MIN_TRACKS = 5
    internal const val MIN_GENRE = 5
    internal const val MIN_DECADE = 8
    internal const val MIN_ARTIST = 6
    internal const val MIN_DAILY_LIBRARY = 10
    internal const val MAX_GENRE_MIXES = 6
    internal const val MAX_ARTIST_MIXES = 4
    internal const val DAILY_SIZE = 30

    /** Strings are resolved by the caller ([labels]) so this stays pure and testable. */
    fun build(library: List<Track>, taste: Taste, labels: MixLabels): List<Mix> {
        if (library.isEmpty()) return emptyList()
        val b = Builder(library, taste, labels)
        return buildList {
            b.daily()?.let(::add)
            b.onRepeat()?.let(::add)
            b.liked()?.let(::add)
            b.topGenres().take(MAX_GENRE_MIXES).forEach { g -> b.genreMix(g)?.let(::add) }
            b.fresh()?.let(::add)
            b.rediscover()?.let(::add)
            b.topArtists().take(MAX_ARTIST_MIXES).forEach { a -> b.artistMix(a)?.let(::add) }
            b.decades().forEach { d -> b.decadeMix(d)?.let(::add) }
        }
    }

    /** Rebuilds one mix by id (for opening/playing it later). */
    fun byId(id: String, library: List<Track>, taste: Taste, labels: MixLabels): Mix? {
        if (library.isEmpty()) return null
        val b = Builder(library, taste, labels)
        return when {
            id == ID_DAILY -> b.daily()
            id == ID_ON_REPEAT -> b.onRepeat()
            id == ID_LIKED -> b.liked()
            id == ID_FRESH -> b.fresh()
            id == ID_REDISCOVER -> b.rediscover()
            id.startsWith(PREFIX_GENRE) -> b.genreGroups[id.removePrefix(PREFIX_GENRE)]?.let(b::genreMix)
            id.startsWith(PREFIX_ARTIST) -> b.artistGroups[id.removePrefix(PREFIX_ARTIST)]?.let(b::artistMix)
            id.startsWith(PREFIX_DECADE) -> id.removePrefix(PREFIX_DECADE).toIntOrNull()?.let(b::decadeMix)
            else -> null
        }
    }

    /** Local calendar day of [now] (changes at local midnight). */
    internal fun dayNumber(now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(now + zone.getOffset(now), DAY_MS)

    internal class Group(val slug: String, val name: String, val feats: List<Feat>)

    private class Builder(library: List<Track>, private val taste: Taste, private val labels: MixLabels) {
        val tracks: List<Track> = library.distinctBy { it.id }.sortedBy { it.id }
        val feats: List<Feat> = tracks.feats().distinctSongs()
        val day = dayNumber(taste.now)

        fun seed(id: String): Long = id.hashCode().toLong() * 1_000_003L + day
        fun rnd(id: String) = Random(seed(id))
        fun plays(f: Feat) = taste.recentPlays[f.id] ?: 0

        val genreGroups: Map<String, Group> by lazy {
            feats.filter { it.genre.isNotEmpty() }.groupBy { it.genre }.map { (key, group) ->
                val slug = key.replace(' ', '-')
                slug to Group(slug, group.mostCommon { it.track.genre!!.trim() }, group)
            }.toMap()
        }

        val artistGroups: Map<String, Group> by lazy {
            feats.filter { it.artist.isNotEmpty() }.groupBy { it.artist }.map { (key, group) ->
                val slug = key.replace(' ', '-')
                val solo = group.filter { normalizeText(it.track.artist) == key }.ifEmpty { group }
                slug to Group(slug, solo.mostCommon { it.track.artist.trim() }, group)
            }.toMap()
        }

        fun topGenres(): List<Group> = genreGroups.values.filter { it.feats.size >= MIN_GENRE }
            .sortedWith(compareByDescending<Group> { g -> g.feats.size + 2 * g.feats.sumOf(::plays) }.thenBy { it.slug })

        fun topArtists(): List<Group> = artistGroups.values.filter { it.feats.size >= MIN_ARTIST }
            .sortedWith(
                compareByDescending<Group> { g -> g.feats.size + 2 * g.feats.sumOf(::plays) + g.feats.count { it.track.liked } }
                    .thenBy { it.slug },
            )

        fun decades(): List<Int> = feats.mapNotNull { f -> f.year?.takeIf { it > 0 }?.let { it / 10 * 10 } }
            .groupingBy { it }.eachCount().filter { it.value >= MIN_DECADE }.keys.sortedDescending()

        // ------------------------------------------------------------------ mixes

        fun daily(): Mix? {
            if (feats.size < MIN_DAILY_LIBRARY) return null
            val rnd = rnd(ID_DAILY)
            val favourites = feats.filter { favScore(it) > 0.0 }
                .sortedWith(compareByDescending<Feat> { favScore(it) }.thenBy { it.id })
                .take(40)
            val favPick = weightedOrder(favourites, { it.id }, { favScore(it) * tasteWeight(it.track, taste) }, rnd).take(18)
            val favIds = favourites.mapTo(HashSet()) { it.id }
            val recentCut = taste.now - 30 * DAY_MS
            val profile = (favourites.ifEmpty { feats }).mapNotNull { it.family }.groupingBy { it }.eachCount()
            val profileTotal = profile.values.sum().coerceAtLeast(1)
            val favArtists = favourites.mapTo(HashSet()) { it.artist }
            val discoveryPool = feats.filter { f ->
                f.id !in favIds && (f.track.playCount == 0 || (f.track.lastPlayedAt ?: 0) < recentCut)
            }
            val discWeight = { f: Feat ->
                (1.0 + 2.0 * (f.family?.let { profile[it] } ?: 0) / profileTotal + (if (f.artist in favArtists) 1.0 else 0.0)) *
                    tasteWeight(f.track, taste)
            }
            val discPick = weightedOrder(discoveryPool, { it.id }, discWeight, rnd).take(DAILY_SIZE - favPick.size)
            var picked = interleave(favPick, discPick)
            if (picked.size < DAILY_SIZE) {
                val used = picked.mapTo(HashSet()) { it.id }
                picked = picked + weightedOrder(feats.filter { it.id !in used }, taste, rnd).take(DAILY_SIZE - picked.size)
            }
            return mix(ID_DAILY, labels.daily, labels.dailySubtitle, Mix.Kind.DAILY, spread(picked).map { it.track })
        }

        fun onRepeat(): Mix? {
            val top = feats.filter { plays(it) >= 2 }
                .sortedWith(
                    compareByDescending<Feat> { plays(it) }
                        .thenByDescending { it.track.lastPlayedAt ?: 0 }
                        .thenBy { it.id },
                )
                .take(30)
            if (top.size < MIN_TRACKS) return null
            return mix(ID_ON_REPEAT, labels.onRepeat, labels.onRepeatSubtitle, Mix.Kind.ON_REPEAT, top.map { it.track })
        }

        fun liked(): Mix? {
            val liked = feats.filter { it.track.liked }
            if (liked.size < MIN_TRACKS) return null
            val order = spread(weightedOrder(liked, taste, rnd(ID_LIKED))).map { it.track }
            return mix(ID_LIKED, labels.liked, labels.likedSubtitle, Mix.Kind.LIKED, order)
        }

        fun fresh(): Mix? {
            val since = taste.now - 14 * DAY_MS
            val fresh = feats.filter { it.track.addedAt >= since }
            if (fresh.size < MIN_TRACKS) return null
            val span = (14 * DAY_MS).toDouble()
            val order = weightedOrder(
                fresh,
                { it.id },
                { f -> tasteWeight(f.track, taste) * (1.0 + 2.0 * ((f.track.addedAt - since).coerceAtLeast(0) / span)) },
                rnd(ID_FRESH),
            ).take(50)
            return mix(ID_FRESH, labels.fresh, labels.freshSubtitle, Mix.Kind.FRESH, spread(order).map { it.track })
        }

        fun rediscover(): Mix? {
            val recentCut = taste.now - 30 * DAY_MS
            val addedCut = taste.now - 14 * DAY_MS
            val pool = feats.filter { f ->
                val t = f.track
                val notThisMonth = plays(f) == 0 && (t.lastPlayedAt ?: 0) < recentCut
                val playedBefore = t.playCount > 0 || t.lastPlayedAt != null
                notThisMonth && (playedBefore || t.addedAt < addedCut)
            }
            if (pool.size < MIN_TRACKS) return null
            val order = weightedOrder(
                pool,
                { it.id },
                { f -> tasteWeight(f.track, taste) * (1.0 + 0.5 * ln(1.0 + f.track.playCount)) },
                rnd(ID_REDISCOVER),
            ).take(30)
            return mix(ID_REDISCOVER, labels.rediscover, labels.rediscoverSubtitle, Mix.Kind.REDISCOVER, spread(order).map { it.track })
        }

        fun genreMix(group: Group): Mix? {
            if (group.feats.size < MIN_GENRE) return null
            val id = PREFIX_GENRE + group.slug
            val order = SmartQueue.genre(group.name, tracks, taste, limit = 50, seedValue = seed(id))
            if (order.size < MIN_TRACKS) return null
            val artists = group.feats.groupBy { it.artist }.entries
                .sortedWith(compareByDescending<Map.Entry<String, List<Feat>>> { e -> e.value.size + 2 * e.value.sumOf(::plays) }.thenBy { it.key })
                .take(3)
                .map { e -> e.value.mostCommon { it.track.artist.trim() } }
            return mix(id, labels.genreMix(group.name), labels.genreMixSubtitle(artists.joinToString(", ")), Mix.Kind.GENRE, order)
        }

        fun artistMix(group: Group): Mix? {
            if (group.feats.size < MIN_ARTIST) return null
            val id = PREFIX_ARTIST + group.slug
            val rnd = rnd(id)
            val own = weightedOrder(group.feats, taste, rnd).take(12)
            val anchors = own.take(5)
            val ownIds = group.feats.mapTo(HashSet()) { it.id }
            val score = { c: Feat -> anchors.sumOf { similarity(it, c) } / anchors.size }
            val artist = group.feats.first().artist
            val related = feats.filter { it.id !in ownIds && it.artist != artist && score(it) > 0.0 }
            val similar = pickSimilar(related, score, taste, own.size, rnd, previous = null)
            val order = interleave(own, similar).map { it.track }
            val others = similar.map { it.artist }.distinct().take(3)
                .map { a -> similar.filter { it.artist == a }.mostCommon { it.track.artist.trim() } }
            val subtitle = labels.genreMixSubtitle(others.ifEmpty { listOf(group.name) }.joinToString(", "))
            return mix(id, labels.artistMix(group.name), subtitle, Mix.Kind.ARTIST, order)
        }

        fun decadeMix(decade: Int): Mix? {
            val inDecade = feats.filter { f -> f.year?.let { it > 0 && it / 10 * 10 == decade } == true }
            if (inDecade.size < MIN_DECADE) return null
            val id = PREFIX_DECADE + decade
            val order = spread(weightedOrder(inDecade, taste, rnd(id)).take(60)).map { it.track }
            return mix(id, labels.decade(decade), labels.decadeSubtitle, Mix.Kind.DECADE, order)
        }

        private fun favScore(f: Feat): Double {
            val t = f.track
            val total = t.playCount + t.skipCount
            val skipRatio = if (total > 0) t.skipCount.toDouble() / (total + 1) else 0.0
            val raw = (if (t.liked) 3.0 else 0.0) + 2.0 * ln(1.0 + plays(f)) + 0.5 * ln(1.0 + t.playCount) - 2.0 * skipRatio
            return if (t.liked || plays(f) > 0 || t.playCount > 0) raw else 0.0
        }

        private fun mix(id: String, title: String, subtitle: String, kind: Mix.Kind, order: List<Track>): Mix {
            val covers = coversOf(order)
            val tint = order.firstOrNull { it.coverPath == covers.firstOrNull() && it.accentColor != null }?.accentColor
                ?: order.firstNotNullOfOrNull { it.accentColor }
            return Mix(id = id, title = title, subtitle = subtitle, kind = kind, tracks = order, covers = covers, tint = tint)
        }
    }

    /** Up to 4 distinct covers, one per album, in mix order. */
    internal fun coversOf(tracks: List<Track>): List<String> {
        val seenAlbums = HashSet<String>()
        val out = ArrayList<String>(4)
        for (t in tracks) {
            val cover = t.coverPath ?: continue
            if (cover in out) continue
            val album = t.albumKey?.normalized ?: cover
            if (!seenAlbums.add(album)) continue
            out += cover
            if (out.size == 4) break
        }
        return out
    }

    /** Merges two lists proportionally ([a] leading), keeping each one's order. */
    internal fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<T>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            val takeA = j >= b.size || (i < a.size && i.toDouble() / a.size <= j.toDouble() / b.size)
            if (takeA) out += a[i++] else out += b[j++]
        }
        return out
    }
}

private fun <T> List<T>.mostCommon(selector: (T) -> String): String =
    groupingBy(selector).eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key

/** Localized titles for mixes. */
data class MixLabels(
    val genreMix: (genre: String) -> String,
    val genreMixSubtitle: (artists: String) -> String,
    val liked: String,
    val likedSubtitle: String,
    val onRepeat: String,
    val onRepeatSubtitle: String,
    val rediscover: String,
    val rediscoverSubtitle: String,
    val fresh: String,
    val freshSubtitle: String,
    val decade: (decade: Int) -> String,
    val decadeSubtitle: String,
    val artistMix: (artist: String) -> String,
    val daily: String,
    val dailySubtitle: String,
)
