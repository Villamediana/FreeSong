package com.villamediana.surco.playback.effects

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric's audio effects are half-real (an Equalizer with 5 bands but no ranges, no bass
 * boost/virtualizer shadows) — a good stand-in for odd phones: nothing may throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AudioFxFactoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun probingAndOpeningNeverThrow() {
        val factory = AudioFxFactory(context)
        val caps = factory.probe()
        if (caps != null) {
            assertTrue(caps.centersHz.all { it > 0 })
            assertTrue(!caps.hasEqualizer || caps.minMb < caps.maxMb)
        }
        val chain = factory.open(4242) {}
        chain?.apply(EffectConfig(enabled = true, levelsMb = chain.caps.centersHz.map { 300 }, bassBoost = 500, virtualizer = 500))
        chain?.apply(EffectConfig(enabled = false, levelsMb = chain.caps.centersHz.map { 0 }, bassBoost = 0, virtualizer = 0), force = true)
        chain?.release()
        // No session, no effects.
        assertTrue(factory.open(0) {} == null)
    }

    @Test
    fun theRealControllerBootsOnARealContext() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fx = DeviceAudioEffects(context, scope)
            fx.attach(4243)
            fx.setEnabled(true)
            fx.setBassBoost(300)
            fx.attach(0)
            fx.release()
            assertTrue(fx.state.value.bassBoost == 300)
        } finally {
            scope.cancel()
        }
    }
}
