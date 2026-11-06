package com.villamediana.surco.playback

import android.content.Context
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.SettingsStore
import com.villamediana.surco.data.db.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** MediaController-backed [PlayerConnection]. (Stub — implemented by the playback module.) */
class MediaPlayerConnection(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : PlayerConnection {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    override val positionMs: Flow<Long> = flowOf(0L)
    override val sleepTimer: StateFlow<SleepTimer> = MutableStateFlow(SleepTimer.Off)
    override fun play(tracks: List<Track>, startIndex: Int, source: PlaySource, shuffle: Boolean) {}
    override fun playSmart(seed: SmartSeed) {}
    override fun togglePlayPause() {}
    override fun pause() {}
    override fun next() {}
    override fun previous() {}
    override fun seekTo(positionMs: Long) {}
    override fun skipTo(queueIndex: Int) {}
    override fun setShuffle(enabled: Boolean) {}
    override fun cycleRepeat() {}
    override fun playNext(tracks: List<Track>) {}
    override fun enqueue(tracks: List<Track>) {}
    override fun removeAt(queueIndex: Int) {}
    override fun move(from: Int, to: Int) {}
    override fun setSleepTimer(timer: SleepTimer) {}
}
