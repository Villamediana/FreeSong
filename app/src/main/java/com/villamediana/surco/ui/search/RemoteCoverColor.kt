package com.villamediana.surco.ui.search

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.villamediana.surco.ui.components.CoverColors
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Dominant color of an online thumbnail (same tuning as [CoverColors], which only reads local
 * files). Goes through Coil, so the image is usually already in its memory/disk cache.
 */
object RemoteCoverColors {
    private val cache = ConcurrentHashMap<String, Int>()

    fun cached(url: String?): Int? = url?.let { cache[it] }

    suspend fun extract(context: Context, url: String?): Int? {
        if (url.isNullOrBlank()) return null
        cache[url]?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(128)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap()
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: return@withContext null
            CoverColors.tune(Color(swatch.rgb)).toArgb().also { cache[url] = it }
        }
    }
}

/** Animated cover color for an online thumbnail; [fallback] until it is known. */
@Composable
fun rememberRemoteCoverColor(url: String?, fallback: Color = SurcoColors.Surface3): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(RemoteCoverColors.cached(url)?.let { Color(it) }) }
    LaunchedEffect(url) {
        if (color == null) color = runCatching { RemoteCoverColors.extract(context, url) }.getOrNull()?.let { Color(it) }
    }
    val animated by animateColorAsState(color ?: fallback, tween(520), label = "remoteCover")
    return animated
}
