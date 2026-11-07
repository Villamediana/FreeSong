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

    val settings: StateFlow<Settings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<LibraryStats?> = library.stats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everything Sound keeps on the phone (audio, covers, lyrics) — re-measured when the library's size changes. */
    val usedBytes: StateFlow<Long?> = library.stats
        .map { it.trackCount to it.bytes }
        .distinctUntilChanged()
        .mapLatest { withContext(Dispatchers.IO) { graph.storage.usedBytes() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Room left on the phone where the app keeps its files (re-measured with [usedBytes]). */
    val freeBytes: StateFlow<Long?> = library.stats
        .map { it.trackCount to it.bytes }
        .distinctUntilChanged()
        .mapLatest { withContext(Dispatchers.IO) { runCatching { android.os.StatFs(graph.appContext.filesDir.path).availableBytes }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the equalizer is shaping the sound (the "Equalizador" row says so). */
    val equalizerOn: StateFlow<Boolean> = graph.effects.state
        .map { it.available && it.enabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _historyCleared = MutableStateFlow(false)
    val historyCleared: StateFlow<Boolean> = _historyCleared

    fun setQuality(q: AudioQuality) = launch { store.setAudioQuality(q) }
    fun setWifiOnly(v: Boolean) = launch { store.setWifiOnly(v) }
    fun setSmartDownloads(v: Boolean) = launch { store.setSmartDownloads(v) }
    fun setParallel(n: Int) = launch { store.setParallelDownloads(n) }
    fun setFetchLyrics(v: Boolean) = launch { store.setFetchLyrics(v) }
    fun setLikeDownloads(v: Boolean) = launch { store.setLikeDownloads(v) }
    fun setShowHome(v: Boolean) = launch { store.setShowHome(v) }
    fun setTheme(v: com.villamediana.sound.data.ThemeMode) = launch { store.setTheme(v) }

    fun clearHistory() = launch {
        library.clearHistory()
        _historyCleared.value = true
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
