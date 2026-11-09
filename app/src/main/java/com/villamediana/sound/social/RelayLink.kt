package com.villamediana.sound.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** What the relay pushes while the app is connected. */
sealed interface RelayEvent {
    data class Hello(val pending: Int) : RelayEvent
    data class FriendRequestIn(val request: FriendRequest) : RelayEvent
    data class FriendAccepted(val user: Profile) : RelayEvent
    data class FriendRemoved(val userId: String) : RelayEvent
    /** A friend sent a song, a text or a reaction. */
    data class DeliveryIn(val delivery: Delivery, val from: Profile) : RelayEvent
    data class JamInvited(val invite: JamInvite) : RelayEvent
    /** The jam changed (someone played, joined, left…); [rejected] when this phone's change lost the race. */
    data class JamChanged(val jam: Jam, val rejected: String?) : RelayEvent
    data class JamEnded(val jamId: String) : RelayEvent
    /** The relay turned this phone away (profile deleted, signed out from another phone): check the account. */
    data object SignedOut : RelayEvent
}

/**
 * The live line to the relay (one WebSocket): events in, jam changes out, and the relay's clock
 * (so every phone in a jam agrees on where the song is). Connected while [want] is true; reconnects
 * by itself with a growing pause.
 */
class RelayLink(
    http: OkHttpClient,
    private val baseUrl: String,
    private val token: () -> String?,
    private val scope: CoroutineScope,
) {
    private val client = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RelayEvent> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    private var loop: Job? = null

    private companion object {
        /** The relay's close codes for a token it no longer takes (4001 profile deleted, 4003 signed out elsewhere). */
        val SIGNED_OUT_CODES = setOf(4001, 4003)
        /** Closed because the same profile opened too many lines (other phones): no hurry to come back. */
        const val TOO_MANY_LINES = 4002
        const val MAX_PAUSE_MS = 30_000L
    }

    /** Relay clock minus this phone's clock, from the best (shortest) ping. */
    @Volatile var clockOffsetMs: Long = 0L
        private set
    private var bestRtt = Long.MAX_VALUE

    fun serverNow(): Long = System.currentTimeMillis() + clockOffsetMs

    @Synchronized
    fun want(on: Boolean) {
        if (on == wanted) return
        wanted = on
        if (on) {
            loop?.cancel()
            loop = scope.launch(Dispatchers.IO) { keepConnected() }
        } else {
            loop?.cancel()
            loop = null
            socket?.close(1000, "bye")
            socket = null
            _connected.value = false
        }
    }

    fun send(message: JsonObject): Boolean = socket?.send(message.toString()) ?: false

    private suspend fun keepConnected() {
        var pause = 1_000L
        while (wanted) {
            val t = token()
            if (t == null) {
                delay(5_000)
                continue
            }
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/ws")
                .header("Authorization", "Bearer $t")
                .build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connected.value = true
                    bestRtt = Long.MAX_VALUE
                    pause = 1_000L
                    syncClock(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

                // The relay closed the line: answer the close (or OkHttp leaves it half open) and see why.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    if (code in SIGNED_OUT_CODES) _events.tryEmit(RelayEvent.SignedOut)
                    if (code == TOO_MANY_LINES) pause = MAX_PAUSE_MS
                    webSocket.close(1000, null)
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connected.value = false
                    if (response?.code == 401) _events.tryEmit(RelayEvent.SignedOut)
                    closed.complete(Unit)
                }
            })
            socket = ws
            try {
                closed.await()
            } finally {
                if (!wanted) ws.close(1000, "bye")
            }
            socket = null
            if (!wanted) break
            delay(pause)
            pause = (pause * 2).coerceAtMost(MAX_PAUSE_MS)
        }
    }

    /** Three pings a moment apart; the quickest answer sets the offset. */
    private fun syncClock(ws: WebSocket) {
        scope.launch(Dispatchers.IO) {
            repeat(3) {
                ws.send(buildJsonObject { put("t", "ping"); put("c", System.currentTimeMillis()) }.toString())
                delay(400)
            }
        }
    }

    private fun handle(ws: WebSocket, text: String) {
        val o = runCatching { RelayJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = o["t"]?.jsonPrimitive?.content ?: return
        runCatching {
            when (type) {
                "pong" -> {
                    val sent = o["c"]!!.jsonPrimitive.long
                    val server = o["s"]!!.jsonPrimitive.long
                    val now = System.currentTimeMillis()
                    val rtt = now - sent
                    if (rtt in 0 until bestRtt) {
                        bestRtt = rtt
                        clockOffsetMs = server - (sent + rtt / 2)
                    }
                }
                "hello" -> _events.tryEmit(RelayEvent.Hello(o["pending"]?.jsonPrimitive?.int ?: 0))
                "friend.request" -> _events.tryEmit(RelayEvent.FriendRequestIn(RelayJson.decodeFromJsonElement(FriendRequest.serializer(), o["request"]!!)))
                "friend.accepted" -> _events.tryEmit(RelayEvent.FriendAccepted(RelayJson.decodeFromJsonElement(Profile.serializer(), o["user"]!!)))
                "friend.removed" -> _events.tryEmit(RelayEvent.FriendRemoved(o["userId"]!!.jsonPrimitive.content))
                "delivery" -> _events.tryEmit(
                    RelayEvent.DeliveryIn(
                        RelayJson.decodeFromJsonElement(Delivery.serializer(), o["delivery"]!!),
                        RelayJson.decodeFromJsonElement(Profile.serializer(), o["from"]!!),
                    ),
                )
                "jam.invite" -> _events.tryEmit(RelayEvent.JamInvited(RelayJson.decodeFromJsonElement(JamInvite.serializer(), o)))
                "jam" -> _events.tryEmit(
                    RelayEvent.JamChanged(
                        RelayJson.decodeFromJsonElement(Jam.serializer(), o["jam"]!!),
                        o["rejected"]?.jsonPrimitive?.content,
                    ),
                )
                "jam.ended" -> _events.tryEmit(RelayEvent.JamEnded(o["jamId"]!!.jsonPrimitive.content))
            }
        }
    }
}
