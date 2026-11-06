package com.villamediana.surco.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Dominant color of a cover, tuned for dark backgrounds (never too dark, never neon).
 * Cached per path for the process lifetime.
 */
object CoverColors {
    private val cache = ConcurrentHashMap<String, Int>()

    suspend fun extract(path: String?): Int? {
        if (path == null) return null
        cache[path]?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return@withContext null
            val palette = Palette.from(bmp).maximumColorCount(16).generate()
            bmp.recycle()
            val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: return@withContext null
            tune(Color(swatch.rgb)).toArgb().also { cache[path] = it }
        }
    }

    /** Pushes the color into a usable band: luminance 0.12..0.45. */
    fun tune(c: Color): Color {
        var color = c
        repeat(6) {
            val l = color.luminance()
            color = when {
                l < 0.10f -> Color(color.red + (1 - color.red) * 0.18f, color.green + (1 - color.green) * 0.18f, color.blue + (1 - color.blue) * 0.18f)
                l > 0.45f -> Color(color.red * 0.82f, color.green * 0.82f, color.blue * 0.82f)
                else -> return color
            }
        }
        return color
    }
}

/** Composable helper: cached [Track.accentColor] first, else extracts from the file. */
@Composable
fun rememberCoverColor(path: String?, cached: Int? = null, fallback: Color = SurcoColors.Surface3): Color {
    var color by remember(path, cached) { mutableStateOf(cached?.let { Color(it) }) }
    LaunchedEffect(path, cached) {
        if (cached == null) color = CoverColors.extract(path)?.let { Color(it) }
    }
    return color ?: fallback
}
