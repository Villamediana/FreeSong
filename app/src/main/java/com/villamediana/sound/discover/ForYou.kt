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
import com.villamediana.sound.smart.Picks
import com.villamediana.sound.smart.Listen
import com.villamediana.sound.smart.Recommend
import com.villamediana.sound.smart.SmartMixes
import com.villamediana.sound.smart.TasteProfile
import com.villamediana.sound.smart.artistKey
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
import kotlin.math.abs
import kotlin.random.Random

/**
 * The Home's suggestions, from what the listener actually likes ([TasteProfile]: everything heard
 * on the phone and streamed, the skips, the hearts, the picked genres):
 * - "Descobertas pra você": the day's new songs, from the radios of their best loved songs across
 *   their genres; the same all day, new the next.
 * - "Porque você curte <artista>": their favourite artist right now and another one taking turns,
 *   fixed for the 6-hour slot (it doesn't change with every song played).
 * - The genre shelves of the genres they listen to (and picked), leaning to their artists.
 * Songs and artists they keep skipping never come back. None of it is the same list day after
 * day: each starts from other songs of theirs (drawn among the ones they like most), what was
 * suggested in the last days counts less ([DiscoverRepository.shown]) and a little chance moves
 * the rest; the songs they already know take a share at most, the rest is new.
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
     * "Pra agora": your songs that fit this moment ([MusicDna.songsFor] — drawn among the best
     * fits, the ones the mix had in the last days less: not the same ones every morning) and as
     * many new ones from their radios in the taste of the moment (made once per moment and day). Null while this
     * moment is still being learned (fewer than [MOMENT_MIN] songs heard around this hour): no
     * card rather than the songs of other hours; it shows up by itself once the moment is lived.
     * The friends along name the mix only when there is music heard with them.
     */
    suspend fun momentPlan(names: (Set<String>) -> List<String> = { emptyList() }): MomentPlan? {
        val p = profile()
        val dna = dna()
        if (dna.isEmpty) return null
        val m = moment()
        val fits = dna.songsFor(m, MOMENT_POOL)
        if (fits.size < MOMENT_MIN) return null
        val day = SmartMixes.dayNumber(clock())
        // The same draw all day for this moment (the mix doesn't change when the app is opened again).
        val rnd = Random(day * 1_000_003L + m.key.hashCode())
        val before = runCatching { discover.shown(today = false) }.getOrDefault(emptyMap())
        val songs = Picks.draw(fits, MOMENT_OWN, rnd, { it.videoId }) { before[it.videoId] }
        val tracks = library.allTracks()
        val byVideo = tracks.filter { it.videoId != null }.associateBy { it.videoId!! }
        val own = songs.map { s -> byVideo[s.videoId] ?: SongRef(s.videoId, s.title, s.artist).toRemote().asTransientTrack() }
        val inLibrary = tracks.mapNotNullTo(HashSet()) { it.videoId }
        val seeds = songs.take(MOMENT_SEEDS).map { it.videoId }
        val fresh = runCatching {
            discover.daily("moment-v2:" + m.key, day) {
                Recommend.fromRadios(radios(seeds), p.inMoment(dna, m), inLibrary + fits.map { it.videoId }, MOMENT_NEW, seen = discover.shown(), rnd = rnd)
            }
        }.getOrDefault(emptyList()).filter { it.videoId !in inLibrary && !p.avoids(it.videoId, it.artist) }
        runCatching { discover.markShown(songs.map { it.videoId }) }
        return MomentPlan(m, own, fresh, names(dna.known(m.people)))
    }

    /**
     * How well each song of the library fits right now (track id → factor, only the ones the
     * moment moves): what the shuffle and "a seguir" of the library lean to. Empty while the DNA
     * has nothing to say.
     */
    suspend fun libraryFit(): Map<Long, Double> {
        val dna = dna()
        if (dna.isEmpty) return emptyMap()
        val m = moment()
        val tracks = library.allTracks()
        return withContext(Dispatchers.Default) {
            val out = HashMap<Long, Double>()
            for (t in tracks) {
                val fit = dna.fit(t.videoId, artistKey(t.artist), m)
                if (abs(fit - 1.0) > 0.03) out[t.id] = fit
            }
            out
        }
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
        val list = discover.daily("discoveries-v2", day) {
            // Another draw of the songs they start from each day, and what the last days showed behind.
            val rnd = Random(day * 1_000_003L + 7)
            val seeds = p.seeds(DISCOVERY_SEEDS, rnd).mapNotNull { it.videoId }
            Recommend.fromRadios(radios(seeds), p, inLibrary + seeds, DISCOVERIES, seen = discover.shown(), rnd = rnd)
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
                    val tracks = discover.daily("because-v2:" + a.key, slot) {
                        // Not always from the artist's same two songs: two of their best, drawn each slot.
                        val rnd = Random(slot * 1_000_003L + a.key.hashCode())
                        val seeds = a.songs.mapNotNull { it.videoId }.take(BECAUSE_SEED_POOL).shuffled(rnd).take(BECAUSE_SEEDS)
                        Recommend.fromRadios(radios(seeds), p, inLibrary + seeds, BECAUSE_SIZE, perArtist = 3, seen = discover.shown(), rnd = rnd)
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
     * A genre's shelf for this listener: half from the radios of their own artists in the genre
     * (their corner of it — two of them drawn each session, from one of their best songs), half
     * from the genre's live pool in their order (their artists and the unheard first); another draw
     * each session, in the taste of the moment, mostly songs they don't know yet. Without listening
     * in the genre: the pool in that order.
     */
    suspend fun shelf(genre: GenreDef, force: Boolean = false): List<RemoteTrack> {
        val p = profileNow()
        val pool = discover.shelfPool(genre, force)
        val rnd = Random(discover.drawSeed("shelf-mine:" + genre.id))
        val seen = runCatching { discover.shown() }.getOrDefault(emptyMap())
        val mine = p.artists.filter { it.genre == genre.id }.take(SHELF_ARTISTS).shuffled(rnd).take(SHELF_SEEDS)
            .mapNotNull { a -> a.songs.filter { it.videoId != null }.take(SHELF_SONGS).randomOrNull(rnd)?.videoId }
        val personal = if (mine.isEmpty()) emptyList() else Recommend.fromRadios(radios(mine), p, mine.toSet(), SHELF_SIZE / 2, onlyNew = false, seen = seen, rnd = rnd)
        val taken = personal.mapTo(HashSet()) { it.videoId }
        val rest = Recommend.order(pool.filter { it.videoId !in taken }, p, discover.drawSeed("shelf:" + genre.id), SHELF_SIZE - personal.size, seen = seen)
        return weave(personal, rest)
    }

    /**
     * New songs like [seeds] (a page's "Recomendadas"): what their radios agree on, in the
     * listener's taste of the moment; another draw each session.
     */
    suspend fun like(seeds: List<String>, exclude: Set<String>, count: Int): List<RemoteTrack> =
        Recommend.fromRadios(
            radios(seeds), profileNow(), exclude + seeds, count,
            seen = runCatching { discover.shown() }.getOrDefault(emptyMap()),
            rnd = Random(discover.drawSeed("like:" + seeds.sorted().joinToString(","))),
        )

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
        /**
         * "Pra agora": the songs of yours it is drawn from and how many it takes, the least to
         * make it, the seeds of its new songs and how many new (as many as yours).
         */
        const val MOMENT_POOL = 30
        const val MOMENT_OWN = 12
        const val MOMENT_MIN = 5
        const val MOMENT_SEEDS = 3
        const val MOMENT_NEW = 12
        const val LEARN_FROM = 20
        const val DISCOVERY_SEEDS = 6
        const val DISCOVERIES = 20
        const val BECAUSE_POOL = 8
        /** The favourites always on the row; the others take turns. */
        const val BECAUSE_FIXED = 2
        const val BECAUSE_TURNS = 2
        const val BECAUSE_SEEDS = 2
        /** The artist's best songs the row's two seeds are drawn from. */
        const val BECAUSE_SEED_POOL = 5
        /** A card opens a mix: long enough to be one. */
        const val BECAUSE_SIZE = 30
        const val MIN_ROW = 4
        const val MAX_SHELVES = 6
        /** A learned genre gets a shelf when it has at least this share of the strongest one. */
        const val LEARNED_SHARE = 0.2
        const val SHELF_SEEDS = 2
        /** The listener's artists of the genre the shelf's seeds are drawn from, and the best songs of each. */
        const val SHELF_ARTISTS = 6
        const val SHELF_SONGS = 3
        /** A genre's card opens a mix: long enough to be one. */
        const val SHELF_SIZE = 30
        const val RADIO_SIZE = 30
    }
}
