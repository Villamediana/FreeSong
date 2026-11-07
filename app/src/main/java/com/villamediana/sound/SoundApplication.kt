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
        graph = AppGraph(this)
        instance = this
        Notifications.createChannels(this)
        graph.downloads.kick()
        // "Downloads inteligentes" follows its switch (scheduled on, cancelled off).
        graph.scope.launch {
            graph.settings.settings.map { it.smartDownloads }.distinctUntilChanged().collect { on ->
                runCatching { com.villamediana.sound.download.SmartDownloads.sync(this@SoundApplication, on) }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.http }))
                // Covers at the size they are drawn (rows don't download the 720 px art).
                add(com.villamediana.sound.online.ThumbnailSizeInterceptor())
            }
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
