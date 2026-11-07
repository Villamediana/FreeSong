package com.villamediana.sound.ui.search

import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.stats.Recaps
import com.villamediana.sound.ui.nav.RemoteFind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The artist of the song in the open player, loaded ahead: a tap on their name opens their page
 * at once. Only that one artist is held — the next one replaces it (its load is dropped), and
 * nothing else is kept.
 */
class ArtistPrefetch(
    private val scope: CoroutineScope,
    /** Finds the page by name (artist / album) on YouTube Music. */
    private val find: suspend (CollectionKind, String, String?) -> RemoteCollection,
    /** Loads a page. */
    private val load: suspend (String, CollectionKind) -> CollectionDetail,
) {
    private var key: String? = null
    private var job: Deferred<CollectionDetail>? = null
    @Volatile private var failed = false

    private val _found = MutableStateFlow<Pair<String, RemoteCollection>?>(null)

    /** The last page found by name (route key → page): its picture shows before its songs arrive. */
    val found: StateFlow<Pair<String, RemoteCollection>?> = _found.asStateFlow()

    /** Loads [artist]'s page ahead (the one before is dropped). The same artist again: nothing. */
    fun prefetch(artist: String) {
        val name = Recaps.mainArtist(artist)
        if (name.isBlank()) return
        val k = RemoteFind.url(CollectionKind.ARTIST, name, null)
        synchronized(this) {
            if (k == key && job?.isCancelled == false && !failed) return
            job?.cancel()
            key = k
            failed = false
            job = scope.async {
                try {
                    fetch(k, CollectionKind.ARTIST, name, null)
                } catch (e: Throwable) {
                    if (e !is CancellationException) failed = true
                    throw e
                }
            }
        }
    }

    /** A page opened by name ([key]): the one loaded ahead when it is that one, else loaded now. */
    suspend fun open(key: String, kind: CollectionKind, title: String, author: String?): CollectionDetail {
        val ahead = synchronized(this) { job?.takeIf { this.key == key && !it.isCancelled && !failed } }
        if (ahead != null) {
            try {
                return ahead.await()
            } catch (e: CancellationException) {
                // Replaced by the next song's artist meanwhile: loaded here instead (or this page closed).
            } catch (_: Exception) {
            }
        }
        return fetch(key, kind, title, author)
    }

    private suspend fun fetch(key: String, kind: CollectionKind, title: String, author: String?): CollectionDetail {
        val page = find(kind, title, author)
        _found.value = key to page
        return load(page.url, kind)
    }
}
