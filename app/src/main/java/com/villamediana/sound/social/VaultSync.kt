package com.villamediana.sound.social

import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.ImportedPlaylist
import com.villamediana.sound.data.db.OnlinePlay
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.data.db.SharedSongDao
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What this phone keeps of the person, read out for the vault and written back from it. */
interface VaultLocal {
    /** Everything the vault carries, as this phone has it. [base] (the last sync) says if the genres changed here. */
    suspend fun export(base: VaultData?, now: Long): VaultData

    /** Makes this phone match [merged] (it was [local] a moment ago). */
    suspend fun apply(merged: VaultData, local: VaultData)
}

/**
 * The profile's vault on this phone: on every sync it reads the vault (written by this profile's
 * other phones), puts it together with what this phone has ([VaultMerge]), makes this phone match
 * and writes the result back — sealed here ([VaultCrypto]), so the relay keeps bytes it cannot read.
 * A phone that just recovered the profile opens it with the code; then it keeps the data key. A new
 * code re-wraps the key ([rekey]) and the other phones keep syncing with the key they have.
 */
class VaultSync(
    private val dir: File,
    private val api: RelayApi,
    private val local: VaultLocal,
    /** The profile signed in here: its id, the recovery code shown on this phone. Null without one. */
    private val profile: () -> Pair<String, String?>?,
    private val loadKey: suspend (userId: String) -> ByteArray?,
    private val saveKey: suspend (userId: String, key: ByteArray?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { SYNCED, NO_PROFILE, OFFLINE, LOCKED }

    private val lock = Mutex()

    /** Reads, puts together, applies and writes. [code]: a code to open the vault with (just typed). */
    suspend fun sync(code: String? = null): Outcome = lock.withLock { withContext(Dispatchers.IO) { run(code, rekey = null) } }

    /** A new recovery code: the vault's key is wrapped by it (and the contents synced on the way). */
    suspend fun rekey(newCode: String): Outcome = lock.withLock { withContext(Dispatchers.IO) { run(null, rekey = newCode) } }

    /** The profile left this phone: what the vault knew here goes. */
    suspend fun forget(userId: String) = lock.withLock {
        withContext(Dispatchers.IO) { baseFile(userId).delete() }
        saveKey(userId, null)
    }

    private suspend fun run(typed: String?, rekey: String?): Outcome {
        val (userId, shown) = profile() ?: return Outcome.NO_PROFILE
        repeat(ATTEMPTS) {
            val remote = try {
                api.vault()
            } catch (e: IOException) {
                return Outcome.OFFLINE
            }
            val saved = loadKey(userId)
            val key: ByteArray
            var wrapped: String? = null
            var remoteData = VaultData()
            if (remote == null) {
                // The first phone to sync: it makes the key and wraps it with the code it shows.
                val code = rekey ?: shown ?: typed ?: return Outcome.LOCKED
                key = saved ?: VaultCrypto.newDataKey()
                wrapped = VaultCrypto.wrap(key, VaultCrypto.codeKey(code, userId))
            } else {
                val withSaved = saved?.let { VaultCrypto.openData(remote.data, it) }
                val opened: ByteArray
                if (saved != null && withSaved != null) {
                    key = saved
                    opened = withSaved
                } else {
                    // This phone has no key yet (or an old one): the code opens it.
                    val code = typed ?: shown ?: return Outcome.LOCKED
                    key = VaultCrypto.unwrap(remote.key, VaultCrypto.codeKey(code, userId)) ?: return Outcome.LOCKED
                    opened = VaultCrypto.openData(remote.data, key) ?: return Outcome.LOCKED
                }
                remoteData = runCatching { RelayJson.decodeFromString(VaultData.serializer(), opened.decodeToString()) }.getOrNull()
                    ?: return Outcome.LOCKED
                if (rekey != null) wrapped = VaultCrypto.wrap(key, VaultCrypto.codeKey(rekey, userId))
            }
            val now = clock()
            val base = loadBase(userId)
            val mine = local.export(base, now)
            val merged = VaultMerge.merge(base, mine, remoteData, now)
            local.apply(merged, mine)
            if (remote == null || merged != remoteData || wrapped != null) {
                val sealed = VaultCrypto.sealData(RelayJson.encodeToString(VaultData.serializer(), merged).encodeToByteArray(), key)
                try {
                    api.putVault(remote?.rev ?: 0, sealed, wrapped)
                } catch (e: RelayException) {
                    // Another phone wrote in between: read again and put together again.
                    if (e.status == 409) return@repeat
                    return Outcome.OFFLINE
                } catch (e: IOException) {
                    return Outcome.OFFLINE
                }
            }
            saveKey(userId, key)
            saveBase(userId, merged)
            return Outcome.SYNCED
        }
        return Outcome.OFFLINE
    }

    private fun baseFile(userId: String) = File(dir, "vault-base-${userId.filter(Char::isLetterOrDigit)}.json")

    private fun loadBase(userId: String): VaultData? =
        runCatching { RelayJson.decodeFromString(VaultData.serializer(), baseFile(userId).readText()) }.getOrNull()

    private fun saveBase(userId: String, data: VaultData) {
        runCatching {
            dir.mkdirs()
            val f = baseFile(userId)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(RelayJson.encodeToString(VaultData.serializer(), data))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}

/**
 * This phone's side of the vault: hearts, listening (a year), playlists made here and brought from
 * Spotify, the songs swapped with friends, the genres picked on the Home.
 */
class PhoneVault(
    private val library: LibraryRepository,
    private val shared: SharedSongDao,
    private val genres: suspend () -> Pair<Boolean, Set<String>>,
    private val setGenres: suspend (Set<String>) -> Unit,
) : VaultLocal {
    override suspend fun export(base: VaultData?, now: Long): VaultData {
        val since = now - VaultMerge.PLAYS_KEPT_MS
        val tracks = library.allTracks().associateBy { it.id }
        val plays = library.playEventsListSince(since).mapNotNull { e ->
            val t = tracks[e.trackId] ?: return@mapNotNull null
            val v = t.videoId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VPlay(v, t.title, t.artist, t.genre, e.playedAt, e.listenedMs, t.durationMs, e.completed, e.skipped)
        } + library.onlinePlaysListSince(since).map { p ->
            VPlay(p.videoId, p.title, p.artist, null, p.playedAt, p.listenedMs, p.durationMs, p.completed, p.skipped)
        }
        val (chosen, ids) = genres()
        val sortedIds = ids.sorted()
        // The genres' time: unchanged since the last sync keeps that time; changed here is now.
        val genreAt = base?.genres?.takeIf { it.chosen == chosen && it.ids == sortedIds }?.at ?: if (chosen) now else 0L
        return VaultData(
            likes = library.vaultLikes().map { (s, at) -> VLike(s.toV(), at) }.sortedBy { it.song.videoId },
            plays = plays.distinctBy { it.key }.sortedWith(compareByDescending<VPlay> { it.at }.thenBy { it.videoId }),
            playlists = library.vaultPlaylists().map { p ->
                VPlaylist(
                    key = p.playlist.createdAt.toString(),
                    name = p.playlist.name,
                    description = p.playlist.description,
                    sourceUrl = p.playlist.sourceUrl,
                    pinned = p.playlist.pinned,
                    createdAt = p.playlist.createdAt,
                    updatedAt = p.playlist.updatedAt,
                    songs = p.songs.map { it.toV() },
                )
            }.sortedBy { it.key },
            imported = library.importedList().map { VImported(it.url, it.name, it.owner, it.coverUrl, it.tracksJson, it.trackCount, it.importedAt) }.sortedBy { it.url },
            // Voice messages stay on the phone that has their audio (it doesn't fit the vault).
            shared = shared.all().filterNot { it.isVoice }.map {
                VShared(
                    it.friendId, it.direction, it.deliveryId, VSong(it.videoId, it.title, it.artist, it.album, it.durationMs, it.cover, it.url), it.at, it.seen,
                    kind = it.kind, text = it.text,
                    myReaction = it.myReaction, myReactionAt = it.myReactionAt, theirReaction = it.theirReaction, theirReactionAt = it.theirReactionAt,
                    replyTo = it.replyTo, editedAt = it.editedAt,
                )
            }.sortedWith(compareBy<VShared> { it.at }.thenBy { it.key }),
            genres = VGenres(chosen, sortedIds, genreAt),
        )
    }

    override suspend fun apply(merged: VaultData, local: VaultData) {
        // Hearts.
        val mineLikes = local.likes.associateBy { it.song.videoId }
        val theirLikes = merged.likes.associateBy { it.song.videoId }
        for ((id, like) in theirLikes) if (id !in mineLikes) library.likeFromVault(like.song.toRemote(), like.at)
        for (id in mineLikes.keys) if (id !in theirLikes) library.unlikeFromVault(id)
        // Listening.
        val heard = local.plays.mapTo(HashSet()) { it.key }
        library.addOnlinePlays(
            merged.plays.filter { it.key !in heard }.map { p ->
                OnlinePlay(
                    videoId = p.videoId, title = p.title, artist = p.artist, durationMs = p.durationMs,
                    url = "https://music.youtube.com/watch?v=${p.videoId}", playedAt = p.at, listenedMs = p.listenedMs,
                    completed = p.completed, skipped = p.skipped, context = "vault",
                )
            },
        )
        // Playlists.
        val here = library.vaultPlaylists().associateBy { it.playlist.createdAt.toString() }
        val mineLists = local.playlists.associateBy { it.key }
        for (p in merged.playlists) {
            val have = here[p.key]
            val mine = mineLists[p.key]
            if (have == null || (mine != null && p.updatedAt > mine.updatedAt)) {
                library.putPlaylistFromVault(p.createdAt, p.updatedAt, p.name, p.description, p.sourceUrl, p.pinned, p.songs.map { it.toRemote() }, have?.playlist?.id)
            }
        }
        val kept = merged.playlists.mapTo(HashSet()) { it.key }
        for ((key, p) in here) if (key !in kept && key in mineLists) library.deletePlaylistFromVault(p.playlist.id)
        // Playlists from Spotify.
        val mineImported = local.imported.associateBy { it.url }
        for (p in merged.imported) {
            val mine = mineImported[p.url]
            if (mine == null || p.at > mine.at) {
                library.putImportedFromVault(ImportedPlaylist(p.url, p.name, p.owner, p.cover, p.tracksJson, p.count, p.at))
            }
        }
        val keptImported = merged.imported.mapTo(HashSet()) { it.url }
        for (url in mineImported.keys) if (url !in keptImported) library.removeImported(url)
        // Conversations with friends: messages, what was seen, the reactions.
        val mineShared = local.shared.associateBy { it.key }
        for (s in merged.shared) {
            if (s.kind == SharedSong.VOICE) continue
            val mine = mineShared[s.key]
            if (mine == null) {
                shared.insert(
                    SharedSong(
                        friendId = s.friendId, direction = s.direction, deliveryId = s.deliveryId, videoId = s.song.videoId,
                        title = s.song.title, artist = s.song.artist, album = s.song.album, durationMs = s.song.durationMs,
                        cover = s.song.cover, url = s.song.url, at = s.at, seen = s.seen,
                        kind = s.kind, text = s.text,
                        myReaction = s.myReaction, myReactionAt = s.myReactionAt, theirReaction = s.theirReaction, theirReactionAt = s.theirReactionAt,
                        replyTo = s.replyTo, editedAt = s.editedAt,
                    ),
                )
                continue
            }
            if (s.editedAt > mine.editedAt && s.text != null) shared.editText(s.friendId, s.direction, s.deliveryId, s.text, s.editedAt)
            if (s.seen && !mine.seen) shared.markSeenOne(s.direction, s.deliveryId)
            if (s.myReactionAt > mine.myReactionAt) shared.setMyReaction(s.friendId, s.deliveryId, s.myReaction, s.myReactionAt)
            if (s.theirReactionAt > mine.theirReactionAt) shared.setTheirReaction(s.friendId, s.deliveryId, s.theirReaction, s.theirReactionAt)
        }
        // Genres picked on the Home.
        val g = merged.genres
        if (g != null && g.chosen && g != local.genres) setGenres(g.ids.toSet())
    }

    private fun RemoteTrack.toV() = VSong(videoId, title, artist, album, durationMs, thumbnailUrl, url)
}

/** The song as the player streams it. */
internal fun VSong.toRemote() = RemoteTrack(
    videoId = videoId,
    url = url?.takeIf { it.startsWith("https://") } ?: "https://music.youtube.com/watch?v=$videoId",
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    thumbnailUrl = cover,
)
