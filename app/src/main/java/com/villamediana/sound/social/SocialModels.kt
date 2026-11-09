package com.villamediana.sound.social

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.online.YouTubeLinks
import com.villamediana.sound.playback.OnlineItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON of the relay (unknown fields ignored: the server may grow). */
internal val RelayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

/**
 * Someone on FreeSong. Everyone sees the photo, @username, name and since when; [listenedMs] and
 * [top] come only for friends (and for oneself).
 */
@Serializable
data class Profile(
    val id: String,
    val username: String,
    val name: String,
    /** "/avatars/<id>.jpg?v=N" on the relay, or null. */
    val avatar: String? = null,
    val since: Long = 0,
    val listenedMs: Long? = null,
    val top: List<SongRef>? = null,
    /** The profile's numbers of the month and the year (friends and oneself only; apps before 1.21 send none). */
    val numbers: Numbers? = null,
    /** none · outgoing · incoming · friends · self (lists only). */
    val relation: String? = null,
    val requestId: Long? = null,
    val friendsSince: Long? = null,
) {
    val isFriend: Boolean get() = relation == "friends" || friendsSince != null
}

/** A song as it travels between phones: its YouTube video and what the player shows. */
/** "Seus números": totals of the month and of the year, worked out on the phone (no history). */
@Serializable
data class Numbers(val month: NumPeriod? = null, val year: NumPeriod? = null)

@Serializable
data class NumPeriod(
    val minutes: Long = 0,
    val songs: Int = 0,
    val artists: Int = 0,
    /** 0–23, the hour with the most listening. */
    val hour: Int? = null,
    /** Minutes per weekday, Monday first. */
    val weekdays: List<Long> = List(7) { 0L },
    /** The top artists. */
    val top: List<NumArtist> = emptyList(),
) {
    val empty: Boolean get() = minutes <= 0 && top.isEmpty()
}

@Serializable
data class NumArtist(val name: String, val minutes: Long = 0, val cover: String? = null)

@Serializable
data class SongRef(
    val videoId: String,
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationMs: Long? = null,
    val cover: String? = null,
    val url: String? = null,
) {
    fun toRemote(): RemoteTrack = RemoteTrack(
        videoId = videoId,
        url = url?.takeIf { it.startsWith("https://") } ?: YouTubeLinks.watchUrl(videoId, music = true),
        title = title,
        artist = artist,
        album = album?.takeIf { it.isNotBlank() },
        durationMs = durationMs?.takeIf { it > 0 },
        thumbnailUrl = cover ?: thumbnailOf(videoId),
    )

    companion object {
        /** The song behind a queue/library track; null when it has no YouTube id (nothing to play elsewhere). */
        fun of(track: Track): SongRef? {
            val videoId = track.videoId?.takeIf { it.isNotBlank() } ?: return null
            val online = OnlineItem.of(track)
            // A downloaded song's cover is a file on this phone: the other phones get the video's picture.
            val cover = online?.thumbnailUrl?.takeIf { it.startsWith("https://") }
                ?: track.coverPath?.takeIf { it.startsWith("https://") }
                ?: thumbnailOf(videoId)
            return SongRef(
                videoId = videoId,
                // The relay refuses a song without a title (and the others would see nothing).
                title = track.title.ifBlank { videoId },
                artist = track.artist,
                album = track.album?.takeIf { it.isNotBlank() },
                durationMs = track.durationMs.takeIf { it > 0 },
                cover = cover,
                url = (online?.url ?: track.sourceUrl)?.takeIf { it.startsWith("https://") },
            )
        }

        fun of(remote: RemoteTrack): SongRef = SongRef(
            videoId = remote.videoId,
            title = remote.title,
            artist = remote.artist,
            album = remote.album,
            durationMs = remote.durationMs,
            cover = remote.thumbnailUrl?.takeIf { it.startsWith("https://") } ?: thumbnailOf(remote.videoId),
            url = remote.url.takeIf { it.startsWith("https://") },
        )

        fun thumbnailOf(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }
}

@Serializable
data class FriendRequest(val requestId: Long, val at: Long, val user: Profile)

@Serializable
data class FriendsPage(
    val friends: List<Profile> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)

@Serializable
data class UsersPage(val users: List<Profile> = emptyList(), val next: Int? = null)

/** A song a friend sent while it was on its way (the relay forgets it once this phone confirms). */
@Serializable
data class Delivery(
    val id: Long,
    /** "share" (a song: [track]), "text" ([text]) or "reaction" ([emoji] on the message [ref]; "" takes it back). */
    val kind: String,
    val from: String,
    val at: Long,
    val track: SongRef? = null,
    val text: String? = null,
    val ref: Long? = null,
    val emoji: String? = null,
)

@Serializable
data class Mailbox(val deliveries: List<Delivery> = emptyList(), val from: List<Profile> = emptyList(), val requests: Int = 0)

/** What a jam is playing, the same for everyone in it. */
@Serializable
data class JamState(
    val queue: List<SongRef> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** Server clock when this was set. */
    val updatedAt: Long = 0,
    val rev: Long = 0,
    val by: String? = null,
) {
    val current: SongRef? get() = queue.getOrNull(index)

    /** Where the song is at [serverNow]: still where it was set when paused; moved on with the clock when playing. */
    fun positionAt(serverNow: Long): Long {
        val base = if (playing) positionMs + (serverNow - updatedAt).coerceAtLeast(0) else positionMs
        val limit = current?.durationMs?.takeIf { it > 0 }
        return if (limit != null) base.coerceAtMost(limit) else base
    }
}

@Serializable
data class Jam(
    val id: String,
    val hostId: String,
    val members: List<Profile> = emptyList(),
    val invited: List<Profile> = emptyList(),
    val state: JamState = JamState(),
    val serverTime: Long = 0,
)

@Serializable
data class JamInvite(val jam: Jam, val from: Profile)

@Serializable
data class CurrentJam(val jam: Jam? = null, val invites: List<JamInvite> = emptyList(), val serverTime: Long = 0)

@Serializable
internal data class AccountCreated(val token: String, val recoveryCode: String, val me: Profile)

@Serializable
internal data class Recovered(val token: String, val me: Profile)
