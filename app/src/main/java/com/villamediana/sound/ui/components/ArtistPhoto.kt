package com.villamediana.sound.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.villamediana.sound.discover.DiscoverRepository
import com.villamediana.sound.stats.Recaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Artists' own pictures (their YouTube Music page), found once by name and kept with the artist
 * page (on disk). Until found — or offline — the album cover stands in.
 */
class ArtistPhotos(private val discover: DiscoverRepository, private val scope: CoroutineScope) {
    private val _found = MutableStateFlow<Map<String, String>>(emptyMap())
    val found: StateFlow<Map<String, String>> = _found.asStateFlow()
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The picture known now, without waiting. */
    fun peek(name: String, exact: Boolean = false): String? = key(name, exact).let { k -> _found.value[k] ?: discover.peekArtist(k)?.thumbnailUrl }

    fun request(name: String, exact: Boolean = false) {
        val k = key(name, exact)
        if (k.isEmpty() || _found.value.containsKey(k) || !asked.add(k)) return
        scope.launch {
            val photo = discover.artist(k)?.thumbnailUrl
            if (photo != null) _found.update { it + (k to photo) } else asked.remove(k) // asked again later (offline)
        }
    }

    /** [exact]: [name] is already one artist (a duo the numbers credit whole), not a credit to cut down. */
    internal fun key(name: String, exact: Boolean = false) = if (exact) name.trim() else Recaps.mainArtist(name).trim()
}

/** The app's [ArtistPhotos] (none in tests and previews: the album cover). */
val LocalArtistPhotos = staticCompositionLocalOf<ArtistPhotos?> { null }

/** The artist's own picture in a circle; the album cover [fallback] until it is found. [exact]: see [ArtistPhotos.key]. */
@Composable
fun ArtistPhoto(name: String, fallback: String?, modifier: Modifier = Modifier, exact: Boolean = false) {
    val photos = LocalArtistPhotos.current
    val photo = if (photos == null) {
        null
    } else {
        LaunchedEffect(name, exact) { photos.request(name, exact) }
        val k = remember(name, exact) { photos.key(name, exact) }
        val found by remember(photos, k) { photos.found.map { it[k] }.distinctUntilChanged() }.collectAsState(initial = photos.peek(name, exact))
        found ?: photos.peek(name, exact)
    }
    Cover(photo ?: fallback, modifier, seed = name, shape = CircleShape)
}
