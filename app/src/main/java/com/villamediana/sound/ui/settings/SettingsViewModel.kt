package com.villamediana.sound.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.villamediana.sound.SoundApplication
import com.villamediana.sound.data.AudioQuality
import com.villamediana.sound.data.Settings
import com.villamediana.sound.data.model.LibraryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = SoundApplication.graph
    private val store = graph.settings
    private val library = graph.library

    // Already read at app start: the screen's first frame has them (nothing pops in while it opens).
    val settings: StateFlow<Settings?> = graph.settingsNow

    val stats: StateFlow<LibraryStats?> = graph.libraryStats

    /** Everything Sound keeps on the phone (audio, covers, lyrics) — re-measured when the library's size changes. */
    val usedBytes: StateFlow<Long?> = graph.libraryStats.filterNotNull()
        .map { it.trackCount to it.bytes }
        .distinctUntilChanged()
        // Walking the files waits for the screen to be in (it competes with the opening for the CPU).
        .mapLatest { kotlinx.coroutines.delay(OPEN_SETTLE_MS); withContext(Dispatchers.IO) { graph.storage.usedBytes() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Room left on the phone where the app keeps its files (re-measured with [usedBytes]). */
    val freeBytes: StateFlow<Long?> = graph.libraryStats.filterNotNull()
        .map { it.trackCount to it.bytes }
        .distinctUntilChanged()
        .mapLatest { withContext(Dispatchers.IO) { runCatching { android.os.StatFs(graph.appContext.filesDir.path).availableBytes }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the equalizer is shaping the sound (the "Equalizador" row says so). */
    val equalizerOn: StateFlow<Boolean> = graph.effects.state
        .map { it.available && it.enabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What each cache holds on the phone (measured on open and after a clean-up). */
    private val _caches = MutableStateFlow<Map<com.villamediana.sound.data.CacheKind, Long>?>(null)
    val caches: StateFlow<Map<com.villamediana.sound.data.CacheKind, Long>?> = _caches

    init {
        // Measured once the screen is in (the sizes touch the disk; nothing to show before the row).
        viewModelScope.launch {
            kotlinx.coroutines.delay(OPEN_SETTLE_MS)
            _caches.value = graph.caches.sizes()
        }
    }

    fun clearCache(kind: com.villamediana.sound.data.CacheKind) = viewModelScope.launch {
        graph.caches.clear(kind)
        _caches.value = graph.caches.sizes()
    }

    fun clearAllCaches() = viewModelScope.launch {
        graph.caches.clearAll()
        _caches.value = graph.caches.sizes()
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
