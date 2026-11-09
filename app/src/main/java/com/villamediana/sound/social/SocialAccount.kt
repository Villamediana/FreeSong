package com.villamediana.sound.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Kept in files/datastore/ (the Android backup carries it to a new phone with the library). */
private val Context.socialStore: DataStore<Preferences> by preferencesDataStore(name = "social")

/** The profile's lifecycle on this phone. */
enum class AccountStatus {
    /** Not known yet (reading the saved one). */
    LOADING,
    /** No profile and none being made: the first launch offline, or after a failure. */
    NONE,
    READY,
    /** The person deleted the profile: none is made again until they ask. */
    OPTED_OUT,
}

/**
 * The FreeSong profile of this phone: made by itself on the first launch (random @username and
 * name), then editable — name, @username, photo. Its token and recovery code are kept in
 * DataStore; a new phone gets the profile back from the Android backup or from the code.
 */
class SocialAccount(
    private val context: Context,
    private val scope: CoroutineScope,
    apiFactory: (token: () -> String?) -> RelayApi,
) {
    @Volatile private var token: String? = null
    val api: RelayApi = apiFactory { token }

    private val _status = MutableStateFlow(AccountStatus.LOADING)
    val status: StateFlow<AccountStatus> = _status

    private val _me = MutableStateFlow<Profile?>(null)
    /** The profile as last seen (shown at once from disk, refreshed from the relay). */
    val me: StateFlow<Profile?> = _me

    private val _recoveryCode = MutableStateFlow<String?>(null)
    val recoveryCode: StateFlow<String?> = _recoveryCode

    private val creating = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            val p = runCatching { context.socialStore.data.first() }.getOrNull()
            token = p?.get(TOKEN)
            _recoveryCode.value = p?.get(RECOVERY)
            _me.value = p?.get(ME)?.let { runCatching { RelayJson.decodeFromString(Profile.serializer(), it) }.getOrNull() }
            _status.value = when {
                token != null -> AccountStatus.READY
                p?.get(OPTED_OUT) == true -> AccountStatus.OPTED_OUT
                else -> AccountStatus.NONE
            }
        }
    }

    val ready: Boolean get() = token != null

    /** For the live line (it sends the same token in its handshake). */
    internal fun currentToken(): String? = token

    /** Waits for the saved profile to be read. */
    suspend fun loaded(): AccountStatus {
        while (_status.value == AccountStatus.LOADING) kotlinx.coroutines.delay(20)
        return _status.value
    }

    /**
     * The profile, made now if there is none (the app calls it by itself on launch; [force] also
     * after a deletion, when the person asks for a new one). Null offline or on failure.
     */
    suspend fun ensure(force: Boolean = false): Profile? = creating.withLock {
        val status = loaded()
        if (token != null) return@withLock refresh()
        if (status == AccountStatus.OPTED_OUT && !force) return@withLock null
        val created = try {
            withContext(Dispatchers.IO) { api.createAccount() }
        } catch (e: IOException) {
            return@withLock null
        }
        save(created.token, created.recoveryCode, created.me)
        created.me
    }

    /** The profile again from the relay (photo, name and @username changed on another phone…). */
    suspend fun refresh(): Profile? {
        if (token == null) return null
        return try {
            val p = withContext(Dispatchers.IO) { api.me() }
            keep(p)
            p
        } catch (e: RelayException) {
            // The profile is gone on the relay (deleted on another phone): this phone forgets it.
            // Any other refusal (busy, an odd answer) keeps the profile as last seen.
            if (e.status == 401) {
                forget(optOut = false)
                null
            } else {
                _me.value
            }
        } catch (e: IOException) {
            _me.value
        }
    }

    /** A profile from another phone, by its recovery code. Its relay error code on failure. */
    suspend fun recover(code: String): Result<Profile> = runCatching {
        val r = withContext(Dispatchers.IO) { api.recover(code) }
        // The profile this phone made by itself, still without anyone, would stay in the list as a
        // stranger: it goes (one with friends or requests is kept on the relay).
        if (token != null && r.me.id != _me.value?.id) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val page = api.friends()
                    if (page.friends.isEmpty() && page.incoming.isEmpty() && page.outgoing.isEmpty()) api.deleteAccount()
                }
            }
        }
        save(r.token, formatCode(code), r.me)
        r.me
    }

    suspend fun rename(name: String?, username: String?): Result<Profile> = runCatching {
        val p = withContext(Dispatchers.IO) { api.rename(name = name, username = username) }
        keep(p)
        p
    }

    /** A photo from the gallery: square, 512 px, JPEG. */
    /** The picked photo, upright and about [EDIT_PX] on its shorter side, to frame it. Null when it isn't an image. */
    suspend fun loadPhoto(uri: Uri): Bitmap? = withContext(Dispatchers.IO) { decode(uri, EDIT_PX) }

    /** The square [cut] of [photo] (in its pixels), at 512 px, becomes the profile photo. */
    suspend fun setPhoto(photo: Bitmap, cut: android.graphics.Rect): Result<Profile> = runCatching {
        val jpeg = withContext(Dispatchers.Default) { cutJpeg(photo, cut, AVATAR_PX, quality = 85) }
        val p = withContext(Dispatchers.IO) { api.setAvatar(jpeg) }
        keep(p)
        p
    }

    suspend fun removePhoto(): Result<Profile> = runCatching {
        val p = withContext(Dispatchers.IO) { api.deleteAvatar() }
        keep(p)
        p
    }

    suspend fun newRecoveryCode(): Result<String> = runCatching {
        val code = withContext(Dispatchers.IO) { api.newRecoveryCode() }
        context.socialStore.edit { it[RECOVERY] = code }
        _recoveryCode.value = code
        code
    }

    /** The vault's data key on this phone, when it is [userId]'s (it opens the vault without the code). */
    suspend fun vaultKey(userId: String): ByteArray? {
        val saved = context.socialStore.data.first()[VAULT_KEY] ?: return null
        val (owner, key) = saved.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return if (owner == userId) runCatching { VaultCrypto.unb64(key) }.getOrNull() else null
    }

    suspend fun setVaultKey(userId: String, key: ByteArray?) {
        context.socialStore.edit { if (key == null) it.remove(VAULT_KEY) else it[VAULT_KEY] = userId + ":" + VaultCrypto.b64(key) }
    }

    /** Deletes the profile on the relay (and the songs shared with friends on this phone, by [onDeleted]). */
    suspend fun delete(onDeleted: suspend () -> Unit): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.deleteAccount() }
        forget(optOut = true)
        onDeleted()
    }

    /**
     * Hours listened, the five most heard and the profile's numbers, sent at most every
     * [STATS_EVERY_MS] (or when they change).
     */
    suspend fun syncStats(listenedMs: Long, top: List<SongRef>, numbers: Numbers? = null) {
        if (token == null) return
        val p = context.socialStore.data.first()
        val signature = "$listenedMs|" + top.joinToString(",") { it.videoId } +
            "|" + (numbers?.let { RelayJson.encodeToString(Numbers.serializer(), it).hashCode() } ?: 0)
        val fresh = p[STATS_SIG] == signature && System.currentTimeMillis() - (p[STATS_AT] ?: 0L) < STATS_EVERY_MS
        if (fresh) return
        runCatching { withContext(Dispatchers.IO) { api.setStats(listenedMs, top, numbers) } }.onSuccess { me ->
            keep(me)
            context.socialStore.edit {
                it[STATS_SIG] = signature
                it[STATS_AT] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun save(newToken: String, code: String, me: Profile) {
        context.socialStore.edit {
            it[TOKEN] = newToken
            it[RECOVERY] = code
            it[ME] = RelayJson.encodeToString(Profile.serializer(), me)
            it.remove(OPTED_OUT)
        }
        token = newToken
        _recoveryCode.value = code
        _me.value = me
        _status.value = AccountStatus.READY
    }

    private suspend fun keep(me: Profile) {
        _me.value = me
        context.socialStore.edit { it[ME] = RelayJson.encodeToString(Profile.serializer(), me) }
    }

    private suspend fun forget(optOut: Boolean) {
        context.socialStore.edit {
            it.clear()
            if (optOut) it[OPTED_OUT] = true
        }
        token = null
        _me.value = null
        _recoveryCode.value = null
        _status.value = if (optOut) AccountStatus.OPTED_OUT else AccountStatus.NONE
    }

    /** The picked photo upright (EXIF, Android 9+), decoded at about [shortSide] px on its shorter side. */
    private fun decode(uri: Uri, shortSide: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val shortest = minOf(info.size.width, info.size.height)
                    if (shortest > shortSide * 2) decoder.setTargetSampleSize(shortest / shortSide)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        } else {
            val resolver = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= shortSide) sample *= 2
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }

    companion object {
        const val AVATAR_PX = 512
        /** The photo while it is framed: enough to zoom in without blur, not a whole camera photo in memory. */
        const val EDIT_PX = 1400

        /** "7k2q m9xd 4tfa h3rb" → "7K2Q-M9XD-4TFA-H3RB" (shown as the relay gives it). */
        fun formatCode(raw: String): String {
            val chars = raw.uppercase().filter { it.isLetterOrDigit() }
            return if (chars.length == 16) chars.chunked(4).joinToString("-") else raw.trim().uppercase()
        }

        /** The middle square of [source], at most [size] px, as JPEG (no trimming: it's a face, not a cover). */
        /** The square [cut] of [source] at [size] px, JPEG (the framing chosen in "Ajustar foto"). */
        internal fun cutJpeg(source: Bitmap, cut: android.graphics.Rect, size: Int, quality: Int): ByteArray {
            val side = minOf(cut.width(), cut.height(), source.width, source.height).coerceAtLeast(1)
            val left = cut.left.coerceIn(0, source.width - side)
            val top = cut.top.coerceIn(0, source.height - side)
            val square = Bitmap.createBitmap(source, left, top, side, side)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            return java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }

        internal fun centerSquareJpeg(source: Bitmap, size: Int, quality: Int): ByteArray {
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }.also {
                if (scaled !== square) scaled.recycle()
                if (square !== source) square.recycle()
                source.recycle()
            }
        }
        const val STATS_EVERY_MS = 6L * 3_600_000
        private val TOKEN = stringPreferencesKey("token")
        private val RECOVERY = stringPreferencesKey("recovery_code")
        private val ME = stringPreferencesKey("me")
        private val OPTED_OUT = booleanPreferencesKey("opted_out")
        private val STATS_SIG = stringPreferencesKey("stats_sig")
        private val STATS_AT = longPreferencesKey("stats_at")
        private val VAULT_KEY = stringPreferencesKey("vault_key")
    }
}
