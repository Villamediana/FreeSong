package com.villamediana.surco.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "surco_settings")

enum class AudioQuality {
    /** Highest bitrate available (usually Opus ~160 kbps). */
    BEST,
    /** Prefer AAC/M4A (~128 kbps) — widest compatibility, smaller files. */
    COMPATIBLE,
    /** Lowest bitrate (~50–70 kbps) — saves space. */
    SAVER,
}

data class Settings(
    val audioQuality: AudioQuality = AudioQuality.BEST,
    val wifiOnly: Boolean = false,
    val fetchLyrics: Boolean = true,
    val parallelDownloads: Int = 2,
    /** Smart shuffle = weighted by taste, no same artist back to back. Plain shuffle otherwise. */
    val smartShuffle: Boolean = true,
    /** When the queue ends, keep playing similar songs from the library. */
    val autoplaySimilar: Boolean = true,
    /** Normalize volume between tracks (ExoPlayer loudness). */
    val normalizeVolume: Boolean = false,
    val lastQueueJson: String? = null,
)

class SettingsStore(private val context: Context) {
    private object K {
        val quality = stringPreferencesKey("audio_quality")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val lyrics = booleanPreferencesKey("fetch_lyrics")
        val parallel = intPreferencesKey("parallel_downloads")
        val smartShuffle = booleanPreferencesKey("smart_shuffle")
        val autoplay = booleanPreferencesKey("autoplay_similar")
        val normalize = booleanPreferencesKey("normalize_volume")
        val lastQueue = stringPreferencesKey("last_queue")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            audioQuality = p[K.quality]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: AudioQuality.BEST,
            wifiOnly = p[K.wifiOnly] ?: false,
            fetchLyrics = p[K.lyrics] ?: true,
            parallelDownloads = (p[K.parallel] ?: 2).coerceIn(1, 4),
            smartShuffle = p[K.smartShuffle] ?: true,
            autoplaySimilar = p[K.autoplay] ?: true,
            normalizeVolume = p[K.normalize] ?: false,
            lastQueueJson = p[K.lastQueue],
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setAudioQuality(v: AudioQuality) = context.dataStore.edit { it[K.quality] = v.name }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setFetchLyrics(v: Boolean) = context.dataStore.edit { it[K.lyrics] = v }
    suspend fun setParallelDownloads(v: Int) = context.dataStore.edit { it[K.parallel] = v.coerceIn(1, 4) }
    suspend fun setSmartShuffle(v: Boolean) = context.dataStore.edit { it[K.smartShuffle] = v }
    suspend fun setAutoplaySimilar(v: Boolean) = context.dataStore.edit { it[K.autoplay] = v }
    suspend fun setNormalizeVolume(v: Boolean) = context.dataStore.edit { it[K.normalize] = v }
    suspend fun setLastQueueJson(v: String?) = context.dataStore.edit { if (v == null) it.remove(K.lastQueue) else it[K.lastQueue] = v }
}
