package com.villamediana.sound.playback

import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.data.onlineTrackId
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.YouTubeLinks
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * An online (streamed) song as it travels from the app to the service and into the saved queue:
 * its video id plus what the player shows. In the app it is a transient [Track] (negative id, see
 * data/OnlineTrack.kt); in the player, a `yt:<videoId>` media item.
 */
@Serializable
data class OnlineItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    /** Watch page (music.youtube.com or youtube.com) — what "Compartilhar" sends. */
    val url: String? = null,
) {
    val trackId: Long get() = onlineTrackId(videoId)

    fun toRemote(): RemoteTrack = RemoteTrack(
        videoId = videoId,
        url = url?.takeIf { it.startsWith("http") } ?: YouTubeLinks.watchUrl(videoId, music = true),
        title = title,
        artist = artist,
        album = album?.takeIf { it.isNotBlank() },
        durationMs = durationMs?.takeIf { it > 0 },
        thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
    )

    fun toTrack(): Track = toRemote().asTransientTrack()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
        private val listSerializer = ListSerializer(serializer())

        fun of(remote: RemoteTrack): OnlineItem = OnlineItem(
            videoId = remote.videoId,
            title = remote.title,
            artist = remote.artist,
            album = remote.album,
            durationMs = remote.durationMs,
            thumbnailUrl = remote.thumbnailUrl,
            url = remote.url,
        )

        /** The online song behind a transient track; null for library tracks. */
        fun of(track: Track): OnlineItem? {
            if (!track.isOnline) return null
            val videoId = track.videoId?.takeIf { it.isNotBlank() } ?: return null
            return OnlineItem(
                videoId = videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs.takeIf { it > 0 },
                thumbnailUrl = track.coverPath,
                url = track.sourceUrl ?: track.filePath.takeIf { it.startsWith("http") },
            )
        }

        fun encodeList(items: List<OnlineItem>): String = json.encodeToString(listSerializer, items)

        fun decodeList(text: String?): List<OnlineItem> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
        }
    }
}

/** Pure decisions about queues that contain online songs. */
internal object OnlineQueue {
    /**
     * The songs a PLAY / QUEUE_ADD command names, in order: library ids (≥ 0) from [library],
     * online ids (< 0) from [online] — played from the library instead when that video is already
     * downloaded ([byVideoId]). Ids that resolve to nothing keep their position as null.
     */
    fun resolve(
        ids: List<Long>,
        online: Map<Long, OnlineItem>,
        library: Map<Long, Track>,
        byVideoId: Map<String, Track>,
    ): List<Track?> = ids.map { id ->
        if (id >= 0) library[id]
        else online[id]?.let { item -> byVideoId[item.videoId] ?: item.toTrack() }
    }
}
