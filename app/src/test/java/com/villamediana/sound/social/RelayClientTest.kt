package com.villamediana.sound.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The app's client (RelayApi + RelayLink) against the real relay (relay/src/server.js) started
 * here with Node on a free port and a throwaway data folder. Skipped where Node isn't installed.
 */
/** The real relay (relay/src/server.js), started once per test run with Node on a free port. */
object LocalRelay {
    private var process: Process? = null
    private var dataDir: File? = null
    private var url: String? = null
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    /** The relay's address; the test is skipped where Node or relay/node_modules is missing. */
    @Synchronized
    fun base(): String {
        url?.let { return it }
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "relay/src/server.js").isFile }
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        assumeTrue("Node e relay/ precisam existir", root != null && node != null && File(root, "relay/node_modules/ws").isDirectory)
        val port = ServerSocket(0).use { it.localPort }
        val dir = Files.createTempDirectory("relay-test").toFile()
        dataDir = dir
        process = ProcessBuilder(node, "--no-warnings", "src/server.js")
            .directory(File(root, "relay"))
            .apply { environment().putAll(mapOf("PORT" to "$port", "HOST" to "127.0.0.1", "DATA_DIR" to dir.path)) }
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "log"))
            .start()
        Runtime.getRuntime().addShutdownHook(Thread {
            process?.destroy()
            dir.deleteRecursively()
        })
        val base = "http://127.0.0.1:$port"
        val until = System.currentTimeMillis() + 10_000
        while (true) {
            val up = runCatching { http.newCall(okhttp3.Request.Builder().url("$base/v1/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
            if (up) break
            check(System.currentTimeMillis() < until) { "relay não subiu: " + File(dir, "log").readText() }
            Thread.sleep(100)
        }
        url = base
        return base
    }
}

class RelayClientTest {
    companion object {
        lateinit var base: String
        val http: OkHttpClient get() = LocalRelay.http

        @JvmStatic
        @BeforeClass
        fun startRelay() {
            base = LocalRelay.base()
        }
    }

    private class Phone {
        var token: String? = null
        val api = RelayApi(http, base) { token }
        lateinit var me: Profile
        var code = ""

        fun register(): Phone {
            val created = api.createAccount()
            token = created.token
            me = created.me
            code = created.recoveryCode
            return this
        }
    }

    private fun friends(a: Phone, b: Phone) {
        assertEquals("pending", a.api.requestFriend(b.me.id))
        val req = b.api.friends().incoming.single { it.user.id == a.me.id }
        assertEquals("friends", b.api.accept(req.requestId))
    }

    private fun song(n: Int) = SongRef("vid${n.toString().padStart(8, '0')}", "Música $n", "Artista", durationMs = 200_000, cover = "https://i.ytimg.com/vi/x/hq.jpg")

    @Test
    fun accountRenamePhotoAndRecovery() {
        val a = Phone().register()
        assertTrue(a.me.username.matches(Regex("[a-z]+_\\d{3,4}")))
        val renamed = a.api.rename(name = "Ana Teste", username = "ana.teste${System.nanoTime() % 10000}")
        assertEquals("Ana Teste", renamed.name)
        // A real JPEG (the relay checks the image's structure since 1.1.0; a fake one is turned down).
        val jpeg = java.io.ByteArrayOutputStream().also { out ->
            val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, (x * 4 shl 16) or (y * 4 shl 8) or 0x80)
            javax.imageio.ImageIO.write(img, "jpg", out)
        }.toByteArray()
        val fake = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(400) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals("not_jpeg", (runCatching { a.api.setAvatar(fake) }.exceptionOrNull() as RelayException).code)
        val withPhoto = a.api.setAvatar(jpeg)
        assertNotNull(withPhoto.avatar)
        assertEquals("$base${withPhoto.avatar}", a.api.absolute(withPhoto.avatar))
        val b = Phone().register()
        val taken = runCatching { b.api.rename(username = renamed.username) }.exceptionOrNull() as RelayException
        assertEquals(409, taken.status)
        assertEquals("username_taken", taken.code)
        // Another phone, the code: the same profile.
        val recovered = Phone().api.recover(a.code)
        assertEquals(a.me.id, recovered.me.id)
    }

    @Test
    fun friendsSeeHoursAndTopStrangersDont() {
        val a = Phone().register()
        val b = Phone().register()
        b.api.setStats(3_600_000, listOf(song(1), song(2)))
        assertNull(a.api.user(b.me.id).listenedMs)
        friends(a, b)
        val seen = a.api.user(b.me.id)
        assertEquals(3_600_000L, seen.listenedMs)
        assertEquals(listOf(song(1).videoId, song(2).videoId), seen.top!!.map { it.videoId })
        assertTrue(a.api.friends().friends.any { it.id == b.me.id && it.isFriend })
        val page = a.api.users(b.me.username, 0)
        assertEquals("friends", page.users.single { it.id == b.me.id }.relation)
    }

    @Test
    fun aSharedSongArrivesLiveOrWaitsInTheMailboxThenLeavesTheRelay() = runBlocking {
        val a = Phone().register()
        val b = Phone().register()
        friends(a, b)
        // B with the app closed: it waits.
        val sent = a.api.share(b.me.id, song(3))
        val box = b.api.mailbox()
        assertEquals(sent.id, box.deliveries.single().id)
        assertEquals(song(3).videoId, box.deliveries.single().track!!.videoId)
        b.api.ack(box.deliveries.map { it.id })
        assertTrue(b.api.mailbox().deliveries.isEmpty())
        // B with the app open: it arrives on the live line.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = RelayLink(http, base, { b.token }, scope)
        val events = CopyOnWriteArrayList<RelayEvent>()
        scope.launch { link.events.collect { events += it } }
        link.want(true)
        withTimeout(5_000) { link.connected.first { it } }
        a.api.share(b.me.id, song(4))
        withTimeout(5_000) { while (events.none { it is RelayEvent.SongIn }) kotlinx.coroutines.delay(20) }
        val inbound = events.filterIsInstance<RelayEvent.SongIn>().single()
        assertEquals(a.me.id, inbound.from.id)
        assertEquals(song(4).videoId, inbound.delivery.track!!.videoId)
        link.want(false)
        scope.cancel()
    }

    @Test
    fun aJamOverTheLiveLineWithTheRelaysClock() = runBlocking {
        val a = Phone().register()
        val b = Phone().register()
        friends(a, b)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val linkA = RelayLink(http, base, { a.token }, scope)
        val linkB = RelayLink(http, base, { b.token }, scope)
        val eventsB = CopyOnWriteArrayList<RelayEvent>()
        val eventsA = CopyOnWriteArrayList<RelayEvent>()
        scope.launch { linkA.events.collect { eventsA += it } }
        scope.launch { linkB.events.collect { eventsB += it } }
        linkA.want(true)
        linkB.want(true)
        withTimeout(5_000) { linkA.connected.first { it }; linkB.connected.first { it } }

        val jam = a.api.startJam(listOf(b.me.id), JamState(listOf(song(1), song(2)), 0, 0, true))
        withTimeout(5_000) { while (eventsB.none { it is RelayEvent.JamInvited }) kotlinx.coroutines.delay(20) }
        val joined = b.api.joinJam(jam.id)
        assertEquals(2, joined.members.size)
        // B pauses for everyone, over the live line.
        val bridge = RelayJamBridge(b.api, linkB)
        assertTrue(bridge.sendState(jam.id, joined.state.rev, joined.state.copy(playing = false, positionMs = 12_000), withQueue = false))
        withTimeout(5_000) {
            while (eventsA.filterIsInstance<RelayEvent.JamChanged>().none { it.jam.state.rev == joined.state.rev + 1 }) kotlinx.coroutines.delay(20)
        }
        val seen = eventsA.filterIsInstance<RelayEvent.JamChanged>().last().jam.state
        assertEquals(false, seen.playing)
        assertEquals(12_000, seen.positionMs)
        assertEquals(b.me.id, seen.by)
        // The relay's clock (same machine here): the offset is small.
        Thread.sleep(1_500)
        assertTrue("offset ${linkA.clockOffsetMs}", kotlin.math.abs(linkA.clockOffsetMs) < 500)
        // Over HTTP too (when the line is down), and a stale change is answered with the current jam.
        val stale = b.api.postJamState(jam.id, joined.state.rev, joined.state, withQueue = false)
        assertNotEquals(joined.state.rev, stale.state.rev)
        a.api.endJam(jam.id)
        withTimeout(5_000) { while (eventsB.none { it is RelayEvent.JamEnded }) kotlinx.coroutines.delay(20) }
        linkA.want(false)
        linkB.want(false)
        scope.cancel()
    }

    @Test
    fun deletingTheProfileEndsItsToken() {
        val a = Phone().register()
        a.api.deleteAccount()
        val e = runCatching { a.api.me() }.exceptionOrNull() as RelayException
        assertEquals(401, e.status)
    }

    @Test
    fun youTubeMusicCoversAndLinksTravelOthersAreLeftBehind() {
        val a = Phone().register()
        val b = Phone().register()
        friends(a, b)
        val music = song(5).copy(
            cover = "https://lh3.googleusercontent.com/abc123=w544-h544-l90-rj",
            url = "https://music.youtube.com/watch?v=vid00000005",
        )
        a.api.share(b.me.id, music)
        // A picture from anywhere else would make the friend's phone call that server: the song goes, the picture doesn't.
        a.api.share(b.me.id, song(6).copy(cover = "https://example.com/spy.jpg", url = "https://example.com/x"))
        val got = b.api.mailbox().deliveries.mapNotNull { it.track }.associateBy { it.videoId }
        assertEquals(music.cover, got.getValue(music.videoId).cover)
        assertEquals(music.url, got.getValue(music.videoId).url)
        assertNull(got.getValue(song(6).videoId).cover)
        assertNull(got.getValue(song(6).videoId).url)
    }

    /** A phone with the app open; its events as they come. */
    private fun online(p: Phone, scope: CoroutineScope): Pair<RelayLink, CopyOnWriteArrayList<RelayEvent>> {
        val link = RelayLink(http, base, { p.token }, scope)
        val events = CopyOnWriteArrayList<RelayEvent>()
        scope.launch { link.events.collect { events += it } }
        link.want(true)
        return link to events
    }

    @Test
    fun theLineSaysSoWhenTheProfileIsDeletedOnAnotherPhone() = runBlocking {
        val a = Phone().register()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val (link, events) = online(a, scope)
        withTimeout(5_000) { link.connected.first { it } }
        // The other phone (same profile, by the code) deletes it.
        val other = Phone().apply { token = api.recover(a.code).token }
        other.api.deleteAccount()
        withTimeout(5_000) { while (events.none { it == RelayEvent.SignedOut }) kotlinx.coroutines.delay(20) }
        link.want(false)
        scope.cancel()
    }

    @Test
    fun theLineSaysSoWhenAnotherPhoneSignsThisOneOut() = runBlocking {
        val a = Phone().register()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val (link, events) = online(a, scope)
        withTimeout(5_000) { link.connected.first { it } }
        val other = Phone().apply { token = api.recover(a.code).token }
        // "Sign out of the other phones" on the other one.
        http.newCall(
            okhttp3.Request.Builder().url("$base/v1/me/sessions").delete().header("Authorization", "Bearer ${other.token}").build(),
        ).execute().use { assertEquals(200, it.code) }
        withTimeout(5_000) { while (events.none { it == RelayEvent.SignedOut }) kotlinx.coroutines.delay(20) }
        assertEquals(401, (runCatching { a.api.me() }.exceptionOrNull() as RelayException).status)
        assertEquals(a.me.id, other.api.me().id)
        link.want(false)
        scope.cancel()
    }
}
