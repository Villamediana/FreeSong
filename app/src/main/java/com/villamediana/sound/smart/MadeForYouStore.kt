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
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()

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
        val made = coroutineScope {
            plans.map { plan ->
                async {
                    val new = discover.daily("foryou:" + plan.id, day) {
                        val radios = plan.seeds.mapNotNull { seed ->
                            seed.videoId?.let { id -> runCatching { discover.related(id, RADIO_SIZE) }.getOrNull() }
                        }
                        MadeForYou.pickNew(radios, inLibrary)
                    }.filter { it.videoId !in inLibrary }
                    plan to MadeForYou.assemble(plan, new, l)
                }
            }.awaitAll()
        }
        _mixes.value = made.map { it.second }
        built = shape
        complete = made.all { (plan, mix) -> plan.seeds.isEmpty() || mix.tracks.size > plan.own.size }
    }

    fun byId(id: String): Mix? = _mixes.value.firstOrNull { it.id == id }

    private companion object {
        const val RADIO_SIZE = 20
    }
}
