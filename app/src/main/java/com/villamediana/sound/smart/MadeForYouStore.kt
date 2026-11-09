package com.villamediana.sound.smart

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.discover.DiscoverRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The day's made-for-you mixes, shared by the Home cards, the mix page and the player (what the
 * card shows is what plays). Made once a day: your songs show at once, the new ones join as their
 * radios arrive (kept on disk for the day, so reopening the app keeps the same mix until midnight).
 * A library just starting out gets its mixes as its songs come in, not only the next day.
 */
class MadeForYouStore(
    private val library: LibraryRepository,
    private val discover: DiscoverRepository,
    private val labels: () -> MadeForYou.Labels,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The listener's taste: the songs and artists they keep skipping stay out of the mixes. */
    private val profile: (suspend () -> TasteProfile)? = null,
    /** "Pra agora", from the DNA: what fits this moment (null: not enough listening yet). */
    private val moment: (suspend () -> MomentPlan?)? = null,
    private val momentLabels: () -> MadeForYou.MomentLabels = { MadeForYou.MomentLabels({ _, _ -> "" }, { it }) },
    /** The moment now, as a key (cheap): the "Pra agora" mix is made again only when it changes. */
    private val momentKey: () -> String = { "" },
) {
    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    private val _moment = MutableStateFlow<Mix?>(null)
    /** "Pra agora" first, then the day's genre mixes. */
    val mixes: StateFlow<List<Mix>> = combine(_moment, _mixes) { m, list -> listOfNotNull(m) + list }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    /** The moment the "Pra agora" mix was made for, and the day. */
    private var momentMadeFor: String? = null

    /** What the current mixes were made from: the day, and each mix with how many of your songs it had. */
    private var built: Pair<Long, List<Pair<String, Int>>>? = null
    /** Every mix that can have new songs got them (a failure is tried again on the next refresh). */
    private var complete = false
    private val again = java.util.concurrent.atomic.AtomicBoolean(false)
    private var job: Job? = null

    /**
     * Makes the mixes when there are none for today yet, or when the library changed what they'd be
     * made of (a new genre, songs still filling a young mix): a mix lasts the day once it is full.
     */
    fun refresh() {
        if (job?.isActive == true) {
            again.set(true) // asked while making: look again once done
            return
        }
        job = scope.launch {
            do {
                again.set(false)
                // Never let a failure here take the app down: the mixes just wait for the next try.
                runCatching { make(SmartMixes.dayNumber(clock())) }
                runCatching { makeMoment(SmartMixes.dayNumber(clock())) }
            } while (again.get())
        }
    }

    private suspend fun make(day: Long) {
        val tracks = library.allTracks()
        val plans = MadeForYou.plan(tracks, library.loadTaste(), day)
        val shape = day to plans.map { it.id to it.own.size }
        if (shape == built && complete) return
        val l = labels()
        if (plans.isEmpty()) {
            _mixes.value = emptyList()
            built = shape
            complete = true
            return
        }
        // Your songs first (instant) when there is nothing on screen yet, then with the new ones.
        if (_mixes.value.isEmpty()) _mixes.value = plans.map { MadeForYou.assemble(it, emptyList(), l) }
        val inLibrary = tracks.mapNotNullTo(HashSet()) { it.videoId }
        val taste = profile?.let { runCatching { it() }.getOrNull() }
        val made = coroutineScope {
            plans.map { plan ->
                async {
                    // "v2": the mixes got bigger and match the style (yesterday's list doesn't carry over).
                    val new = discover.daily("foryou-v2:" + plan.id, day) {
                        val radios = plan.seeds.mapNotNull { seed ->
                            seed.videoId?.let { id -> runCatching { discover.related(id, RADIO_SIZE) }.getOrNull() }
                        }
                        MadeForYou.pickNew(radios, inLibrary)
                    }.filter { it.videoId !in inLibrary && taste?.avoids(it.videoId, it.artist) != true }
                    plan to MadeForYou.assemble(plan, new, l)
                }
            }.awaitAll()
        }
        _mixes.value = made.map { it.second }
        built = shape
        complete = made.all { (plan, mix) -> plan.seeds.isEmpty() || mix.tracks.any { it.id < 0 } }
    }

    fun byId(id: String): Mix? = mixes.value.firstOrNull { it.id == id } ?: _mixes.value.firstOrNull { it.id == id }

    /** "Pra agora": made again when the moment changes (another part of the day, another place, a jam). */
    private suspend fun makeMoment(day: Long) {
        val make = moment ?: return
        // The same moment as the mix on screen: nothing to make (no work on every return to the Home).
        if (_moment.value != null && momentMadeFor == "$day|${momentKey()}") return
        val plan = make() ?: run {
            _moment.value = null
            momentMadeFor = null
            return
        }
        val key = "$day|${plan.moment.key}"
        if (key == momentMadeFor && _moment.value != null) return
        _moment.value = MadeForYou.assembleMoment(plan, momentLabels())
        momentMadeFor = key
    }

    private companion object {
        /** Songs asked of each radio: the more each brings, the better they can be compared. */
        const val RADIO_SIZE = 50
    }
}
