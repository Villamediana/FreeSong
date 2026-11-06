package com.villamediana.surco.playback.effects

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.villamediana.surco.playback.EqPreset
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the listener chose — independent of the phone (custom bands are kept by frequency). */
data class EffectsSettings(
    val enabled: Boolean = false,
    /** null = custom curve ([customLevels]). */
    val preset: EqPreset? = EqPreset.FLAT,
    /** Custom curve: centre frequency (Hz) → level (mB). */
    val customLevels: Map<Int, Int> = emptyMap(),
    /** 0..1000 */
    val bassBoost: Int = 0,
    /** 0..1000 */
    val virtualizer: Int = 0,
)

internal interface EffectsPersistence {
    suspend fun load(): EffectsSettings
    suspend fun save(settings: EffectsSettings)
}

private val Context.effectsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "surco_effects",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The equalizer's own DataStore file (`surco_effects`). */
internal class EffectsStore(private val dataStore: DataStore<Preferences>) : EffectsPersistence {
    constructor(context: Context) : this(context.applicationContext.effectsDataStore)

    override suspend fun load(): EffectsSettings =
        runCatching { decode(dataStore.data.first()) }.getOrDefault(EffectsSettings())

    override suspend fun save(settings: EffectsSettings) {
        dataStore.edit { encode(settings, it) }
    }

    @Serializable
    private data class SavedBand(val hz: Int, val mb: Int)

    private object K {
        val enabled = booleanPreferencesKey("eq_enabled")
        val preset = stringPreferencesKey("eq_preset")
        val custom = stringPreferencesKey("eq_custom_bands")
        val bass = intPreferencesKey("bass_boost")
        val virtualizer = intPreferencesKey("virtualizer")
    }

    private val json = Json { ignoreUnknownKeys = true }

    internal fun decode(p: Preferences): EffectsSettings {
        val preset = when (val raw = p[K.preset]) {
            null -> EqPreset.FLAT
            CUSTOM -> null
            else -> EqPreset.entries.firstOrNull { it.name == raw } ?: EqPreset.FLAT
        }
        val custom = p[K.custom]
            ?.let { raw -> runCatching { json.decodeFromString<List<SavedBand>>(raw) }.getOrNull() }
            ?.associate { it.hz to it.mb }
            .orEmpty()
        return EffectsSettings(
            enabled = p[K.enabled] ?: false,
            preset = preset,
            customLevels = custom,
            bassBoost = (p[K.bass] ?: 0).coerceIn(0, STRENGTH_MAX),
            virtualizer = (p[K.virtualizer] ?: 0).coerceIn(0, STRENGTH_MAX),
        )
    }

    internal fun encode(s: EffectsSettings, p: MutablePreferences) {
        p[K.enabled] = s.enabled
        p[K.preset] = s.preset?.name ?: CUSTOM
        if (s.customLevels.isEmpty()) {
            p.remove(K.custom)
        } else {
            val bands = s.customLevels.entries.sortedBy { it.key }.map { SavedBand(it.key, it.value) }
            p[K.custom] = json.encodeToString(bands)
        }
        p[K.bass] = s.bassBoost
        p[K.virtualizer] = s.virtualizer
    }

    private companion object {
        const val CUSTOM = "CUSTOM"
    }
}

/** BassBoost / Virtualizer strength scale (per mille). */
internal const val STRENGTH_MAX = 1000
