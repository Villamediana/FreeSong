package com.villamediana.sound.social

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The relay said no: [status] and its error code ("username_taken", "not_friends"…). */
class RelayException(val status: Int, val code: String) : IOException("relay $status $code")

/**
 * The relay's HTTP API (relay/src/server.js). Blocking calls: run them on Dispatchers.IO.
 * [token] is read on every call (it appears once the account exists).
 */
class RelayApi(
    private val http: OkHttpClient,
    val baseUrl: String,
    private val token: () -> String?,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Absolute address of something the relay serves ("/avatars/…" → "https://…/avatars/…"). */
    fun absolute(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("https://") -> path
        else -> baseUrl.trimEnd('/') + path
    }

    private fun <T> call(method: String, path: String, body: RequestBody?, out: KSerializer<T>, auth: Boolean = true): T {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(jsonType) else null)
            .apply {
                if (auth) {
                    val t = token() ?: throw RelayException(401, "no_account")
                    header("Authorization", "Bearer $t")
                }
            }
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) {
                val code = runCatching { RelayJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw RelayException(r.code, code ?: "http_${r.code}")
            }
            // An answer out of shape is a failed call like any other (never an error that escapes).
            return try {
                RelayJson.decodeFromString(out, text)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw RelayException(r.code, "bad_response")
            } catch (e: IllegalArgumentException) {
                throw RelayException(r.code, "bad_response")
            }
        }
    }

    private fun json(o: JsonObject) = o.toString().toRequestBody(jsonType)

    // ---------------------------------------------------------------- account

    internal fun createAccount(): AccountCreated = call("POST", "/v1/accounts", json(JsonObject(emptyMap())), AccountCreated.serializer(), auth = false)

    internal fun recover(code: String): Recovered =
        call("POST", "/v1/accounts/recover", json(buildJsonObject { put("code", code) }), Recovered.serializer(), auth = false)

    fun me(): Profile = call("GET", "/v1/me", null, MeBox.serializer()).me

    fun rename(name: String? = null, username: String? = null): Profile = call(
        "PATCH", "/v1/me",
        json(buildJsonObject {
            if (name != null) put("name", name)
            if (username != null) put("username", username)
        }),
        MeBox.serializer(),
    ).me

    fun setAvatar(jpeg: ByteArray): Profile = call("PUT", "/v1/me/avatar", jpeg.toRequestBody("image/jpeg".toMediaType()), MeBox.serializer()).me

    fun deleteAvatar(): Profile = call("DELETE", "/v1/me/avatar", null, MeBox.serializer()).me

    fun setStats(listenedMs: Long, top: List<SongRef>, numbers: Numbers? = null): Profile = call(
        "PUT", "/v1/me/stats",
        json(buildJsonObject {
            put("listenedMs", listenedMs)
            put("top", RelayJson.encodeToJsonElement(ListSerializer(SongRef.serializer()), top))
            numbers?.let { put("numbers", RelayJson.encodeToJsonElement(Numbers.serializer(), it)) }
        }),
        MeBox.serializer(),
    ).me

    fun newRecoveryCode(): String = call("POST", "/v1/me/recovery", null, CodeBox.serializer()).recoveryCode

    fun deleteAccount() {
        call("DELETE", "/v1/me", null, OkBox.serializer())
    }

    /** The profile's vault (sealed on its phones: the relay can't read it); null when there is none yet. */
    internal fun vault(): VaultBox? = try {
        call("GET", "/v1/me/vault", null, VaultBox.serializer())
    } catch (e: RelayException) {
        if (e.status == 404) null else throw e
    }

    /**
     * Writes the vault over version [baseRev] (0: the first); [key] (the data key wrapped by the
     * code) on the first write and after a new code. The new version; 409 "stale" when another phone
     * wrote first.
     */
    internal fun putVault(baseRev: Long, data: String, key: String?): Long = call(
        "PUT",
        "/v1/me/vault",
        json(buildJsonObject {
            put("baseRev", baseRev)
            put("data", data)
            if (key != null) put("key", key)
        }),
        RevBox.serializer(),
    ).rev

    // ---------------------------------------------------------------- people and friends

    fun users(query: String, offset: Int, limit: Int = 30): UsersPage {
        val url = (baseUrl.trimEnd('/') + "/v1/users").toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return call("GET", url.encodedPath + "?" + url.encodedQuery, null, UsersPage.serializer())
    }

    fun user(id: String): Profile = call("GET", "/v1/users/$id", null, UserBox.serializer()).user

    fun friends(): FriendsPage = call("GET", "/v1/friends", null, FriendsPage.serializer())

    /** "pending" or "friends" (the other had already asked). */
    fun requestFriend(userId: String): String =
        call("POST", "/v1/friends/requests", json(buildJsonObject { put("userId", userId) }), StatusBox.serializer()).status

    fun accept(requestId: Long): String = call("POST", "/v1/friends/requests/$requestId/accept", null, StatusBox.serializer()).status

    fun decline(requestId: Long): String = call("POST", "/v1/friends/requests/$requestId/decline", null, StatusBox.serializer()).status

    fun unfriend(userId: String) {
        call("DELETE", "/v1/friends/$userId", null, StatusBox.serializer())
    }

    // ---------------------------------------------------------------- shared songs (delivered, not kept)

    /** Sends [song] to a friend; the relay answers with its delivery id and time. */
    fun share(to: String, song: SongRef): SentShare = call(
        "POST", "/v1/shares",
        json(buildJsonObject {
            put("to", to)
            put("track", RelayJson.encodeToJsonElement(SongRef.serializer(), song))
        }),
        ShareBox.serializer(),
    ).share

    /** A text to a friend (relay 1.5.0); the relay answers with its delivery id and time. */
    fun sendText(to: String, text: String): SentShare = call(
        "POST", "/v1/messages",
        json(buildJsonObject {
            put("to", to)
            put("text", text)
        }),
        MessageBox.serializer(),
    ).message

    /** A reaction ([emoji], "" to take it back) to the message [ref] of the conversation with [to]. */
    fun react(to: String, ref: Long, emoji: String) {
        call(
            "POST", "/v1/reactions",
            json(buildJsonObject {
                put("to", to)
                put("ref", ref)
                put("emoji", emoji)
            }),
            ReactionBox.serializer(),
        )
    }

    fun mailbox(): Mailbox = call("GET", "/v1/mailbox", null, Mailbox.serializer())

    fun ack(ids: List<Long>) {
        if (ids.isEmpty()) return
        call("POST", "/v1/mailbox/ack", json(buildJsonObject { putJsonArray("ids") { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }), AckBox.serializer())
    }

    // ---------------------------------------------------------------- jam

    fun currentJam(): CurrentJam = call("GET", "/v1/jams/current", null, CurrentJam.serializer())

    fun startJam(invite: List<String>, state: JamState?): Jam = call(
        "POST", "/v1/jams",
        json(buildJsonObject {
            putJsonArray("invite") { invite.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            if (state != null) put("state", RelayJson.encodeToJsonElement(JamState.serializer(), state))
        }),
        JamBox.serializer(),
    ).jam

    fun inviteToJam(jamId: String, userIds: List<String>): Jam = call(
        "POST", "/v1/jams/$jamId/invite",
        json(buildJsonObject { putJsonArray("userIds") { userIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }),
        JamBox.serializer(),
    ).jam

    fun joinJam(jamId: String): Jam = call("POST", "/v1/jams/$jamId/join", null, JamBox.serializer()).jam

    fun declineJam(jamId: String) {
        call("POST", "/v1/jams/$jamId/decline", null, OkBox.serializer())
    }

    fun leaveJam(jamId: String) {
        call("POST", "/v1/jams/$jamId/leave", null, OkBox.serializer())
    }

    fun endJam(jamId: String) {
        call("DELETE", "/v1/jams/$jamId", null, OkBox.serializer())
    }

    /** The jam's new state over HTTP (when the live line is down); the relay answers with the jam as it is now. */
    fun postJamState(jamId: String, baseRev: Long, state: JamState, withQueue: Boolean): Jam = call(
        "POST", "/v1/jams/$jamId/state",
        json(buildJsonObject {
            put("baseRev", baseRev)
            if (withQueue) put("queue", RelayJson.encodeToJsonElement(ListSerializer(SongRef.serializer()), state.queue))
            put("index", state.index)
            put("positionMs", state.positionMs)
            put("playing", state.playing)
        }),
        StateBox.serializer(),
    ).jam

    @Serializable internal data class StateBox(val accepted: Boolean = false, val jam: Jam)
    @Serializable internal data class MeBox(val me: Profile)
    @Serializable internal data class UserBox(val user: Profile)
    @Serializable internal data class CodeBox(val recoveryCode: String)
    @Serializable internal data class OkBox(val ok: Boolean = true)
    @Serializable internal data class VaultBox(val rev: Long, val key: String, val data: String, val updatedAt: Long = 0)
    @Serializable internal data class RevBox(val rev: Long)
    @Serializable internal data class StatusBox(val status: String)
    @Serializable internal data class AckBox(val removed: Int = 0)
    @Serializable internal data class ShareBox(val share: SentShare)
    @Serializable internal data class MessageBox(val message: SentShare)
    @Serializable internal data class ReactionBox(val reaction: SentShare)
    @Serializable internal data class JamBox(val jam: Jam)
}

@Serializable
data class SentShare(val id: Long, val at: Long)
