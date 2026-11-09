package com.villamediana.sound.discover

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.smart.DnaMoment
import com.villamediana.sound.smart.FriendsTaste
import com.villamediana.sound.smart.MomentPlan
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.smart.GenreMatch
import com.villamediana.sound.smart.Heart
import com.villamediana.sound.smart.MusicDna
import com.villamediana.sound.smart.Listen
import com.villamediana.sound.smart.Recommend
import com.villamediana.sound.smart.SmartMixes
import com.villamediana.sound.smart.TasteProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Home's suggestions, from what the listener actually likes ([TasteProfile]: everything heard
 * on the phone and streamed, the skips, the hearts, the picked genres):
 * - "Descobertas pra você": the day's new songs, from the radios of their best loved songs across
 *   their genres; the same all day, new the next.
 * - "Porque você curte <artista>": their favourite artist right now and another one taking turns,
 *   fixed for the 6-hour slot (it doesn't change with every song played).
 * - The genre shelves of the genres they listen to (and picked), leaning to their artists.
 * Songs and artists they keep skipping never come back.
 */
class ForYou(
    private val library: LibraryRepository,
    private val discover: DiscoverRepository,
    private val artistGenres: ArtistGenres,
    private val picked: suspend () -> Set<String>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the phone is now (a learned place), when places are on. */
    private val placeNow: () -> Long? = { null },
    /** Who is listening along now (the friends in the jam). */
    private val peopleNow: () -> Set<String> = { emptySet() },
    /** What the friends like, as they show it to their friends (already on this phone). */
    private val friends: () -> FriendsTaste = { FriendsTaste.NONE },
) {
    /** One "Porque você curte <artist>" row. */
    data class BecauseRow(val artist: String, val artistKey: String, val tracks: List<RemoteTrack>)

    private val lock = Mutex()
    private var cached: Pair<Long, TasteProfile>? = null
    private var cachedDna: MusicDna? = null
    private var learning: Job? = null
    /** The artists of this slot's "Porque você curte…" rows: chosen once per slot, they don't move with each song. */
    @Volatile private var anchors: Pair<Long, List<TasteProfile.ArtistTaste>>? = null

    /** The taste right now: rebuilt at most every [PROFILE_TTL_MS] (or after [invalidate]). */
    suspend fun profile(): TasteProfile = lock.withLock {
        val now = clock()
        cached?.let { (at, p) -> if (now - at in 0 until PROFILE_TTL_MS) return@withLock p }
        val (p, dna) = withContext(Dispatchers.Default) { build(now) }
        cached = now to p
        cachedDna = dna
        learnGenres(p)
        p
    }

    /** The musical DNA (built with the taste, from the same listening). */
    suspend fun dna(): MusicDna {
        profile()
        return cachedDna ?: MusicDna.build(emptyList(), emptySet(), clock())
    }

    /** The moment now: the part of the day, the day of the week, the place and the friends along. */
    fun moment(): DnaMoment = DnaMoment.at(clock(), place = placeNow(), people = peopleNow())

    /**
     * "Pra agora": your songs that fit this moment best ([MusicDna.songsFor]) and new ones from
     * their radios in the taste of the moment (made once per moment and day). Null while the DNA
     * has too little to say.
     */
    suspend fun momentPlan(names: (Set<String>) -> List<String> = { emptyList() }): MomentPlan? {
        val p = profile()
        val dna = dna()
        if (dna.isEmpty) return null
        val m = moment()
        val songs = dna.songsFor(m, MOMENT_OWN)
        if (songs.size < MOMENT_MIN) return null
        val tracks = library.allTracks()
        val byVideo = tracks.filter { it.videoId != null }.associateBy { it.videoId!! }
        val own = songs.map { s -> byVideo[s.videoId] ?: SongRef(s.videoId, s.title, s.artist).toRemote().asTransientTrack() }
        val inLibrary = tracks.mapNotNullTo(HashSet()) { it.videoId }
        val seeds = songs.take(MOMENT_SEEDS).map { it.videoId }
        val day = SmartMixes.dayNumber(clock())
        val fresh = runCatching {
            discover.daily("moment-v1:" + m.key, day) {
                Recommend.fromRadios(radios(seeds), p.inMoment(dna, m), inLibrary + songs.map { it.videoId }, MOMENT_NEW)
            }
        }.getOrDefault(emptyList()).filter { it.videoId !in inLibrary && !p.avoids(it.videoId, it.artist) }
        return MomentPlan(m, own, fresh, names(m.people))
    }

    /** The taste for right now ([TasteProfile.inMoment]): what radios and suggestions use. */
    suspend fun profileNow(): TasteProfile {
        val p = profile()
        return p.inMoment(dna(), moment())
    }

    /** Something changed (a heart, a song heard): the next [profile] is built again. */
    fun invalidate() {
        cached = null
    }

    private suspend fun build(now: Long): Pair<TasteProfile, MusicDna> {
        val since = now - HISTORY_MS
        val tracks = library.allTracks()
        val byId = tracks.associateBy { it.id }
        val listens = ArrayList<Listen>()
        fun people(raw: String?) = raw?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        for (e in library.playEventsListSince(since)) {
            val t = byId[e.trackId] ?: continue
            listens += Listen(t.videoId, t.title, t.artist, t.genre, e.playedAt, e.listenedMs, t.durationMs, e.completed, e.skipped, e.placeId, people(e.people))
        }
        for (p in library.onlinePlaysListSince(since)) {
            listens += Listen(p.videoId, p.title, p.artist, null, p.playedAt, p.listenedMs, p.durationMs, p.completed, p.skipped, p.placeId, people(p.people))
        }
        val hearts = tracks.filter { it.liked }.map { Heart(it.videoId, it.title, it.artist, it.genre, it.likedAt ?: it.addedAt) } +
            library.likedOnlineList().map { Heart(it.videoId, it.title, it.artist, null, it.likedAt) }
        // Downloading a song says something too (less than a heart): the library's songs count a little.
        val saved = tracks.filter { !it.liked }.map { Heart(it.videoId, it.title, it.artist, it.genre, it.addedAt) }
        val known = artistGenres.known()
        val profile = TasteProfile.build(listens, hearts, now, known, picked(), saved, runCatching { friends() }.getOrDefault(FriendsTaste.NONE))
        val dna = MusicDna.build(
            listens,
            hearts.mapNotNullTo(HashSet()) { it.videoId },
            now,
            genreOf = { artist, songGenre -> GenreMatch.idOf(songGenre ?: known[artist]) },
            sounds = library.sounds(),
        )
        return profile to dna
    }

    /** The genres of the artists listened most that nobody knows yet: a few asked in the background. */
    private fun learnGenres(p: TasteProfile) {
        if (learning?.isActive == true) return
        val missing = p.artists.filter { it.genre == null }.take(LEARN_FROM).map { it.key to it.name }
        if (missing.isEmpty()) return
        learning = scope.launch {
            if (runCatching { artistGenres.learn(missing) }.getOrDefault(false)) invalidate()
        }
    }

    /** "Descobertas pra você": made once a day; the ones skipped since leave the row. */
    suspend fun discoveries(): List<RemoteTrack> {
        val p = profile()
        if (p.isEmpty) return emptyList()
        val inLibrary = library.allTracks().mapNotNullTo(HashSet()) { it.videoId }
        val day = SmartMixes.dayNumber(clock())
        val list = discover.daily("discoveries-v1", day) {
            val seeds = p.seeds(DISCOVERY_SEEDS).mapNotNull { it.videoId }
            Recommend.fromRadios(radios(seeds), p, inLibrary + seeds, DISCOVERIES)
        }
        // Skipped just now: out of the row for now (the session), back later — a skip is the moment.
        val session = runCatching { SongRadio.sessionOf(library, clock()) }.getOrDefault(com.villamediana.sound.smart.Session())
        return list.filter { it.videoId !in inLibrary && !p.avoids(it.videoId, it.artist) && it.videoId !in session.skipped }
    }

    /**
     * "Porque você curte…": the two favourite artists of the moment and two of the next ones taking
     * turns each 6-hour slot (one card each on the Home, in a single row). Each stays the same
     * through its slot.
     */
    suspend fun because(): List<BecauseRow> {
        val p = profile()
        val slot = GenreCatalog.slotOf(clock())
        val chosen = anchors?.takeIf { it.first == slot }?.second ?: run {
            val favorites = p.favoritesNow(BECAUSE_POOL)
            buildList {
                addAll(favorites.take(BECAUSE_FIXED))
                val rest = favorites.drop(BECAUSE_FIXED)
                if (rest.isNotEmpty()) {
                    val start = (slot % rest.size).toInt()
                    repeat(minOf(BECAUSE_TURNS, rest.size)) { add(rest[(start + it) % rest.size]) }
                }
            }.also { if (it.isNotEmpty()) anchors = slot to it }
        }
        if (chosen.isEmpty()) return emptyList()
        val inLibrary = library.allTracks().mapNotNullTo(HashSet()) { it.videoId }
        return coroutineScope {
            chosen.map { a ->
                async {
                    val tracks = discover.daily("because-v1:" + a.key, slot) {
                        val seeds = a.songs.mapNotNull { it.videoId }.take(BECAUSE_SEEDS)
                        Recommend.fromRadios(radios(seeds), p, inLibrary + seeds, BECAUSE_SIZE, perArtist = 3)
                    }.filter { it.videoId !in inLibrary && !p.avoids(it.videoId, it.artist) }
                    tracks.takeIf { it.size >= MIN_ROW }?.let { BecauseRow(a.name, a.key, it) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * The genres for shelves: the picked ones and the ones listened most (a learned one only when
     * it is a real part of the listening), the most listened first.
     */
    suspend fun genres(favorites: Collection<String>): List<GenreDef> {
        val p = profile()
        val top = p.genres.firstOrNull()?.score ?: 0.0
        val ids = p.genres.filter { it.picked || it.score >= LEARNED_SHARE * top }.map { it.id }
            .let { learned -> learned + favorites.filter { it !in learned } }
        return ids.mapNotNull(GenreCatalog::byId).take(MAX_SHELVES)
    }

    /**
     * A genre's shelf for this listener: half from the radios of their own favourites in the genre
     * (their corner of it), half from the genre's live pool in their order (their artists and the
     * unheard first); another draw each session. Without listening in the genre: the pool in that order.
     */
    suspend fun shelf(genre: GenreDef, force: Boolean = false): List<RemoteTrack> {
        val p = profile()
        val pool = discover.shelfPool(genre, force)
        val mine = p.artists.filter { it.genre == genre.id }.take(SHELF_SEEDS)
            .mapNotNull { a -> a.songs.firstOrNull { it.videoId != null }?.videoId }
        val personal = if (mine.isEmpty()) emptyList() else Recommend.fromRadios(radios(mine), p, mine.toSet(), SHELF_SIZE / 2, onlyNew = false)
        val taken = personal.mapTo(HashSet()) { it.videoId }
        val rest = Recommend.order(pool.filter { it.videoId !in taken }, p, discover.drawSeed("shelf:" + genre.id), SHELF_SIZE - personal.size)
        return weave(personal, rest)
    }

    /** New songs like [seeds] (a page's "Recomendadas"): what their radios agree on, in the listener's taste. */
    suspend fun like(seeds: List<String>, exclude: Set<String>, count: Int): List<RemoteTrack> =
        Recommend.fromRadios(radios(seeds), profile(), exclude + seeds, count)

    private suspend fun radios(seeds: List<String>): List<List<RemoteTrack>> = coroutineScope {
        seeds.map { id -> async { runCatching { discover.related(id, RADIO_SIZE) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    private fun <T> weave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        val ia = a.iterator()
        val ib = b.iterator()
        while (ia.hasNext() || ib.hasNext()) {
            if (ia.hasNext()) out += ia.next()
            if (ib.hasNext()) out += ib.next()
        }
        return out
    }

    private companion object {
        const val PROFILE_TTL_MS = 2L * 60_000L
        /** How far back the listening is read (the long memory fades long before). */
        const val HISTORY_MS = 180L * 24 * 3_600_000L
        /** "Pra agora": your songs, the least to make it, the seeds of its new songs and how many new. */
        const val MOMENT_OWN = 16
        const val MOMENT_MIN = 5
        const val MOMENT_SEEDS = 3
        const val MOMENT_NEW = 9
        const val LEARN_FROM = 20
        const val DISCOVERY_SEEDS = 6
        const val DISCOVERIES = 20
        const val BECAUSE_POOL = 8
        /** The favourites always on the row; the others take turns. */
        const val BECAUSE_FIXED = 2
        const val BECAUSE_TURNS = 2
        const val BECAUSE_SEEDS = 2
        /** A card opens a mix: long enough to be one. */
        const val BECAUSE_SIZE = 30
        const val MIN_ROW = 4
        const val MAX_SHELVES = 6
        /** A learned genre gets a shelf when it has at least this share of the strongest one. */
        const val LEARNED_SHARE = 0.2
        const val SHELF_SEEDS = 2
        /** A genre's card opens a mix: long enough to be one. */
        const val SHELF_SIZE = 30
        const val RADIO_SIZE = 30
    }
}
