package com.villamediana.sound.playback.effects

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.villamediana.sound.playback.EqPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EffectsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** One DataStore per block, closed afterwards — like a new process reading the same file. */
    private suspend fun <T> withStore(file: File, block: suspend (EffectsStore) -> T): T {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            return block(EffectsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })))
        } finally {
            scope.cancel()
            job.join()
        }
    }

    @Test
    fun nothingSavedGivesTheDefaults() = runBlocking {
        val s = withStore(File(tmp.root, "empty.preferences_pb")) { it.load() }
        assertEquals(EffectsSettings(), s)
        assertEquals(EqPreset.FLAT, s.preset)
    }

    @Test
    fun presetSettingsRoundTrip() = runBlocking {
        val file = File(tmp.root, "preset.preferences_pb")
        val saved = EffectsSettings(enabled = true, preset = EqPreset.LATE_NIGHT, bassBoost = 420, virtualizer = 1000)
        withStore(file) { it.save(saved) }
        assertEquals(saved, withStore(file) { it.load() })
    }

    @Test
    fun customCurveRoundTripsByFrequency() = runBlocking {
        val file = File(tmp.root, "custom.preferences_pb")
        val curve = mapOf(60 to 300, 230 to -150, 910 to 0, 3_600 to 520, 14_000 to -1500)
        withStore(file) { it.save(EffectsSettings(enabled = true, preset = null, customLevels = curve, bassBoost = 0, virtualizer = 250)) }
        val loaded = withStore(file) { it.load() }
        assertNull(loaded.preset)
        assertEquals(curve, loaded.customLevels)
        assertEquals(250, loaded.virtualizer)
        assertEquals(true, loaded.enabled)

        // Back to a preset: the custom curve is cleared from the file when emptied.
        withStore(file) { it.save(loaded.copy(preset = EqPreset.ROCK, customLevels = emptyMap())) }
        val again = withStore(file) { it.load() }
        assertEquals(EqPreset.ROCK, again.preset)
        assertEquals(emptyMap<Int, Int>(), again.customLevels)
    }

    @Test
    fun brokenValuesFallBackGently() {
        val store = EffectsStore(PreferenceDataStoreFactory.create(produceFile = { File(tmp.root, "unused.preferences_pb") }))
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("eq_preset") to "DISCO_9000",
            stringPreferencesKey("eq_custom_bands") to "{not json",
        )
        val s = store.decode(prefs)
        assertEquals(EqPreset.FLAT, s.preset)
        assertEquals(emptyMap<Int, Int>(), s.customLevels)

        val custom = store.decode(mutablePreferencesOf(stringPreferencesKey("eq_preset") to "CUSTOM"))
        assertNull(custom.preset)
    }

    @Test
    fun strengthsAreClampedOnRead() {
        val store = EffectsStore(PreferenceDataStoreFactory.create(produceFile = { File(tmp.root, "unused2.preferences_pb") }))
        val prefs = mutablePreferencesOf()
        store.encode(EffectsSettings(bassBoost = 5000, virtualizer = -20), prefs)
        val s = store.decode(prefs)
        assertEquals(1000, s.bassBoost)
        assertEquals(0, s.virtualizer)
    }
}
