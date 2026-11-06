package com.villamediana.surco.ui.detail

import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.model.TrackSort
import com.villamediana.surco.data.sortKey
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.ui.components.formatDuration
import com.villamediana.surco.ui.components.formatLongDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure helpers of the detail screens (no Compose, no Android) — formatting, scroll math and
 * playback matching. Everything here is unit-tested.
 */
object MetaFormat {
    const val SEP = " · "

    /** Joins the non-blank parts with " · " ("Soda Stereo · 1990 · 12 músicas, 47 min"). */
    fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(SEP) { it!!.trim() }

    /** "47 min" / "2 h 14 min"; under a minute it reads like a song length ("0:45"). Zero → null. */
    fun duration(ms: Long): String? = when {
        ms <= 0 -> null
        ms < 60_000 -> formatDuration(ms)
        else -> formatLongDuration(ms)
    }

    /** "12 músicas, 47 min" — [countText] already localized/pluralized by the caller. */
    fun countAndDuration(countText: String, durationMs: Long): String =
        duration(durationMs)?.let { "$countText, $it" } ?: countText

    /** Decade as people say it: 1990 → "90", 2010 → "2010" (for "Anos 90" / "Años 2010"). */
    fun decade(decade: Int): String = when {
        decade in 1910..1999 -> (decade % 100).toString().padStart(2, '0')
        decade in 1..99 -> decade.toString().padStart(2, '0')
        else -> decade.toString()
    }

    /** "Soda Stereo, Gustavo Cerati" — the most frequent artists of a set of songs. */
    fun topArtists(tracks: List<Track>, limit: Int = 2): List<String> =
        tracks.groupingBy { it.artist.trim() }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.sortKey() })
            .take(limit)
            .map { it.key }
}

/** Labels for the fast-scroller bubble. */
object ScrollIndex {
    /** First letter A–Z of a title, accents folded ("Ánimo" → A, "¡Hola!" → H); digits/others → "#". */
    fun letter(text: String?): String {
        val key = text?.sortKey()?.trimStart { !it.isLetterOrDigit() } ?: return "#"
        val c = key.firstOrNull() ?: return "#"
        return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
    }

