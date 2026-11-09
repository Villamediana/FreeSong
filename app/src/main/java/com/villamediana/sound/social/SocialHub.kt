package com.villamediana.sound.social

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.villamediana.sound.BuildConfig
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.data.db.SharedSongDao
import com.villamediana.sound.playback.MediaPlayerConnection
import com.villamediana.sound.stats.Recaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** The songs swapped with friends, kept on this phone only (the relay just delivers them). */
class SharedSongs(private val dao: SharedSongDao, private val api: RelayApi) {
    val all: Flow<List<SharedSong>> = dao.observeAll()

    fun thread(friendId: String): Flow<List<SharedSong>> = dao.observeThread(friendId)

    suspend fun send(friendId: String, song: SongRef): Result<Unit> = runCatching {
        val sent = withContext(Dispatchers.IO) { api.share(friendId, song) }
        dao.insert(row(friendId, SharedSong.OUT, sent.id, song, sent.at, seen = true))
    }

    /** A delivery kept here; true when it is new (not a second copy of one already kept). */
    suspend fun keep(d: Delivery): Boolean {
        val song = d.track ?: return false
        return dao.insert(row(d.from, SharedSong.IN, d.id, song, d.at, seen = false)) > 0
    }

    suspend fun markSeen(friendId: String) = dao.markSeen(friendId)

    suspend fun clear() = dao.clear()

    private fun row(friendId: String, direction: String, deliveryId: Long, s: SongRef, at: Long, seen: Boolean) = SharedSong(
        friendId = friendId, direction = direction, deliveryId = deliveryId,
        videoId = s.videoId, title = s.title, artist = s.artist, album = s.album,
        durationMs = s.durationMs, cover = s.cover, url = s.url, at = at, seen = seen,
    )
}

/**
 * FreeSong's social side, for the whole app: the profile (made by itself), friends, songs swapped
 * with them and the jam. The live line to the relay is up while the app is on screen or a jam is on.
 */
