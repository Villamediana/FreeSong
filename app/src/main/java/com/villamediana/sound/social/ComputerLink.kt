package com.villamediana.sound.social

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Linking a computer (FreeSong on the web, freesong.simplyonegoal.com/web) to this profile, the same
 * way the Novelo does: the computer shows a QR code with its link id, its public key (ECDH P-256) and
 * a secret of 16 bytes that is only on its screen; this phone answers with the vault's key encrypted
 * so only that computer opens it — AES-256-GCM with HKDF-SHA256(ECDH ‖ secret, salt = the link id,
 * "freesong-link-v1"), a key pair made only for this answer. The relay carries bytes it can't open,
 * and can't make an answer the computer would take (it never saw the secret). Pure, tested on the JVM
 * against the page's own code (web/src/core/crypto.js).
 */
object ComputerLink {
    /** What a computer's QR code says. */
    data class Code(val linkId: String, val webKey: String, val secret: String)

    /** The answer: this phone's one-time public key, the IV and the ciphertext (all base64url). */
    data class Wrapped(val pub: String, val iv: String, val ct: String)

    private const val IV = 12
    private val INFO = "freesong-link-v1".toByteArray()
    private val random = SecureRandom()
    private val CODE = Regex("""^https://([A-Za-z0-9.-]+)/web/link/?#([A-Za-z0-9_-]{22})\.([A-Za-z0-9_-]{80,100})\.([A-Za-z0-9_-]{22})$""")

    /**
     * The address in a computer's QR code (also what opens the app from the phone's camera): only one
     * of FreeSong's own server ([host]), with a link, a key and a secret of the right shape.
     */
    fun code(text: String?, host: String = com.villamediana.sound.online.FreeSongLinks.HOST): Code? {
        val m = CODE.matchEntire(text?.trim() ?: return null) ?: return null
        if (!m.groupValues[1].equals(host, ignoreCase = true)) return null
        val key = m.groupValues[3]
        if (runCatching { unb64u(key) }.getOrNull()?.let { it.size == 65 && it[0] == 4.toByte() } != true) return null
        if (runCatching { unb64u(m.groupValues[4]) }.getOrNull()?.size != 16) return null
        return Code(m.groupValues[2], key, m.groupValues[4])
    }

    /** The answer to [code]: profile [user] and the vault's key ([dataKey], 32 bytes), from [owner]'s phone. */
    fun wrap(code: Code, user: String, dataKey: ByteArray, owner: String): Wrapped {
        require(dataKey.size == 32) { "vault key: 32 bytes" }
        val extra = unb64u(code.secret)
        require(extra.size == 16) { "code secret" }
        val peer = publicKey(unb64u(code.webKey))
        val mine = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val shared = KeyAgreement.getInstance("ECDH").apply {
            init(mine.private)
            doPhase(peer, true)
        }.generateSecret()
        val lock = hkdf(shared + extra, code.linkId.toByteArray(), INFO, 32)
        val iv = ByteArray(IV).also(random::nextBytes)
        val text = buildJsonObject {
            put("user", JsonPrimitive(user))
            put("key", JsonPrimitive(b64u(dataKey)))
            put("owner", JsonPrimitive(owner))
        }.toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(lock, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(code.linkId.toByteArray())
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Wrapped(b64u(raw(mine.public as ECPublicKey)), b64u(iv), b64u(ct))
    }

    /** HKDF-SHA256 (RFC 5869): extract with [salt], expand with [info] to [length] bytes. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    private val P256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    /** A P-256 public key raw (0x04 ‖ X ‖ Y, 65 bytes). */
    private fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "P-256 raw key: 65 bytes" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
    }

    private fun raw(key: ECPublicKey): ByteArray {
        fun fixed(n: BigInteger) = n.toByteArray().let { b -> if (b.size == 32) b else if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64u(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/** A computer linked to this profile, as the relay lists it. */
@Serializable
data class Computer(
    val id: String,
    val name: String,
    /** "qr" (read by a phone) or "code" (the recovery code typed on it). */
    val via: String = "qr",
    /** Someone else's computer: unlinked when its tab closes. */
    val shared: Boolean = false,
    val linkedAt: Long = 0,
    val lastSeen: Long = 0,
)
