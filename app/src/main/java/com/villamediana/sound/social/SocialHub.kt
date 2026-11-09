@file:OptIn(kotlinx.coroutines.FlowPreview::class)

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
import com.villamediana.sound.playback.PlaybackPulse
import com.villamediana.sound.stats.Recaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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

/**
 * The conversations with friends — songs, texts, voice messages, replies, corrections and
 * reactions — kept on this phone only (the relay just delivers them and forgets). Voice audio lives
 * in [voiceDir] (null in tests that don't need it).
 */
class SharedSongs(
    private val dao: SharedSongDao,
    private val api: RelayApi,
    private val voiceDir: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val all: Flow<List<SharedSong>> = dao.observeAll()

    /** Unseen messages per friend. */
    val unread: Flow<Map<String, Int>> = dao.observeUnread().map { rows -> rows.associate { it.friendId to it.n } }

    fun thread(friendId: String): Flow<List<SharedSong>> = dao.observeThread(friendId)

    suspend fun send(friendId: String, song: SongRef, replyTo: Long? = null): Result<Unit> = runCatching {
        val sent = withContext(Dispatchers.IO) { api.share(friendId, song, replyTo) }
        dao.insert(row(friendId, SharedSong.OUT, sent.id, song, sent.at, seen = true).copy(replyTo = replyTo))
    }

    /** A text to a friend ([replyTo]: the message it answers); kept here once the relay took it. */
    suspend fun sendText(friendId: String, text: String, replyTo: Long? = null): Result<Unit> = runCatching {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "empty" }
        val sent = withContext(Dispatchers.IO) { api.sendText(friendId, clean, replyTo) }
        dao.insert(textRow(friendId, SharedSong.OUT, sent.id, clean, sent.at, seen = true).copy(replyTo = replyTo))
    }

    /** The new text of your own [message] (a text): on both phones once the relay took it, marked "editada". */
    suspend fun edit(message: SharedSong, text: String): Result<Unit> = runCatching {
        val clean = text.trim()
        require(clean.isNotEmpty() && message.isText && message.mine) { "not editable" }
        if (clean == message.text) return@runCatching
        val sent = withContext(Dispatchers.IO) { api.editText(message.friendId, message.deliveryId, clean) }
        dao.editText(message.friendId, SharedSong.OUT, message.deliveryId, clean, sent.at)
    }

    /**
     * A voice message recorded in [audio] (AAC in MP4) to a friend. Once the relay took it, the
     * file moves into the conversation's folder and the message is kept here.
     */
    suspend fun sendVoice(friendId: String, audio: File, ms: Long, wave: String, replyTo: Long? = null): Result<Unit> = runCatching {
        val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
        val sent = withContext(Dispatchers.IO) { api.sendVoice(friendId, bytes, ms, wave, replyTo) }
        val name = withContext(Dispatchers.IO) { keepVoice(SharedSong.OUT, sent.id, bytes).also { audio.delete() } }
        dao.insert(voiceRow(friendId, SharedSong.OUT, sent.id, ms, wave, name, sent.at, seen = true).copy(replyTo = replyTo))
    }

    /** The audio of a voice message, when it is on this phone. */
    fun voiceFile(message: SharedSong): File? = message.voiceFile?.let { name -> voiceDir?.resolve(name)?.takeIf { it.isFile } }

    /**
     * Your reaction to [message] ([emoji] null takes it back): shown here at once, then sent to the
     * friend; if it doesn't go, it is put back as it was.
     */
    suspend fun react(message: SharedSong, emoji: String?): Result<Unit> {
        val at = maxOf(now(), message.myReactionAt + 1)
        dao.setMyReaction(message.friendId, message.deliveryId, emoji, at)
        return runCatching { withContext(Dispatchers.IO) { api.react(message.friendId, message.deliveryId, emoji.orEmpty()) } }
            .onFailure { dao.setMyReaction(message.friendId, message.deliveryId, message.myReaction, at) }
    }

    /**
     * A delivery kept here: a song, a text or a voice message becomes a message, a correction
     * changes the friend's text it points at, a reaction lands on the message it points at. True for
     * a new message (not a second copy, not a correction or reaction): the one to tell about.
     * A voice message's audio is fetched first; if that fails this throws (IOException) and the
     * delivery must not be confirmed, so it comes again.
     */
    suspend fun keep(d: Delivery): Boolean = when (d.kind) {
        "share" -> d.track?.let { song -> dao.insert(row(d.from, SharedSong.IN, d.id, song, d.at, seen = false).copy(replyTo = d.replyTo)) > 0 } ?: false
        "text" -> d.text?.takeIf { it.isNotBlank() }?.let { text ->
            dao.insert(textRow(d.from, SharedSong.IN, d.id, text, d.at, seen = false).copy(replyTo = d.replyTo)) > 0
        } ?: false
        "voice" -> d.voice?.let { v -> keepVoiceIn(d, v) } ?: false
        "edit" -> {
            val ref = d.ref
            val text = d.text?.trim()
            if (ref != null && !text.isNullOrEmpty()) dao.editText(d.from, SharedSong.IN, ref, text, d.at)
            false
        }
        "reaction" -> {
            d.ref?.let { ref -> dao.setTheirReaction(d.from, ref, d.emoji?.takeIf { it.isNotEmpty() }, d.at) }
            false
        }
        else -> false
    }

    private suspend fun keepVoiceIn(d: Delivery, v: VoiceInfo): Boolean {
        if (dao.find(d.from, d.id) != null) return false
        val name = withContext(Dispatchers.IO) {
            try {
                keepVoice(SharedSong.IN, d.id, api.voiceAudio(d.id))
            } catch (e: RelayException) {
                // Gone from the relay (another of this profile's phones took it): the message stays, without its audio.
                if (e.status == 404) null else throw e
            }
        }
        return dao.insert(voiceRow(d.from, SharedSong.IN, d.id, v.ms, v.wave, name, d.at, seen = false).copy(replyTo = d.replyTo)) > 0
    }

    /** Writes a voice message's audio; its file name (null without a folder). */
    private fun keepVoice(direction: String, deliveryId: Long, bytes: ByteArray): String? {
        val dir = voiceDir ?: return null
        dir.mkdirs()
        val name = "$direction-$deliveryId.m4a"
        val tmp = dir.resolve("$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dir.resolve(name))) {
            tmp.delete()
            throw IOException("voice not kept")
        }
        return name
    }

    suspend fun markSeen(friendId: String) = dao.markSeen(friendId)

    suspend fun clear() {
        dao.clear()
        withContext(Dispatchers.IO) { voiceDir?.deleteRecursively() }
    }

    private fun textRow(friendId: String, direction: String, deliveryId: Long, text: String, at: Long, seen: Boolean) = SharedSong(
        friendId = friendId, direction = direction, deliveryId = deliveryId,
        videoId = "", title = "", artist = "", at = at, seen = seen, kind = SharedSong.TEXT, text = text,
    )

    private fun voiceRow(friendId: String, direction: String, deliveryId: Long, ms: Long, wave: String, file: String?, at: Long, seen: Boolean) = SharedSong(
        friendId = friendId, direction = direction, deliveryId = deliveryId,
        videoId = "", title = "", artist = "", at = at, seen = seen, kind = SharedSong.VOICE,
        voiceMs = ms, voiceWave = wave, voiceFile = file,
    )

    private fun row(friendId: String, direction: String, deliveryId: Long, s: SongRef, at: Long, seen: Boolean) = SharedSong(
        friendId = friendId, direction = direction, deliveryId = deliveryId,
        videoId = s.videoId, title = s.title, artist = s.artist, album = s.album,
        durationMs = s.durationMs, cover = s.cover, url = s.url, at = at, seen = seen,
    )
}