class SocialHub(
    private val context: Context,
    private val scope: CoroutineScope,
    http: OkHttpClient,
    dao: SharedSongDao,
    /** Made on first use (a jam), never at app start. */
    private val player: () -> MediaPlayerConnection,
    private val library: LibraryRepository,
    private val related: suspend (String) -> List<SongRef>,
) {
    val baseUrl: String = BuildConfig.RELAY_URL
    val account = SocialAccount(context, scope) { token -> RelayApi(http, baseUrl, token) }
    val api: RelayApi get() = account.api
    val link = RelayLink(http, baseUrl, { account.currentToken() }, scope)
    val songs = SharedSongs(dao, account.api)
    val jam = JamSession(
        scope = scope,
        relay = RelayJamBridge(account.api, link),
        playerOf = player,
        playbackOf = { player().state },
        positionOf = { player().positionMs },
        me = { account.me.value?.id },
        related = related,
        setGate = { player().gate = it },
    )

    private val _friends = MutableStateFlow(readFriends())
    /** Friends and requests as last seen (kept on disk to show at once, offline too). */
    val friends: StateFlow<FriendsPage> = _friends

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    /** The friend page open right now (its songs arrive without a notification). */
    @Volatile var openFriend: String? = null

    fun start() {
        // Unit tests (Robolectric) drive the hub themselves: no real relay from a test run.
        if (android.os.Build.FINGERPRINT == "robolectric") return
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { _foreground.value = true }
                override fun onStop(owner: LifecycleOwner) { _foreground.value = false }
            })
        }
        // Connected while on screen, or while in a jam (it plays on in the background).
        scope.launch {
            combine(_foreground, jam.jam, account.status) { fg, j, status -> status == AccountStatus.READY && (fg || j != null) }
                .distinctUntilChanged()
                .collect { link.want(it) }
        }
        // Every time the app comes to the screen: the profile (made now if needed), friends, what arrived, the stats.
        scope.launch {
            _foreground.collect { fg -> if (fg) quietly { bootstrap() } }
        }
        // One event that goes wrong doesn't stop the ones after it.
        scope.launch { link.events.collect { quietly { handle(it) } } }
    }

    /** Runs [block]; a failure in it is dropped (the next foreground or event tries again). */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SocialHub", "social step failed", e)
        }
    }

    suspend fun bootstrap() {
        val me = try {
            account.ensure()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        me ?: return
        quietly { refreshFriends() }
        quietly { drainMailbox() }
        quietly { jam.refresh() }
        quietly { syncStats() }
    }

    private suspend fun handle(event: RelayEvent) {
        jam.onEvent(event)
        when (event) {
            is RelayEvent.Hello -> {
                refreshFriends()
                if (event.pending > 0) drainMailbox()
            }
            is RelayEvent.FriendRequestIn -> {
                refreshFriends()
                if (!_foreground.value) SocialNotifier.request(context, event.request.user)
            }
            is RelayEvent.FriendAccepted -> {
                refreshFriends()
                if (!_foreground.value) SocialNotifier.accepted(context, event.user)
            }
            is RelayEvent.FriendRemoved -> refreshFriends()
            is RelayEvent.SongIn -> {
                val fresh = songs.keep(event.delivery)
                link.send(buildJsonObject { put("t", "ack"); putJsonArray("ids") { add(JsonPrimitive(event.delivery.id)) } })
                if (fresh && (!_foreground.value || openFriend != event.from.id)) event.delivery.track?.let { SocialNotifier.song(context, event.from, it, _foreground.value) }
            }
            is RelayEvent.JamInvited -> if (!_foreground.value) SocialNotifier.jam(context, event.invite.from)
            // The profile was deleted on another phone, or this one was signed out from another:
            // the account check forgets it here (and the jam stops), a passing glitch keeps it.
            RelayEvent.SignedOut -> {
                account.refresh()
                if (!account.ready) jam.forget()
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ friends

    suspend fun refreshFriends(): FriendsPage? {
        if (!account.ready) return null
        val page = try {
            withContext(Dispatchers.IO) { api.friends() }
        } catch (e: IOException) {
            return null
        }
        _friends.value = page
        withContext(Dispatchers.IO) { writeFriends(page) }
        return page
    }

    suspend fun requestFriend(userId: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) { api.requestFriend(userId) }.also { refreshFriends() }
    }

    suspend fun accept(requestId: Long): Result<String> = runCatching {
        withContext(Dispatchers.IO) { api.accept(requestId) }.also { refreshFriends() }
    }

    suspend fun decline(requestId: Long): Result<String> = runCatching {
        withContext(Dispatchers.IO) { api.decline(requestId) }.also { refreshFriends() }
    }

    suspend fun unfriend(userId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.unfriend(userId) }
        refreshFriends()
        Unit
    }

    /** Songs that arrived while the app was closed: kept here, then the relay forgets them. */
    suspend fun drainMailbox(): List<Pair<Profile?, Delivery>> {
        if (!account.ready) return emptyList()
        val box = try {
            withContext(Dispatchers.IO) { api.mailbox() }
        } catch (e: IOException) {
            return emptyList()
        }
        val senders = box.from.associateBy { it.id }
        val fresh = box.deliveries.filter { songs.keep(it) }
        runCatching { withContext(Dispatchers.IO) { api.ack(box.deliveries.map { it.id }) } }
        return fresh.map { senders[it.from] to it }
    }

    /** Deleting the profile also takes the songs swapped with friends off this phone. */
    suspend fun deleteProfile(): Result<Unit> = account.delete {
        jam.forget()
        songs.clear()
        _friends.value = FriendsPage()
        withContext(Dispatchers.IO) { friendsFile().delete() }
    }

    /** Hours listened and the five most heard (all time, library and online), for friends to see. */
    suspend fun syncStats() {
        if (!account.ready) return
        val recap = withContext(Dispatchers.Default) {
            val events = library.playEventsSince(0).first()
            val tracks = library.tracks.first().associateBy { it.id }
            val online = library.onlinePlaysListSince(0)
            Recaps.build(0, events, tracks, online, span = Long.MIN_VALUE until Long.MAX_VALUE)
        }
        val top = recap.topSongs.mapNotNull { s -> s.track?.let(SongRef::of) ?: s.remote?.let(SongRef::of) }
        account.syncStats(recap.listenedMs, top)
    }

    private fun friendsFile() = File(context.filesDir, "social-friends.json")

    private fun readFriends(): FriendsPage =
        runCatching { RelayJson.decodeFromString(FriendsPage.serializer(), friendsFile().readText()) }.getOrDefault(FriendsPage())

    private fun writeFriends(page: FriendsPage) {
        runCatching { friendsFile().writeText(RelayJson.encodeToString(FriendsPage.serializer(), page)) }
    }
}