    /** What the bubble says for [track] under [sort]: a letter, a month, a play count or a length. */
    fun label(track: Track, sort: TrackSort, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String = when (sort) {
        TrackSort.TITLE -> letter(track.title)
        TrackSort.ARTIST -> letter(track.artist)
        TrackSort.ALBUM -> if (track.hasAlbum) letter(track.album) else "#"
        TrackSort.RECENT -> monthYear(track.addedAt, locale, zone)
        TrackSort.MOST_PLAYED -> "${track.playCount}×"
        TrackSort.DURATION -> formatDuration(track.durationMs)
    }

    /** "nov. 2026" in the user's locale. */
    fun monthYear(epochMs: Long, locale: Locale, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MMM yyyy", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** First position of each letter in an already sorted list of labels (for A–Z jumps). */
    fun firstIndexByLabel(labels: List<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        labels.forEachIndexed { i, l -> if (l !in out) out[l] = i }
        return out
    }

    /** Item index the thumb points to at [fraction] (0..1) of a list of [count] items. */
    fun indexAt(fraction: Float, count: Int): Int {
        if (count <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    /** Where the thumb sits (0..1) when [firstVisible] is the first item and [visible] items fit. */
    fun fractionOf(firstVisible: Int, count: Int, visible: Int): Float {
        val scrollable = count - visible
        if (scrollable <= 0) return 0f
        return (firstVisible.toFloat() / scrollable).coerceIn(0f, 1f)
    }
}

/**
 * Collapse math of the collection header. "scrolled" is how many pixels of the header item have
 * gone above the top of the list (∞ once the header item left the screen).
 */
object HeaderMath {
    fun scrolled(firstVisibleIndex: Int, firstVisibleOffset: Int): Float =
        if (firstVisibleIndex > 0) Float.POSITIVE_INFINITY else firstVisibleOffset.toFloat().coerceAtLeast(0f)

    /** 0 = header untouched, 1 = scrolled [rangePx] or more. */
    fun fraction(scrolled: Float, rangePx: Float): Float =
        if (rangePx <= 0f) (if (scrolled > 0f) 1f else 0f) else (scrolled / rangePx).coerceIn(0f, 1f)

    /** Art drifts down at half speed (parallax) — capped so it never leaves its slot. */
    fun artTranslation(scrolled: Float, maxPx: Float): Float =
        if (scrolled.isInfinite()) maxPx.coerceAtLeast(0f) else (scrolled * 0.5f).coerceIn(0f, maxPx.coerceAtLeast(0f))

    fun artScale(fraction: Float): Float = 1f - 0.14f * fraction.coerceIn(0f, 1f)

    fun artAlpha(fraction: Float): Float = (1f - fraction * 1.1f).coerceIn(0f, 1f)

    /**
     * Opacity of the compact bar's background: starts when the title is [leadPx] away from the bar
     * and is solid by the time the title slides under it.
     */
    fun barAlpha(scrolled: Float, titleBottomPx: Float, barBottomPx: Float, leadPx: Float): Float {
        if (scrolled.isInfinite()) return 1f
        if (titleBottomPx.isInfinite()) return 0f
        val solidAt = titleBottomPx - barBottomPx
        val startAt = solidAt - leadPx
        if (scrolled <= startAt) return 0f
        if (scrolled >= solidAt || leadPx <= 0f) return 1f
        return ((scrolled - startAt) / leadPx).coerceIn(0f, 1f)
    }

    /** Opacity of the compact title: fades in over [fadePx] once the big title is under the bar. */
    fun titleAlpha(scrolled: Float, titleBottomPx: Float, barBottomPx: Float, fadePx: Float): Float {
        if (scrolled.isInfinite()) return 1f
        if (titleBottomPx.isInfinite()) return 0f
        val start = titleBottomPx - barBottomPx
        if (scrolled <= start) return 0f
        if (fadePx <= 0f) return 1f
        return ((scrolled - start) / fadePx).coerceIn(0f, 1f)
    }
}

/** Ids the detail screens give to [PlaySource] (so "is this collection playing?" can be answered). */
object Sources {
    fun album(artist: String, title: String, displayTitle: String = title) =
        PlaySource(PlaySource.Type.ALBUM, displayTitle, albumId(artist, title))

    /** Same identity rule as AlbumKey.normalized (case-insensitive artist + title). */
    fun albumId(artist: String, title: String) = artist.trim().lowercase() + "\u0001" + title.trim().lowercase()

    fun artist(name: String) = PlaySource(PlaySource.Type.ARTIST, name, name)
    fun playlist(id: Long, name: String) = PlaySource(PlaySource.Type.PLAYLIST, name, id.toString())
    fun genre(name: String) = PlaySource(PlaySource.Type.GENRE, name, name)
    fun mix(id: String, title: String) = PlaySource(PlaySource.Type.MIX, title, id)
    fun liked(title: String) = PlaySource(PlaySource.Type.LIKED, title, "liked")
    fun songs(title: String) = PlaySource(PlaySource.Type.SONGS, title, "songs")

    /**
     * True when [current] is the same collection as [mine]. Same type, and same id when both have
     * one (case-insensitive — genre/artist names), otherwise same title. Smart sessions started
     * by the player (genre, mix) may carry only a title, which is why the fallback exists.
     */
    fun matches(current: PlaySource?, mine: PlaySource): Boolean {
        if (current == null || current.type != mine.type) return false
        val bothIds = current.id != null && mine.id != null
        if (bothIds && current.id.equals(mine.id, ignoreCase = true)) return true
        if (!bothIds || mine.type in titleMatchable) return current.title.isNotBlank() && current.title.equals(mine.title, ignoreCase = true)
        return false
    }

    /** Types where a title match is good enough even when ids differ in format (smart sessions). */
    private val titleMatchable = setOf(PlaySource.Type.GENRE, PlaySource.Type.MIX)
}

/** Ordering helpers. */
object PlayOrder {
    /** Artist "Play": popular songs first, then everything else in the artist's order. */
    fun artist(top: List<Track>, all: List<Track>): List<Track> {
        val seen = HashSet<Long>()
        return (top + all).filter { seen.add(it.id) }
    }

    /** Index to start from when Play is pressed with shuffle on (random but stable for a seed). */
    fun shuffleStart(size: Int, seed: Long): Int = if (size <= 0) 0 else java.util.Random(seed).nextInt(size)

    /** Moves one element (reorder by drag). Out-of-range or no-op returns the same list. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Applies a local order (by key) on top of [items]; unknown items keep their place at the end. */
    fun <T, K> applyOrder(items: List<T>, order: List<K>?, key: (T) -> K): List<T> {
        if (order == null) return items
        val rank = order.withIndex().associate { it.value to it.index }
        return items.withIndex()
            .sortedWith(compareBy({ rank[key(it.value)] ?: (Int.MAX_VALUE / 2 + it.index) }, { it.index }))
            .map { it.value }
    }
}