/** A heart or a playlist changed: the vault syncs this long after the last change. */
private const val VAULT_AFTER_MS = 15_000L

/** "Escrevendo…" lasts this long after the friend's last sign of writing. */
private const val TYPING_MS = 6_000L

/** Deliveries that are a message in the conversation (the friend stopped writing). */
private val MESSAGE_KINDS = setOf("text", "share", "voice")

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
    /** The genres picked on the Home (chosen?, ids), and setting them: they follow the profile too. */
    genres: suspend () -> Pair<Boolean, Set<String>> = { false to emptySet() },
    setGenres: suspend (Set<String>) -> Unit = {},
    /** Listening time already pruned from the history: the profile's hours still count it. */
    private val listenedBefore: suspend () -> Long = { 0L },
) {
    val baseUrl: String = BuildConfig.RELAY_URL
    val account = SocialAccount(context, scope) { token -> RelayApi(http, baseUrl, token) }
    val api: RelayApi get() = account.api
    val link = RelayLink(http, baseUrl, { account.currentToken() }, scope)
    val songs = SharedSongs(dao, account.api, File(context.filesDir, "voice"))
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

    /**
     * The profile's vault: hearts, listening (the taste is learned from it), playlists, songs
     * swapped and genres follow the profile to any phone with its code, sealed so the relay can't
     * read them; several phones of one profile stay in step.
     */
    val vault = VaultSync(
        dir = context.filesDir,
        api = account.api,
        local = PhoneVault(library, dao, genres, setGenres),
        profile = { account.me.value?.id?.let { it to account.recoveryCode.value } },
        loadKey = { account.vaultKey(it) },
        saveKey = { id, key -> account.setVaultKey(id, key) },
    )

    private val _friends = MutableStateFlow(readFriends())
    /** Friends and requests as last seen (kept on disk to show at once, offline too). */
    val friends: StateFlow<FriendsPage> = _friends

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    /** The conversation open right now (its messages arrive without a notification). */
    @Volatile var openChat: String? = null

    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Friends writing to you right now (a few seconds after their last word, it's over). */
    val typing: StateFlow<Set<String>> = _typing.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _profiles = MutableSharedFlow<Profile>(extraBufferCapacity = 16)
    /** A profile that just changed (name, photo): pages showing it update without leaving. */
    val profileChanges: SharedFlow<Profile> = _profiles

    /** "Escrevendo…" to [friendId] ([on] false: stopped). Only while the live line is up; nothing is kept. */
    fun sendTyping(friendId: String, on: Boolean) {
        link.send(buildJsonObject { put("t", "typing"); put("to", friendId); put("on", on) })
    }

    private fun typingFrom(friendId: String, on: Boolean) {
        if (!on) {
            _typing.value = _typing.value - friendId
            return
        }
        val until = System.currentTimeMillis() + TYPING_MS
        _typing.value = _typing.value + (friendId to until)
        scope.launch {
            delay(TYPING_MS)
            val now = System.currentTimeMillis()
            _typing.value = _typing.value.filterValues { it > now }
        }
    }

    /** Someone's name or photo changed: the friends list, the jam and your own card follow. */
    private suspend fun profileChanged(user: Profile) {
        if (user.id == account.me.value?.id) {
            account.refresh()
        } else {
            val page = _friends.value
            if (page.friends.any { it.id == user.id }) {
                val next = page.copy(friends = page.friends.map { f -> if (f.id == user.id) user.copy(friendsSince = f.friendsSince, relation = f.relation) else f })
                _friends.value = next
                withContext(Dispatchers.IO) { writeFriends(next) }
            }
            jam.updateProfile(user)
        }
        _profiles.tryEmit(user)
    }

    fun start() {
        // Unit tests (Robolectric) drive the hub themselves: no real relay from a test run.
        if (android.os.Build.FINGERPRINT == "robolectric") return
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { _foreground.value = true }
                override fun onStop(owner: LifecycleOwner) { _foreground.value = false }
            })
        }
        // Connected while on screen, in a jam (it plays on in the background) or while music plays
        // (a friend's message arrives at once, not at the next check).
        scope.launch {
            combine(_foreground, jam.jam, account.status, PlaybackPulse.playing) { fg, j, status, playing ->
                status == AccountStatus.READY && (fg || j != null || playing)
            }
                .distinctUntilChanged()
                .collect { link.want(it) }
        }
        // Every time the app comes to the screen: the profile (made now if needed), friends, what arrived, the stats.
        scope.launch {
            _foreground.collect { fg -> if (fg) quietly { bootstrap() } }
        }
        // One event that goes wrong doesn't stop the ones after it.
        scope.launch { link.events.collect { quietly { handle(it) } } }
        // A heart or a playlist changed here: the vault follows a moment later (the other phones see it).
        scope.launch {
            combine(library.likedVideoIds, library.playlists.map { lists -> lists.map { it.id to it.playlist.updatedAt } }) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1)
                .debounce(VAULT_AFTER_MS)
                .collect { if (account.ready) quietly { vault.sync() } }
        }
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
        // The vault after the mailbox (the songs that just came in go with it) and before the stats.
        quietly { vault.sync() }
        quietly { syncStats() }
    }

    /** A new recovery code: shown here, and the vault's key wrapped by it (the old code opens nothing). */
    suspend fun newRecoveryCode(): Result<String> = account.newRecoveryCode().onSuccess { code ->
        quietly { vault.rekey(code) }
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
            is RelayEvent.DeliveryIn -> {
                // A voice message whose audio didn't come stays on the relay (no ack): it comes again.
                val fresh = try {
                    songs.keep(event.delivery)
                } catch (e: IOException) {
                    return
                }
                link.send(buildJsonObject { put("t", "ack"); putJsonArray("ids") { add(JsonPrimitive(event.delivery.id)) } })
                if (event.delivery.kind in MESSAGE_KINDS) typingFrom(event.from.id, on = false)
                if (fresh && (!_foreground.value || openChat != event.from.id)) SocialNotifier.message(context, event.from, event.delivery, _foreground.value)
            }
            is RelayEvent.Typing -> typingFrom(event.from, event.on)
            is RelayEvent.ProfileChanged -> profileChanged(event.user)
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

    /** What friends sent while the app was closed: kept here, then the relay forgets it. */
    suspend fun drainMailbox(): List<Pair<Profile?, Delivery>> {
        if (!account.ready) return emptyList()
        val box = try {
            withContext(Dispatchers.IO) { api.mailbox() }
        } catch (e: IOException) {
            return emptyList()
        }
        val senders = box.from.associateBy { it.id }
        val fresh = ArrayList<Delivery>()
        val kept = ArrayList<Long>()
        for (d in box.deliveries) {
            // A voice message whose audio didn't come is not confirmed: it is there next time.
            try {
                if (songs.keep(d)) fresh += d
                kept += d.id
            } catch (e: IOException) {
                continue
            }
        }
        runCatching { withContext(Dispatchers.IO) { api.ack(kept) } }
        return fresh.map { senders[it.from] to it }
    }

    /** Deleting the profile also takes the conversations with friends off this phone. */
    suspend fun deleteProfile(): Result<Unit> {
        val id = account.me.value?.id
        return account.delete {
            jam.forget()
            songs.clear()
            id?.let { vault.forget(it) }
            _friends.value = FriendsPage()
            withContext(Dispatchers.IO) { friendsFile().delete() }
        }
    }

    /**
     * For friends to see: hours listened and the five most heard (all time), and the profile's
     * numbers of the month and of the year (totals only, worked out here).
     */
    suspend fun syncStats() {
        if (!account.ready) return
        val (recap, numbers) = withContext(Dispatchers.Default) {
            val events = library.playEventsSince(0).first()
            val tracks = library.tracks.first().associateBy { it.id }
            val online = library.onlinePlaysListSince(0)
            val now = System.currentTimeMillis()
            val all = Recaps.build(0, events, tracks, online, span = Long.MIN_VALUE until Long.MAX_VALUE)
            fun period(p: com.villamediana.sound.stats.StatsPeriod) =
                NumbersOf.period(com.villamediana.sound.stats.ListeningStatsMaker.build(p, events, tracks, online, now))
            all to Numbers(month = period(com.villamediana.sound.stats.StatsPeriod.MONTH), year = period(com.villamediana.sound.stats.StatsPeriod.YEAR))
        }
        val top = recap.topSongs.mapNotNull { s -> s.track?.let(SongRef::of) ?: s.remote?.let(SongRef::of) }
        account.syncStats(recap.listenedMs + listenedBefore(), top, numbers)
    }

    private fun friendsFile() = File(context.filesDir, "social-friends.json")

    private fun readFriends(): FriendsPage =
        runCatching { RelayJson.decodeFromString(FriendsPage.serializer(), friendsFile().readText()) }.getOrDefault(FriendsPage())

    private fun writeFriends(page: FriendsPage) {
        runCatching { friendsFile().writeText(RelayJson.encodeToString(FriendsPage.serializer(), page)) }
    }
}
