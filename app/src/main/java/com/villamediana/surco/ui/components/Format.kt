package com.villamediana.surco.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.villamediana.surco.R
import java.util.Locale

/**
 * "1 música" · "12 músicas" · "Nenhuma música". Zero has its own text: Portuguese plural rules put
 * 0 in "one", so the plural alone reads "0 música" (and a "zero" item is ignored for pt).
 */
@Composable
fun songCount(n: Int): String =
    if (n == 0) stringResource(R.string.n_songs_none) else pluralStringResource(R.plurals.n_songs, n, n)

/** [songCount] outside composition. */
fun songCount(res: Resources, n: Int): String =
    if (n == 0) res.getString(R.string.n_songs_none) else res.getQuantityString(R.plurals.n_songs, n, n)

/** 3:07 · 1:02:33 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** "2 h 14 min" / "47 min" — for album/playlist headers. */
fun formatLongDuration(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** 1,2 GB · 340 MB */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
