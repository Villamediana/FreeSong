package com.villamediana.sound.social

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vault's locks and how two phones of one profile are put together. */
class VaultTest {
    private val code = "7K2Q-M9XD-4TFA-H3RB"
    private val user = "uabc123"

    @Test
    fun onlyTheCodeOpensTheKeyAndOnlyTheKeyOpensTheContents() {
        val dataKey = VaultCrypto.newDataKey()
        val wrapped = VaultCrypto.wrap(dataKey, VaultCrypto.codeKey(code, user))
        // The code as typed in any way the relay accepts.
        assertArrayEquals(dataKey, VaultCrypto.unwrap(wrapped, VaultCrypto.codeKey("7k2q m9xd 4tfa h3rb", user)))
        // Another code, or the same code for another profile: nothing.
        assertNull(VaultCrypto.unwrap(wrapped, VaultCrypto.codeKey("AAAA-BBBB-CCCC-DDDD", user)))
        assertNull(VaultCrypto.unwrap(wrapped, VaultCrypto.codeKey(code, "uother")))
        val sealed = VaultCrypto.sealData("Oceano · Djavan".encodeToByteArray(), dataKey)
        assertEquals("Oceano · Djavan", VaultCrypto.openData(sealed, dataKey)!!.decodeToString())
        // What the relay holds says nothing.
        assertFalse(String(VaultCrypto.unb64(sealed), Charsets.ISO_8859_1).contains("Oceano"))
        assertNull(VaultCrypto.openData(sealed, VaultCrypto.newDataKey()))
        // A byte changed on the way: refused, not garbage.
        val bytes = VaultCrypto.unb64(sealed).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(VaultCrypto.openData(VaultCrypto.b64(bytes), dataKey))
        // A new code wraps the same key: the other phones keep opening the vault with theirs.
        val rewrapped = VaultCrypto.wrap(dataKey, VaultCrypto.codeKey("ZZZZ-YYYY-XXXX-WWWW", user))
        assertArrayEquals(dataKey, VaultCrypto.unwrap(rewrapped, VaultCrypto.codeKey("ZZZZ-YYYY-XXXX-WWWW", user)))
        assertEquals(VaultCrypto.normalizeCode("O0IL-1"), "00111")
    }

    private fun song(id: String) = VSong(id, "Song $id", "Artist")
    private fun like(id: String, at: Long = 1) = VLike(song(id), at)
    private fun play(id: String, at: Long) = VPlay(id, "Song $id", "Artist", at = at, listenedMs = 200_000, durationMs = 200_000, completed = true)
    private fun list(key: String, up: Long, vararg ids: String) = VPlaylist(key, "List $key", createdAt = key.toLong(), updatedAt = up, songs = ids.map(::song))

    @Test
    fun heartsGivenOrTakenBackOnAnyPhoneReachTheOthers() {
        val base = VaultData(likes = listOf(like("a"), like("b")))
        val here = VaultData(likes = listOf(like("a"), like("b"), like("c"))) // liked c here
        val there = VaultData(likes = listOf(like("a"), like("d"))) // took b back there, liked d
        val m = VaultMerge.merge(base, here, there, now = 10)
        assertEquals(listOf("a", "c", "d"), m.likes.map { it.song.videoId })
        // Taken back here too.
        val m2 = VaultMerge.merge(base, VaultData(likes = listOf(like("b"))), VaultData(likes = listOf(like("a"), like("b"))), 10)
        assertEquals(listOf("b"), m2.likes.map { it.song.videoId })
    }

    @Test
    fun aPhoneJustRecoveredLosesNothingAndKeepsWhatItHad() {
        // No base yet: everything is put together, nothing removed.
        val m = VaultMerge.merge(null, VaultData(likes = listOf(like("new"))), VaultData(likes = listOf(like("a"), like("b"))), 10)
        assertEquals(listOf("a", "b", "new"), m.likes.map { it.song.videoId })
    }

    @Test
    fun listeningAddsUpPlaylistsKeepTheNewerChangeAndSongsSwappedNeverGo() {
        val now = 1_000_000_000L
        val base = VaultData(playlists = listOf(list("100", 5, "x")))
        val here = VaultData(
            plays = listOf(play("x", now - 10), play("y", now - 5)),
            playlists = listOf(list("100", 9, "x", "y"), list("200", 7, "z")),
            shared = listOf(VShared("uf", "in", 1, song("s1"), 3, seen = true)),
        )
        val there = VaultData(
            plays = listOf(play("x", now - 10), play("w", now - 1), play("old", now - VaultMerge.PLAYS_KEPT_MS - 1)),
            playlists = listOf(list("100", 8, "x", "q")),
            shared = listOf(VShared("uf", "in", 1, song("s1"), 3, seen = false), VShared("uf", "out", 2, song("s2"), 4, seen = true)),
        )
        val m = VaultMerge.merge(base, here, there, now)
        assertEquals(listOf("w", "y", "x"), m.plays.map { it.videoId }) // once each, newest first, the too-old gone
        assertEquals(listOf("x", "y"), m.playlists.first { it.key == "100" }.songs.map { it.videoId }) // changed later here
        assertTrue(m.playlists.any { it.key == "200" })
        assertEquals(2, m.shared.size)
        assertTrue(m.shared.first { it.deliveryId == 1L }.seen)
        // A playlist deleted on one phone goes from the other.
        val gone = VaultMerge.merge(base, VaultData(), VaultData(playlists = listOf(list("100", 5, "x"))), now)
        assertTrue(gone.playlists.isEmpty())
    }

    @Test
    fun textsTravelAndEachReactionKeepsItsNewestSetting() {
        val text = VShared("uf", "in", 9, VSong("", ""), 5, seen = false, kind = "text", text = "oi")
        // Phone A: you reacted ❤️ at 10; phone B: you changed to 😂 at 20, and the friend's 🔥 arrived there.
        val a = VaultData(shared = listOf(text.copy(myReaction = "❤️", myReactionAt = 10)))
        val b = VaultData(shared = listOf(text.copy(seen = true, myReaction = "😂", myReactionAt = 20, theirReaction = "🔥", theirReactionAt = 15)))
        val merged = VaultMerge.merge(null, a, b, 100).shared.single()
        assertEquals("text", merged.kind)
        assertEquals("oi", merged.text)
        assertEquals(true, merged.seen)
        assertEquals("😂" to 20L, merged.myReaction to merged.myReactionAt)
        assertEquals("🔥" to 15L, merged.theirReaction to merged.theirReactionAt)
        // Taken back later on A: that wins.
        val back = VaultMerge.merge(null, VaultData(shared = listOf(merged.copy(myReaction = null, myReactionAt = 30))), VaultData(shared = listOf(merged)), 100).shared.single()
        assertEquals(null to 30L, back.myReaction to back.myReactionAt)
    }

    @Test
    fun nothingNewComparesEqualSoNothingIsWritten() {
        val d = VaultData(likes = listOf(like("b"), like("a")), plays = listOf(play("x", 5), play("y", 9)))
        val once = VaultMerge.merge(null, d, VaultData(), 10)
        assertEquals(once, VaultMerge.merge(once, once, once, 10))
    }
}
