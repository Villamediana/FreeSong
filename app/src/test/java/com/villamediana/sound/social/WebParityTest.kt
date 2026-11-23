package com.villamediana.sound.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The phone and FreeSong on the web (web/src/core/crypto.js) lock things the same way, both ways:
 * - what the page sealed (web/tools/fixtures.mjs → test resources/web-fixtures.json) opens here: the
 *   vault's contents, its key wrapped by the recovery code, a computer's QR code;
 * - what this phone makes for the page (a vault, the key wrapped by the code, the answer to the QR
 *   code) goes to web/test/fixtures/phone.json, which the page's own test opens (npm test in web/).
 */
class WebParityTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("web-fixtures.json")!!.readText(),
    ).jsonObject

    private fun str(key: String) = fixture[key]!!.jsonPrimitive.content

    @Test
    fun whatThePageSealedOpensOnThePhone() {
        val raw = VaultCrypto.unb64(str("rawKey"))
        // The key the page wrapped with the code: the code (typed any way) opens it.
        assertArrayEquals(raw, VaultCrypto.unwrap(str("wrappedKey"), VaultCrypto.codeKey(str("code").lowercase(), str("user"))))
        assertNull(VaultCrypto.unwrap(str("wrappedKey"), VaultCrypto.codeKey("AAAA-BBBB-CCCC-DDDD", str("user"))))
        // The contents the page sealed (gzip + AES-GCM in the browser) are the vault the phone reads.
        val opened = VaultCrypto.openData(str("vault"), raw)!!.decodeToString()
        val data = RelayJson.decodeFromString(VaultData.serializer(), opened)
        assertEquals(listOf("dQw4w9WgXcQ"), data.likes.map { it.song.videoId })
        assertEquals("Djavan", data.likes.single().song.artist)
        assertEquals(240_000L, data.likes.single().song.durationMs)
        assertEquals(true, data.plays.single().completed)
        assertEquals("Da web ção", data.playlists.single().name)
        assertEquals(1_700_000_002_000L, data.playlists.single().createdAt)
        assertEquals(listOf("mpb"), data.genres?.ids)
        // And put together with itself, nothing changes (nothing would be written back).
        assertEquals(data, VaultMerge.merge(data, data, data, 1_700_000_005_000L))
    }

    @Test
    fun whatPlaysOnThePageOpensOnThePhoneAndIsNoVault() {
        val raw = VaultCrypto.unb64(str("rawKey"))
        val sealed = fixture["now"]!!.jsonObject["sealed"]!!.jsonPrimitive.content
        val opened = VaultCrypto.openNow(sealed, raw)!!.decodeToString()
        val blob = NowShare.Codec.decodeFromString(NowBlob.serializer(), opened)
        assertEquals("webfixture1", blob.dev)
        assertEquals("dQw4w9WgXcQ", blob.i)
        assertEquals("Djavan", blob.a)
        assertEquals(240_000L, blob.d)
        assertEquals(61_000L, blob.p)
        assertEquals(true, blob.s)
        assertNull(blob.c)
        // Sealed for "now", not for the vault: neither opens as the other.
        assertNull(VaultCrypto.openData(sealed, raw))
        assertNull(VaultCrypto.openNow(str("vault"), raw))
        // Playing for 10 s since it was sent: 10 s further; paused stays put; never past the end.
        assertEquals(71_000L, NowShare.positionNow(blob, 10_000))
        assertEquals(61_000L, NowShare.positionNow(blob.copy(s = false), 10_000))
        assertEquals(237_000L, NowShare.positionNow(blob, 1_000_000))
    }

    @Test
    fun theComputersCodeIsReadAndTheAnswerGoesToThePage() {
        val link = fixture["link"]!!.jsonObject
        val url = link["url"]!!.jsonPrimitive.content
        val code = ComputerLink.code(url)
        assertNotNull(code)
        assertEquals(link["id"]!!.jsonPrimitive.content, code!!.linkId)
        // Only FreeSong's own address, whole and well formed.
        assertNull(ComputerLink.code(url.replace("freesong.simplyonegoal.com", "freesong.example.com")))
        assertNull(ComputerLink.code(url + "x"))
        assertNull(ComputerLink.code(url.substringBefore('#')))

        val raw = VaultCrypto.newDataKey()
        val content = VaultData(likes = listOf(VLike(VSong("kotlin12345", "Do celular", "FreeSong"), 7)))
        val sealed = VaultCrypto.sealData(RelayJson.encodeToString(VaultData.serializer(), content).encodeToByteArray(), raw)
        val wrappedKey = VaultCrypto.wrap(raw, VaultCrypto.codeKey(str("code"), str("user")))
        val answer = ComputerLink.wrap(code, str("user"), raw, "Pixel 8")
        val out = buildJsonObject {
            put("rawKey", JsonPrimitive(VaultCrypto.b64(raw)))
            put("vault", JsonPrimitive(sealed))
            put("wrappedKey", JsonPrimitive(wrappedKey))
            put("now", JsonPrimitive(VaultCrypto.sealNow(NowShare.Codec.encodeToString(NowBlob.serializer(), NowBlob(dev = "phonefixture", i = "kotlin12345", t = "Do celular", a = "FreeSong", d = 200_000, p = 42_000, s = false, at = 1_700_000_010_000)).encodeToByteArray(), raw)))
            put("answer", buildJsonObject {
                put("pub", JsonPrimitive(answer.pub))
                put("iv", JsonPrimitive(answer.iv))
                put("ct", JsonPrimitive(answer.ct))
            })
        }
        val file = File("../web/test/fixtures/phone.json")
        file.parentFile.mkdirs()
        file.writeText(out.toString() + "\n")
    }

    @Test
    fun hkdfIsTheRfcOne() {
        // RFC 5869, test case 1.
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = ComputerLink.hkdf(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.joinToString("") { "%02x".format(it) })
    }
}
