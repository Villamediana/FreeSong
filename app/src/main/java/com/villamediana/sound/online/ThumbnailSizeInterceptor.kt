package com.villamediana.sound.online

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension

/**
 * Every cover is fetched at about the size it is drawn: rows and cards ask Google's image servers
 * for a small picture instead of the 720 px art (see [Thumbnails.sized]). The big player cover
 * still gets the large one.
 */
class ThumbnailSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http")) return chain.proceed()
        val w = (chain.size.width as? Dimension.Pixels)?.px ?: 0
        val h = (chain.size.height as? Dimension.Pixels)?.px ?: 0
        val sized = Thumbnails.sized(url, maxOf(w, h))
        if (sized == url) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
    }
}
