package com.villamediana.sound.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A song of a Spotify playlist: what is needed to find it on YouTube Music. */
data class SpotifyTrack(val title: String, val artist: String, val durationMs: Long? = null)

/** What a Spotify link points to that can be brought in: a playlist or an album. */
enum class SpotifyKind(val path: String) { PLAYLIST("playlist"), ALBUM("album") }

data class SpotifyRef(val kind: SpotifyKind, val id: String) {
    val url: String get() = "https://open.spotify.com/${kind.path}/$id"
}

/** A Spotify playlist or album (an album comes in as a playlist of its songs). */
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>,
    val kind: SpotifyKind = SpotifyKind.PLAYLIST,
) {
    /** Where it came from: a second import of the same link refreshes the same local playlist. */
    val url: String get() = SpotifyRef(kind, id).url
}

sealed class SpotifyError : Exception() {
    /** The text has no Spotify playlist or album link. */
    data object NotALink : SpotifyError() { private fun readResolve(): Any = NotALink }

    /** Private, deleted or never existed: Spotify shows it to no one without a login. */
    data object NotFound : SpotifyError() { private fun readResolve(): Any = NotFound }

    /** No connection (or Spotify did not answer). */
    data object Offline : SpotifyError() { private fun readResolve(): Any = Offline }
}

object SpotifyLinks {
    private val LINK = Regex("""(?:open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(playlist|album)/|spotify:(playlist|album):)([A-Za-z0-9]{22})""")
    private val SHORT = Regex("""https?://spotify\.link/[A-Za-z0-9_-]+""")

    /** The playlist or album a text links to. */
    fun ref(text: String): SpotifyRef? = LINK.find(text)?.let { m ->
        val kind = m.groupValues[1].ifEmpty { m.groupValues[2] }
        SpotifyRef(if (kind == "album") SpotifyKind.ALBUM else SpotifyKind.PLAYLIST, m.groupValues[3])
    }

    /** spotify.link/… — the app's short share links, resolved by following them. */
    fun shortLink(text: String): String? = SHORT.find(text)?.value

    /** Text with a Spotify playlist or album link (full or short). */
    fun mentions(text: String): Boolean = ref(text) != null || shortLink(text) != null

    /** A list imported from Spotify (its url is the Spotify one). */
    fun isImported(url: String): Boolean = ref(url) != null
}

/**
 * Reads a public Spotify playlist or album without any login: the embed page (the player Spotify
 * lets sites put on their pages) carries the name, the cover and the songs — up to 100 of them.
 */
class SpotifyPlaylists(private val http: OkHttpClient) {

    /** The playlist or album a link (or text with one) points to. Throws [SpotifyError]. */
    suspend fun find(text: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val ref = SpotifyLinks.ref(text)
            ?: SpotifyLinks.shortLink(text)?.let { resolveShort(it) }
            ?: throw SpotifyError.NotALink
        val html = get("https://open.spotify.com/embed/${ref.kind.path}/${ref.id}") ?: throw SpotifyError.NotFound
        parseEmbed(ref.id, html, ref.kind) ?: throw SpotifyError.NotFound
    }

    private fun resolveShort(url: String): SpotifyRef? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return try {
            http.newCall(request).execute().use { r ->
                SpotifyLinks.ref(r.request.url.toString()) ?: SpotifyLinks.ref(r.body.string())
            }
        } catch (e: IOException) {
            throw SpotifyError.Offline
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .build()
        return try {
            http.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> r.body.string()
                    r.code == 404 || r.code == 400 -> null
                    else -> throw SpotifyError.Offline
                }
            }
        } catch (e: IOException) {
            throw SpotifyError.Offline
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val json = Json { ignoreUnknownKeys = true }

        /** The playlist (or album) in an embed page, or null when the page says it doesn't exist (private / deleted). */
        fun parseEmbed(id: String, html: String, kind: SpotifyKind = SpotifyKind.PLAYLIST): SpotifyPlaylist? {
            val raw = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
            val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
            val page = root.obj("props")?.obj("pageProps") ?: return null
            if (page.int("status")?.let { it >= 400 } == true) return null
            val entity = page.obj("state")?.obj("data")?.obj("entity") ?: return null
            val tracks = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { e ->
                val t = e as? JsonObject ?: return@mapNotNull null
                val title = t.str("title")?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                // Spotify writes the artists "A, B" with no-break spaces at times.
                val artist = t.str("subtitle").orEmpty().replace(' ', ' ').trim()
                SpotifyTrack(title, artist, t.long("duration")?.takeIf { it > 0 })
            }
            val name = entity.str("name") ?: entity.str("title") ?: return null
            // Playlists bring "coverArt"; albums only the images of "visualIdentity".
            fun largest(list: JsonArray?, width: String) = list
                ?.mapNotNull { (it as? JsonObject)?.let { s -> s.str("url")?.let { url -> url to (s.int(width) ?: 0) } } }
                ?.maxByOrNull { it.second }?.first
            val cover = largest(entity.obj("coverArt")?.get("sources") as? JsonArray, "width")
                ?: largest(entity.obj("visualIdentity")?.get("image") as? JsonArray, "maxWidth")
            return SpotifyPlaylist(id, name, entity.str("subtitle")?.takeIf { it.isNotBlank() }, cover, tracks, kind)
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.prim(key: String) = (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive }.getOrNull() }
        private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
        private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
    }
}
