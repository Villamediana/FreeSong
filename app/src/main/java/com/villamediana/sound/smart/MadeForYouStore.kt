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

    private var builtFor = -1L
    private var job: Job? = null

    /** Makes the day's mixes when there are none for today yet (cheap otherwise). */
    fun refresh() {
        val day = SmartMixes.dayNumber(clock())
        if (builtFor == day || job?.isActive == true) return
        job = scope.launch {
            // Never let a failure here take the app down: the mixes just wait for the next try.
            runCatching { make(day) }
        }
    }

    private suspend fun make(day: Long) {
        run {
            val tracks = library.allTracks()
            val plans = MadeForYou.plan(tracks, library.loadTaste(), day)
            val l = labels()
            if (plans.isEmpty()) {
                _mixes.value = emptyList()
                builtFor = day
                return
            }
            // Your songs first (instant), then with the new ones.
            _mixes.value = plans.map { MadeForYou.assemble(it, emptyList(), l) }
            val inLibrary = tracks.mapNotNullTo(HashSet()) { it.videoId }
            val full = coroutineScope {
                plans.map { plan ->
                    async {
                        val new = discover.daily("foryou:" + plan.id, day) {
                            val radios = plan.seeds.mapNotNull { seed ->
                                seed.videoId?.let { id -> runCatching { discover.related(id, RADIO_SIZE) }.getOrNull() }
                            }
                            MadeForYou.pickNew(radios, inLibrary)
                        }.filter { it.videoId !in inLibrary }
                        MadeForYou.assemble(plan, new, l)
                    }
                }.awaitAll()
            }
            _mixes.value = full
            builtFor = day
        }
    }

    fun byId(id: String): Mix? = _mixes.value.firstOrNull { it.id == id }

    private companion object {
        const val RADIO_SIZE = 20
    }
}
