package com.villamediana.sound

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class SoundApplication : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // If the app ever closes by a crash, its first lines are kept (the update sheet shows why).
        runCatching { com.villamediana.sound.update.ExitReport.catchCrashes(this) }
        graph = AppGraph(this)
        instance = this
        Notifications.createChannels(this)
        graph.downloads.kick()
        // The listening history keeps a bit over a year (no table growing forever); the hours keep counting.
        graph.scope.launch {
            runCatching {
                val removed = graph.library.pruneHistory()
                if (removed > 0) graph.settings.addListenedBefore(removed)
            }
        }
        // "Downloads inteligentes" follows its switch (scheduled on, cancelled off).
        graph.scope.launch {
            graph.settings.settings.map { it.smartDownloads }.distinctUntilChanged().collect { on ->
                runCatching { com.villamediana.sound.download.SmartDownloads.sync(this@SoundApplication, on) }
            }
        }
        // Profile (made by itself), friends, shared songs and the jam; a check an hour with the app closed.
        graph.social.start()
        runCatching { com.villamediana.sound.social.SocialWorker.schedule(this) }
        // New versions from GitHub: a check a day in the background (release builds only).
        if (graph.updates.enabled) runCatching { com.villamediana.sound.update.UpdateCheckWorker.schedule(this) }
        // Android 12–13: a version ready installs itself once the app leaves the screen (14+ waits by itself).
        runCatching { com.villamediana.sound.update.UpdateIdleWorker.watch(this, graph.updates) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(128L * 1024 * 1024).build() }
            .crossfade(180)
            .build()

    companion object {
        lateinit var instance: SoundApplication
            private set
        val graph: AppGraph get() = instance.graph
    }
}
