package com.villamediana.sound.chords

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChordEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chart = ChordChart(listOf(TimedChord(0, 4_000, 9, ChordQuality.MINOR), TimedChord(4_000, 8_000, 5, ChordQuality.MAJOR)), MusicKey(9, true), 90.0)
    private fun subject(key: String) = ChordSubject(key, videoId = key, path = null, durationMs = 200_000)

    private fun TestScope.engine(
        find: (suspend (ChordSubject) -> SheetLookup)? = null,
        store: ChordStore = ChordStore(tmp.newFolder()),
        analyze: suspend (ChordSubject, (Float) -> Unit) -> ChordChart,
    ): Pair<ChordEngine, ChordStore> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Not backgroundScope: advanceUntilIdle leaves background work alone.
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return ChordEngine(scope, store, dispatcher, analyze, io = dispatcher, find = find) to store
    }

    private val written = ChordChart(
        emptyList(), MusicKey(7, false),
        sheet = WrittenSheet(listOf(SheetLine("Uma linha", listOf(SheetChord(0, "G")))), "Cifra Club", "https://example.com/c"),
        looked = true,
    )

    @Test fun aWrittenSheetComesFirstAndNothingIsHeard() = runTest {
        var heard = 0
        val seen = mutableListOf<ChordState>()
        lateinit var engine: ChordEngine
        val (made, store) = engine(find = { s ->
            seen += engine.current(s.key)
            SheetLookup.Found(written)
        }) { _, _ -> heard++; chart }
        engine = made
        engine.request(subject("a"))
        advanceUntilIdle()
        assertEquals(ChordState.Working(0f, looking = true), seen.single())
        assertEquals(ChordState.Ready(written), engine.current("a"))
        assertEquals(0, heard)
        assertEquals(written, store.read("a"))
    }

    @Test fun noSheetThenItIsHeardAndThatIsRemembered() = runTest {
        var asked = 0
        val (engine, store) = engine(find = { asked++; SheetLookup.Missing }) { _, _ -> chart }
        engine.request(subject("a"))
        advanceUntilIdle()
        assertEquals(ChordState.Ready(chart.copy(looked = true)), engine.current("a"))
        assertEquals(true, store.read("a")?.looked)
        // Another session: read back, the sheet isn't asked for again.
        val (again, _) = engine(find = { asked++; SheetLookup.Missing }, store = store) { _, _ -> error("heard again") }
        again.request(subject("a"))
        advanceUntilIdle()
        assertEquals(1, asked)
    }

    @Test fun heardWhileTheSheetCouldNotBeAskedForItIsAskedAgainLater() = runTest {
        var online = false
        var heard = 0
        val (engine, store) = engine(find = { if (online) SheetLookup.Found(written) else SheetLookup.Unreachable }) { _, _ -> heard++; chart }
        engine.request(subject("a"))
        advanceUntilIdle()
        assertEquals(ChordState.Ready(chart), engine.current("a"))
        assertEquals(false, store.read("a")?.looked)
        // Back online, the chords opened again: the sheet takes the heard chords' place.
        online = true
        engine.request(subject("a"))
        advanceUntilIdle()
        assertEquals(ChordState.Ready(written), engine.current("a"))
        assertEquals(written, store.read("a"))
        assertEquals(1, heard)
    }

    @Test fun hearsOnceThenRemembers() = runTest {
        var calls = 0
        val (engine, store) = engine { _, progress ->
            calls++
            progress(0.5f)
            chart
        }
        engine.request(subject("a"))
        advanceUntilIdle()
        assertEquals(ChordState.Ready(chart), engine.current("a"))
        engine.request(subject("a"))
        advanceUntilIdle()
        assertEquals(1, calls)
        // A new session reads it from disk.
        assertEquals(chart, store.read("a"))
    }

    @Test fun anotherSongDropsTheOneBeingHeard() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val (engine, _) = engine { s, _ ->
            if (s.key == "slow") {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
            chart
        }
        engine.request(subject("slow"))
        advanceUntilIdle()
        assertTrue(engine.current("slow") is ChordState.Working)
        engine.request(subject("next"))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(ChordState.Idle, engine.current("slow"))
        assertEquals(ChordState.Ready(chart), engine.current("next"))
    }

    @Test fun aDroppedHearingThatStillReportsDoesNotStickAsWorking() = runTest {
        // The model is mid-window when the listener skips: it reports once more before it notices.
        val gate = CompletableDeferred<Unit>()
        var heard = 0
        val (engine, _) = engine { s, progress ->
            if (s.key == "slow" && heard++ == 0) {
                withContext(NonCancellable) { gate.await() }
                progress(0.7f)
                currentCoroutineContext().ensureActive()
            }
            chart
        }
        engine.request(subject("slow"))
        advanceUntilIdle()
        engine.request(subject("next"))
        advanceUntilIdle()
        assertEquals(ChordState.Idle, engine.current("slow"))
        gate.complete(Unit)
        advanceUntilIdle()
        // Not "Ouvindo… 70%" forever: back on it, it is heard again.
        assertEquals(ChordState.Idle, engine.current("slow"))
        engine.request(subject("slow"))
        advanceUntilIdle()
        assertEquals(ChordState.Ready(chart), engine.current("slow"))
    }

    @Test fun closingTheChordsStopsTheHearing() = runTest {
        var cancelled = false
        val (engine, _) = engine { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        engine.request(subject("song"))
        advanceUntilIdle()
        engine.stop("other")
        advanceUntilIdle()
        assertTrue(!cancelled)
        engine.stop("song")
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(ChordState.Idle, engine.current("song"))
    }

    @Test fun recallReadsBackButNeverHears() = runTest {
        var calls = 0
        val (engine, store) = engine { _, _ -> calls++; chart }
        engine.recall(subject("never"))
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(ChordState.Idle, engine.current("never"))
        store.write("before", chart)
        engine.recall(subject("before"))
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(ChordState.Ready(chart), engine.current("before"))
    }

    @Test fun aFailureStaysUntilTriedAgain() = runTest {
        var fail = true
        val (engine, _) = engine { _, _ -> if (fail) throw ChordFailure(ChordState.Reason.OFFLINE) else chart }
        engine.request(subject("x"))
        advanceUntilIdle()
        assertEquals(ChordState.Failed(ChordState.Reason.OFFLINE), engine.current("x"))
        fail = false
        engine.request(subject("x"))
        advanceUntilIdle()
        assertEquals(ChordState.Failed(ChordState.Reason.OFFLINE), engine.current("x"))
        engine.request(subject("x"), force = true)
        advanceUntilIdle()
        assertEquals(ChordState.Ready(chart), engine.state("x").first())
    }

    @Test fun nothingHeardIsAFailureNotAnEmptyChart() = runTest {
        val (engine, store) = engine { _, _ -> ChordChart(emptyList()) }
        engine.request(subject("quiet"))
        advanceUntilIdle()
        assertEquals(ChordState.Failed(ChordState.Reason.NOTHING_HEARD), engine.current("quiet"))
        assertEquals(null, store.read("quiet"))
    }

    @Test fun anOldChartOnDiskIsHeardAgain() = runTest {
        val folder = tmp.newFolder()
        val store = ChordStore(folder)
        store.write("old", chart.copy(version = ChordChart.VERSION - 1))
        assertEquals(null, store.read("old"))
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
}
