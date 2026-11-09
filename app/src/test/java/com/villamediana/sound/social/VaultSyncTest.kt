package com.villamediana.sound.social

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.RemoteTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing phones with the code, and one profile on several phones: the real relay (Node), two
 * phones each with its own database. What one has, the other gets; what one takes back, the other
 * loses; a new code doesn't lock the phones out; the relay never sees what's inside.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VaultSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opened = mutableListOf<SoundDatabase>()

    @After fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
    }

    /** One phone: its database, its key store, its token and the code it shows. */
    private inner class Phone(name: String) {
        val db = SoundDatabase.inMemory(ApplicationProvider.getApplicationContext()).also { opened += it }
        val library = LibraryRepository(db, scope)
        var token: String? = null
        var userId = ""
        var code: String? = null
        var genres: Pair<Boolean, Set<String>> = false to emptySet()
        val keys = HashMap<String, ByteArray>()
        val api = RelayApi(LocalRelay.http, LocalRelay.base()) { token }
        val vault = VaultSync(
            dir = tmp.newFolder(name),
            api = api,
            local = PhoneVault(library, db.sharedSongs(), { genres }, { genres = true to it }),
            profile = { userId.takeIf { it.isNotEmpty() }?.let { it to code } },
            loadKey = { keys[it] },
            saveKey = { id, k -> if (k == null) keys.remove(id) else keys[id] = k },
        )

        fun create() = apply {
            val made = api.createAccount()
            token = made.token
            userId = made.me.id
            code = made.recoveryCode
        }

        fun recover(withCode: String) = apply {
            val r = api.recover(withCode)
            token = r.token
            userId = r.me.id
            code = withCode
        }

        suspend fun likes() = library.vaultLikes().map { it.first.videoId }.sorted()
    }

    private fun remote(id: String, title: String = "Song $id") =
        RemoteTrack(id, "https://music.youtube.com/watch?v=$id", title, "Artist $id", durationMs = 200_000, thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg")

    @Test
    fun anotherPhoneWithTheCodeGetsEverythingAndBothStayInStep() = runBlocking {
        val a = Phone("a").create()
        // Phone A: a downloaded song with a heart, streamed hearts, listening, a playlist, a song from a friend, genres.
        val fileId = a.library.insertTrack(Track(title = "No celular", artist = "Banda", videoId = "file0000001", filePath = "/x.m4a", durationMs = 200_000, liked = true, likedAt = 1_000))
        a.library.setLiked(remote("oceano00001", "Oceano"), true)
        a.library.setLiked(remote("trembala001"), true)
        repeat(3) { a.library.recordOnlinePlay(remote("oceano00001", "Oceano"), 200_000, 200_000, "test", now = 1_800_000_000_000L - it * 3_600_000L) }
        val list = a.library.createPlaylist("Viagem")
        a.library.addOnlineToPlaylist(list, remote("oceano00001", "Oceano"))
        a.library.addToPlaylist(list, listOf(fileId))
        a.db.sharedSongs().insert(SharedSong(friendId = "ufriend", direction = SharedSong.IN, deliveryId = 7, videoId = "fromfriend1", title = "De um amigo", artist = "X", at = 5, seen = false))
        a.genres = true to setOf("mpb", "rock")
        assertEquals(VaultSync.Outcome.SYNCED, a.vault.sync())

        // The relay holds bytes it can't read.
        val stored = a.api.vault()!!
        assertFalse(String(VaultCrypto.unb64(stored.data), Charsets.ISO_8859_1).contains("Oceano"))
        // Nothing changed: nothing written again.
        assertEquals(VaultSync.Outcome.SYNCED, a.vault.sync())
        assertEquals(stored.rev, a.api.vault()!!.rev)

        // Phone B, new: the code, and everything is there.
        val b = Phone("b").recover(a.code!!)
        assertEquals(VaultSync.Outcome.SYNCED, b.vault.sync())
        assertEquals(listOf("file0000001", "oceano00001", "trembala001"), b.likes())
        val heard = b.library.onlinePlaysListSince(0)
        assertEquals(3, heard.count { it.videoId == "oceano00001" })
        val bList = b.library.vaultPlaylists().single()
        assertEquals("Viagem", bList.playlist.name)
        assertEquals(listOf("oceano00001", "file0000001"), bList.songs.map { it.videoId })
        assertEquals("De um amigo", b.db.sharedSongs().all().single().title)
        assertEquals(true to setOf("mpb", "rock"), b.genres)
        // The taste learned on A is the same on B (the same listening and hearts).
        assertTrue(b.library.likedOnlineList().any { it.videoId == "oceano00001" })

        // Now both are used: B takes a heart back and gives one; A makes a playlist.
        b.library.unlikeFromVault("trembala001")
        b.library.setLiked(remote("novinha0001"), true)
        a.library.createPlaylist("Treino")
        assertEquals(VaultSync.Outcome.SYNCED, b.vault.sync())
        assertEquals(VaultSync.Outcome.SYNCED, a.vault.sync())
        assertEquals(listOf("file0000001", "novinha0001", "oceano00001"), a.likes())
        assertEquals(VaultSync.Outcome.SYNCED, b.vault.sync())
        assertEquals(setOf("Viagem", "Treino"), b.library.vaultPlaylists().map { it.playlist.name }.toSet())

        // A playlist changed on A reaches B (its songs, in order).
        a.library.addOnlineToPlaylist(list, remote("trembala001"))
        a.vault.sync()
        b.vault.sync()
        assertEquals(listOf("oceano00001", "file0000001", "trembala001"), b.library.vaultPlaylists().first { it.playlist.name == "Viagem" }.songs.map { it.videoId })

        // A new code on A: B keeps syncing (it has the key); a third phone opens it with the new code.
        val fresh = a.api.newRecoveryCode()
        a.code = fresh
        assertEquals(VaultSync.Outcome.SYNCED, a.vault.rekey(fresh))
        b.library.setLiked(remote("depois00001"), true)
        assertEquals(VaultSync.Outcome.SYNCED, b.vault.sync())
        val c = Phone("c").recover(fresh)
        assertEquals(VaultSync.Outcome.SYNCED, c.vault.sync())
        assertTrue("depois00001" in c.likes())
        // The old code opens nothing any more.
        assertTrue(VaultCrypto.unwrap(a.api.vault()!!.key, VaultCrypto.codeKey(a.code!!, a.userId)) != null)
        assertTrue(VaultCrypto.unwrap(a.api.vault()!!.key, VaultCrypto.codeKey(b.code!!, a.userId)) == null)
    }

    @Test
    fun twoPhonesWritingAtOnceLoseNothing() = runBlocking {
        val a = Phone("a2").create()
        a.library.setLiked(remote("first000001"), true)
        a.vault.sync()
        val b = Phone("b2").recover(a.code!!)
        b.vault.sync()
        // Both like something before either syncs: the second to write reads again and puts it together.
        a.library.setLiked(remote("fromphonea1"), true)
        b.library.setLiked(remote("fromphoneb1"), true)
        a.vault.sync()
        b.vault.sync()
        a.vault.sync()
        val all = listOf("first000001", "fromphonea1", "fromphoneb1")
        assertEquals(all, a.likes())
        assertEquals(all, b.likes())
    }

    @Test
    fun aDeletedProfileTakesItsVault() = runBlocking {
        val a = Phone("a3").create()
        a.library.setLiked(remote("x0000000001"), true)
        a.vault.sync()
        a.api.deleteAccount()
        assertEquals(VaultSync.Outcome.OFFLINE, a.vault.sync()) // the relay doesn't know this phone any more
        assertTrue(a.library.likedOnlineList().first().videoId == "x0000000001") // what's on the phone stays
    }
}
