package com.villamediana.surco.playback.effects

import com.villamediana.surco.playback.EqPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAudioEffectsTest {

    private val five = EffectCaps(listOf(60, 230, 910, 3_600, 14_000), -1500, 1500, bassBoost = true, virtualizer = true)
    private val ten = EffectCaps(listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000), -1200, 1200, bassBoost = true, virtualizer = false)

    private class FakeChain(override val caps: EffectCaps) : EffectChain {
        val applied = mutableListOf<Pair<EffectConfig, Boolean>>()
        var released = false
        val last: EffectConfig get() = applied.last().first
        override fun apply(config: EffectConfig, force: Boolean) {
            check(!released) { "applied after release" }
            applied += config to force
        }
        override fun release() {
            released = true
        }
    }

    private class FakeFactory(private val probeCaps: EffectCaps?, private val sessionCaps: EffectCaps?) : EffectFactory {
        val opened = mutableMapOf<Int, FakeChain>()
        val regain = mutableMapOf<Int, () -> Unit>()
        var probes = 0
        override fun open(sessionId: Int, onControlRegained: () -> Unit): EffectChain? {
            val caps = sessionCaps ?: return null
            return FakeChain(caps).also {
                opened[sessionId] = it
                regain[sessionId] = onControlRegained
            }
        }
        override fun probe(): EffectCaps? {
            probes++
            return probeCaps
        }
    }

    private class MemoryStore(var saved: EffectsSettings = EffectsSettings()) : EffectsPersistence {
        var held: CompletableDeferred<Unit>? = null
        var saves = 0
        override suspend fun load(): EffectsSettings {
            held?.await()
            return saved
        }
        override suspend fun save(settings: EffectsSettings) {
            saved = settings
            saves++
        }
    }

    private fun TestScope.effects(store: EffectsPersistence, factory: EffectFactory, probe: Boolean = true) =
        DeviceAudioEffects(store, factory, backgroundScope, probeOnStart = probe).also { runCurrent() }

    private fun TestScope.settle() {
        advanceTimeBy(DeviceAudioEffects.SAVE_DEBOUNCE_MS + 50)
        runCurrent()
    }

    @Test
    fun beforeAnySessionTheStateShowsSavedChoicesOnProbedBands() = runTest {
        val store = MemoryStore(EffectsSettings(enabled = true, preset = EqPreset.BASS, bassBoost = 300))
        val factory = FakeFactory(probeCaps = five, sessionCaps = five)
        val fx = effects(store, factory)

        val s = fx.state.value
        assertTrue(s.available)
        assertTrue(s.enabled)
        assertEquals(EqPreset.BASS, s.preset)
        assertEquals(five.centersHz, s.bands.map { it.centerHz })
        assertEquals(EqCurves.presetLevels(EqPreset.BASS, five.centersHz, -1500, 1500), s.bands.map { it.levelMb })
        assertEquals(300, s.bassBoost)
        assertTrue(s.bassBoostAvailable && s.virtualizerAvailable)
        assertEquals(1, factory.probes)
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun withoutAProbeItIsUnavailableUntilASessionArrives() = runTest {
        val store = MemoryStore(EffectsSettings(enabled = true, preset = EqPreset.ROCK))
        val factory = FakeFactory(probeCaps = null, sessionCaps = ten)
        val fx = effects(store, factory)

        assertFalse(fx.state.value.available)
        assertTrue(fx.state.value.bands.isEmpty())
        assertTrue(fx.state.value.enabled)
        assertEquals(EqPreset.ROCK, fx.state.value.preset)
        assertFalse(fx.state.value.bassBoostAvailable)

        fx.attach(7)
        val s = fx.state.value
        assertTrue(s.available)
        assertEquals(10, s.bands.size)
        assertEquals(-1200, s.minLevelMb)
        assertEquals(1200, s.maxLevelMb)
        assertTrue(s.bassBoostAvailable)
        assertFalse(s.virtualizerAvailable)
    }

    @Test
    fun attachAppliesThePersistedSettings() = runTest {
        val store = MemoryStore(EffectsSettings(enabled = true, preset = EqPreset.LATIN, bassBoost = 400, virtualizer = 600))
        val factory = FakeFactory(probeCaps = five, sessionCaps = five)
        val fx = effects(store, factory)

        fx.attach(11)
        val chain = factory.opened.getValue(11)
        val (config, force) = chain.applied.last()
        assertTrue(force)
        assertTrue(config.enabled)
        assertEquals(EqCurves.presetLevels(EqPreset.LATIN, five.centersHz, -1500, 1500), config.levelsMb)
        assertEquals(400, config.bassBoost)
        assertEquals(600, config.virtualizer)
        assertEquals(config.levelsMb, fx.state.value.bands.map { it.levelMb })
    }

    @Test
    fun changesReachTheLiveEffectsAndArePersisted() = runTest {
        val store = MemoryStore()
        val factory = FakeFactory(probeCaps = five, sessionCaps = five)
        val fx = effects(store, factory)
        fx.attach(3)
        val chain = factory.opened.getValue(3)

        fx.setEnabled(true)
        assertTrue(chain.last.enabled)
        fx.selectPreset(EqPreset.VOCAL)
        assertEquals(EqCurves.presetLevels(EqPreset.VOCAL, five.centersHz, -1500, 1500), chain.last.levelsMb)
        fx.setBassBoost(250)
        fx.setVirtualizer(800)
        assertEquals(250, chain.last.bassBoost)
        assertEquals(800, chain.last.virtualizer)
        assertFalse("live changes are not forced", chain.applied.last().second)

        settle()
        assertEquals(EffectsSettings(enabled = true, preset = EqPreset.VOCAL, bassBoost = 250, virtualizer = 800), store.saved)

        fx.setEnabled(false)
        assertFalse(chain.last.enabled)
        assertFalse(fx.state.value.enabled)
    }

    @Test
    fun movingABandMakesACustomCurveThatTravelsByFrequency() = runTest {
        val store = MemoryStore(EffectsSettings(enabled = true, preset = EqPreset.ROCK))
        val factory = FakeFactory(probeCaps = five, sessionCaps = five)
        val fx = effects(store, factory)
        val rock = EqCurves.presetLevels(EqPreset.ROCK, five.centersHz, -1500, 1500)

        fx.setBandLevel(2, 600)
        val s = fx.state.value
        assertNull(s.preset)
        val expected = rock.toMutableList().also { it[2] = 600 }
        assertEquals(expected, s.bands.map { it.levelMb })

        settle()
        assertNull(store.saved.preset)
        assertEquals(EqCurves.levelsByFrequency(five.centersHz, expected), store.saved.customLevels)

        // Same phone, next launch: identical curve.
        val again = effects(MemoryStore(store.saved), FakeFactory(five, five))
        assertEquals(expected, again.state.value.bands.map { it.levelMb })
        assertNull(again.state.value.preset)

        // A 10-band phone: the curve is rebuilt from the frequencies and clamped to ±12 dB.
        val other = effects(MemoryStore(store.saved), FakeFactory(ten, ten))
        val on10 = other.state.value.bands.map { it.levelMb }
        assertEquals(EqCurves.levelsFromFrequencies(store.saved.customLevels, ten.centersHz, -1200, 1200), on10)
        assertTrue("62 Hz follows the 60 Hz band closely", kotlin.math.abs(on10[1] - expected[0]) <= 20)
    }

    @Test
    fun valuesAreClampedAndBadBandsIgnored() = runTest {
        val fx = effects(MemoryStore(), FakeFactory(five, five))
        fx.setBandLevel(0, 99_999)
        assertEquals(1500, fx.state.value.bands[0].levelMb)
        fx.setBandLevel(4, -99_999)
        assertEquals(-1500, fx.state.value.bands[4].levelMb)
        val before = fx.state.value
        fx.setBandLevel(9, 300)
        fx.setBandLevel(-1, 300)
        assertEquals(before, fx.state.value)
        fx.setBassBoost(5_000)
        fx.setVirtualizer(-3)
        assertEquals(1000, fx.state.value.bassBoost)
        assertEquals(0, fx.state.value.virtualizer)
    }

    @Test
    fun resetGoesFlatButKeepsTheSwitch() = runTest {
        val store = MemoryStore(EffectsSettings(enabled = true, preset = null, customLevels = mapOf(60 to 900), bassBoost = 700, virtualizer = 300))
        val fx = effects(store, FakeFactory(five, five))
        assertNull(fx.state.value.preset)
        fx.reset()
        val s = fx.state.value
        assertEquals(EqPreset.FLAT, s.preset)
        assertTrue(s.bands.all { it.levelMb == 0 })
        assertEquals(0, s.bassBoost)
        assertEquals(0, s.virtualizer)
        assertTrue(s.enabled)
        settle()
        assertEquals(emptyMap<Int, Int>(), store.saved.customLevels)
    }

    @Test
    fun aNewSessionReleasesTheOldEffects() = runTest {
        val factory = FakeFactory(five, five)
        val fx = effects(MemoryStore(EffectsSettings(enabled = true)), factory)
        fx.attach(1)
        val first = factory.opened.getValue(1)
        fx.attach(2)
        assertTrue(first.released)
        val second = factory.opened.getValue(2)
        assertFalse(second.released)

        // Same session again: no new effects, settings pushed again (forced).
        val applies = second.applied.size
        fx.attach(2)
        assertSame(second, factory.opened.getValue(2))
        assertEquals(applies + 1, second.applied.size)
        assertTrue(second.applied.last().second)

        // No session: effects gone, the screen keeps the known bands.
        fx.attach(0)
        assertTrue(second.released)
        assertTrue(fx.state.value.available)

        fx.attach(5)
        fx.release()
        assertTrue(factory.opened.getValue(5).released)
        assertTrue(fx.state.value.available)
    }

    @Test
    fun regainingControlReappliesEverything() = runTest {
        val factory = FakeFactory(five, five)
        val fx = effects(MemoryStore(EffectsSettings(enabled = true, preset = EqPreset.JAZZ)), factory)
        fx.attach(4)
        val chain = factory.opened.getValue(4)
        val n = chain.applied.size
        factory.regain.getValue(4).invoke()
        assertEquals(n + 1, chain.applied.size)
        assertTrue(chain.applied.last().second)

        // A stale callback from a released session does nothing.
        fx.attach(6)
        val m = factory.opened.getValue(6).applied.size
        factory.regain.getValue(4).invoke()
        assertEquals(m, factory.opened.getValue(6).applied.size)
    }

    @Test
    fun changesBeforeTheSavedSettingsLoadAreKept() = runTest {
        val store = MemoryStore(EffectsSettings(enabled = false, preset = EqPreset.JAZZ, virtualizer = 500))
        store.held = CompletableDeferred()
        val fx = effects(store, FakeFactory(five, five))

        fx.setEnabled(true)
        assertTrue(fx.state.value.enabled)

        store.held!!.complete(Unit)
        runCurrent()
        val s = fx.state.value
        assertTrue(s.enabled)
        assertEquals(EqPreset.JAZZ, s.preset)
        assertEquals(500, s.virtualizer)
        settle()
        assertEquals(EffectsSettings(enabled = true, preset = EqPreset.JAZZ, virtualizer = 500), store.saved)
    }

    @Test
    fun aPhoneWithoutEffectsStaysUnavailable() = runTest {
        val factory = FakeFactory(probeCaps = null, sessionCaps = null)
        val fx = effects(MemoryStore(EffectsSettings(enabled = true, bassBoost = 200)), factory)
        fx.attach(9)
        val s = fx.state.value
        assertFalse(s.available)
        assertFalse(s.bassBoostAvailable)
        assertFalse(s.virtualizerAvailable)
        assertTrue(s.enabled)
        assertEquals(200, s.bassBoost)
        // Moving a band without bands is a no-op, not a crash.
        fx.setBandLevel(0, 100)
        assertEquals(EqPreset.FLAT, fx.state.value.preset)
    }

    @Test
    fun noProbeWhenAskedNotTo() = runTest {
        val factory = FakeFactory(five, five)
        effects(MemoryStore(), factory, probe = false)
        assertEquals(0, factory.probes)
    }
}
