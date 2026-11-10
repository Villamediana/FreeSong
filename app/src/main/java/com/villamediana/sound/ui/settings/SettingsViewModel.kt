package com.villamediana.sound.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.CacheKind
import com.villamediana.sound.data.StorageUse
import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.model.LibraryStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val store = graph.settings
    private val library = graph.library

    // Already read at app start: the screen's first frame has them (nothing pops in while it opens).
    val settings: StateFlow<Settings?> = graph.settingsNow

    val stats: StateFlow<LibraryStats?> = graph.libraryStats

    /** Whether the equalizer is shaping the sound (the "Equalizador" row says so). */
    val equalizerOn: StateFlow<Boolean> = graph.effects.state
        .map { it.available && it.enabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Downloads, caches and room left, measured together (so the numbers add up): once the screen
     * is in, again when the library changes, and right after every clean-up.
     */
    private val _storage = MutableStateFlow<StorageUse?>(null)
    val storage: StateFlow<StorageUse?> = _storage
    private val remeasure = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            var opening = true
            combine(
                graph.libraryStats.map { it?.trackCount to it?.bytes }.distinctUntilChanged(),
                remeasure,
            ) { _, _ -> }.collectLatest {
                // Walking the files waits for the screen to be in (it competes with the opening for the CPU).
                if (opening) {
                    kotlinx.coroutines.delay(OPEN_SETTLE_MS)
                    opening = false
                }
                _storage.value = graph.caches.measure()
            }
        }
    }

    fun clearCache(kind: CacheKind) = viewModelScope.launch {
        graph.caches.clear(kind)
        remeasure.update { it + 1 }
    }

    fun clearAllCaches() = viewModelScope.launch {
        graph.caches.clearAll()
        remeasure.update { it + 1 }
    }

    fun setQuality(q: AudioQuality) = launch { store.setAudioQuality(q) }
    fun setWifiOnly(v: Boolean) = launch { store.setWifiOnly(v) }
    fun setSmartDownloads(v: Boolean) = launch { store.setSmartDownloads(v) }

    /** "Lembrar lugares": on (the location was allowed by the screen), or off and every place forgotten. */
    fun setLearnPlaces(v: Boolean) = launch {
        store.setLearnPlaces(v)
        if (v) {
            runCatching { graph.places.now() }
        } else {
            graph.places.forget()
            graph.forYou.invalidate()
        }
    }
    fun setPlayerVideo(v: Boolean) = launch { store.setPlayerVideo(v) }
    fun setParallel(n: Int) = launch { store.setParallelDownloads(n) }
    fun setFetchLyrics(v: Boolean) = launch { store.setFetchLyrics(v) }
    fun setLikeDownloads(v: Boolean) = launch { store.setLikeDownloads(v) }
    fun setShowHome(v: Boolean) = launch { store.setShowHome(v) }
    fun setTheme(v: com.villamediana.sound.data.ThemeMode) = launch { store.setTheme(v) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        /** About the time Ajustes takes to slide in. */
        const val OPEN_SETTLE_MS = 450L
    }
}
