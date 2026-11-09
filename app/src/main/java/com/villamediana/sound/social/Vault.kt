package com.villamediana.sound.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The vault's locks. What the phone knows of the person goes to the relay sealed here, with a key
 * of its own (the "data key", random, kept on each phone of the profile); the relay also keeps that
 * key, but wrapped by a key only the recovery code makes. So the relay holds bytes it cannot read,
 * and a new phone with the code opens them. AES-256-GCM; the code's key by PBKDF2-SHA256 salted
 * with the profile id. Pure (javax.crypto), tested on the JVM.
 */
object VaultCrypto {
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 60_000
    private val random = SecureRandom()

    /** The code as the relay reads it: "7k2q m9xd…", "7K2Q-M9XD-…" and O/0, I/L/1 alike. */
    fun normalizeCode(code: String): String =
        code.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }.replace('O', '0').replace('I', '1').replace('L', '1')

    /** The key the recovery code makes for this profile. */
    fun codeKey(code: String, userId: String): ByteArray {
        val spec = PBEKeySpec(normalizeCode(code).toCharArray(), "freesong-vault:$userId".toByteArray(), ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun newDataKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** The data key, wrapped by the code's key (what the relay keeps next to the vault). */
    fun wrap(dataKey: ByteArray, codeKey: ByteArray): String = b64(seal(dataKey, codeKey))

    /** The data key back; null with another code (or a vault from someone else). */
    fun unwrap(wrapped: String, codeKey: ByteArray): ByteArray? = runCatching { open(unb64(wrapped), codeKey) }.getOrNull()

    /** The vault's contents, squeezed and sealed. */
    fun sealData(plain: ByteArray, dataKey: ByteArray): String = b64(seal(gzip(plain), dataKey))

    /** The contents back; null when the key doesn't open them (or they were tampered with). */
    fun openData(sealed: String, dataKey: ByteArray): ByteArray? = runCatching { gunzip(open(unb64(sealed), dataKey)) }.getOrNull()

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(byteArrayOf(VERSION))
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray, key: ByteArray): ByteArray {
        require(sealed.size > 1 + IV_BYTES && sealed[0] == VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        cipher.updateAAD(byteArrayOf(VERSION))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

/** A song as the vault keeps it (short names: the vault travels whole on every sync). */
@Serializable
data class VSong(
    @SerialName("i") val videoId: String,
    @SerialName("t") val title: String,
    @SerialName("a") val artist: String = "",
    @SerialName("al") val album: String? = null,
    @SerialName("d") val durationMs: Long? = null,
    @SerialName("c") val cover: String? = null,
    @SerialName("u") val url: String? = null,
)

/** A heart. */
@Serializable
data class VLike(@SerialName("s") val song: VSong, @SerialName("at") val at: Long)

/** One listening session (what the taste is learned from, and the hours). */
@Serializable
data class VPlay(
    @SerialName("i") val videoId: String,
    @SerialName("t") val title: String,
    @SerialName("a") val artist: String = "",
    @SerialName("g") val genre: String? = null,
    @SerialName("at") val at: Long,
    @SerialName("l") val listenedMs: Long,
    @SerialName("d") val durationMs: Long = 0,
    @SerialName("c") val completed: Boolean = false,
    @SerialName("k") val skipped: Boolean = false,
) {
    val key: String get() = "$videoId@$at"
}

/** A playlist made in the app; [key] (its creation time) is the same on every phone. */
@Serializable
data class VPlaylist(
    @SerialName("k") val key: String,
    @SerialName("n") val name: String,
    @SerialName("ds") val description: String? = null,
    @SerialName("src") val sourceUrl: String? = null,
    @SerialName("p") val pinned: Boolean = false,
    @SerialName("at") val createdAt: Long,
    @SerialName("up") val updatedAt: Long,
    @SerialName("s") val songs: List<VSong> = emptyList(),
)

/** A playlist brought from Spotify (its songs as the import found them). */
@Serializable
data class VImported(
    @SerialName("u") val url: String,
    @SerialName("n") val name: String,
    @SerialName("o") val owner: String? = null,
    @SerialName("c") val cover: String? = null,
    @SerialName("j") val tracksJson: String,
    @SerialName("nn") val count: Int,
    @SerialName("at") val at: Long,
)

/** A message of a conversation with a friend: a song, or a text ([kind] "text", [song] empty). */
@Serializable
data class VShared(
    @SerialName("f") val friendId: String,
    @SerialName("dir") val direction: String,
    @SerialName("del") val deliveryId: Long,
    @SerialName("s") val song: VSong,
    @SerialName("at") val at: Long,
    @SerialName("sn") val seen: Boolean,
    @SerialName("k") val kind: String = "song",
    @SerialName("t") val text: String? = null,
    /** Your reaction and when it was set; the friend's and when it arrived. */
    @SerialName("mr") val myReaction: String? = null,
    @SerialName("mra") val myReactionAt: Long = 0,
    @SerialName("tr") val theirReaction: String? = null,
    @SerialName("tra") val theirReactionAt: Long = 0,
    /** The message it answers, and when its text was last corrected (the newest correction wins). */
    @SerialName("rp") val replyTo: Long? = null,
    @SerialName("ed") val editedAt: Long = 0,
) {
    val key: String get() = "$direction#$deliveryId"
}

/** The genres picked on the Home ([at]: when they last changed). */
@Serializable
data class VGenres(@SerialName("c") val chosen: Boolean, @SerialName("ids") val ids: List<String>, @SerialName("at") val at: Long)

/** Everything in the vault. */
@Serializable
data class VaultData(
    @SerialName("v") val version: Int = 1,
    @SerialName("likes") val likes: List<VLike> = emptyList(),
    @SerialName("plays") val plays: List<VPlay> = emptyList(),
    @SerialName("lists") val playlists: List<VPlaylist> = emptyList(),
    @SerialName("imp") val imported: List<VImported> = emptyList(),
    @SerialName("sh") val shared: List<VShared> = emptyList(),
    @SerialName("gen") val genres: VGenres? = null,
)

/**
 * Two phones of one profile, put together: what this phone has ([local]), what the vault has
 * ([remote], written by the others) and what both had at the last sync ([base]). Something added on
 * either side stays; something removed on either side since the last sync goes (a heart taken back
 * on one phone is taken back on all); a playlist changed on both keeps the newer change; the
 * listening and the conversations only add up (each reaction keeps its newest setting). With no [base] (a phone just recovered, the first
 * sync) nothing is removed: everything is put together. Pure.
 */
object VaultMerge {
    /** Listening older than this leaves the vault (the taste's long memory fades long before). */
    const val PLAYS_KEPT_MS = 400L * 24 * 3_600_000L
    const val MAX_PLAYS = 25_000

    /** The result is in a fixed order, so "nothing changed" compares equal and nothing is written. */
    fun merge(base: VaultData?, local: VaultData, remote: VaultData, now: Long): VaultData = VaultData(
        likes = threeWay(base?.likes, local.likes, remote.likes, { it.song.videoId }) { l, r -> if (r.at > l.at) r else l }
            .sortedBy { it.song.videoId },
        plays = (local.plays + remote.plays).distinctBy { it.key }
            .filter { now - it.at <= PLAYS_KEPT_MS }
            .sortedWith(compareByDescending<VPlay> { it.at }.thenBy { it.videoId })
            .take(MAX_PLAYS),
        playlists = threeWay(base?.playlists, local.playlists, remote.playlists, { it.key }) { l, r -> if (r.updatedAt > l.updatedAt) r else l }
            .sortedBy { it.key },
        imported = threeWay(base?.imported, local.imported, remote.imported, { it.url }) { l, r -> if (r.at > l.at) r else l }
            .sortedBy { it.url },
        shared = (local.shared + remote.shared).groupBy { it.key }.values
            .map { same ->
                val mine = same.maxBy { it.myReactionAt }
                val theirs = same.maxBy { it.theirReactionAt }
                val edited = same.maxBy { it.editedAt }
                same.first().copy(
                    seen = same.any { it.seen },
                    myReaction = mine.myReaction, myReactionAt = mine.myReactionAt,
                    theirReaction = theirs.theirReaction, theirReactionAt = theirs.theirReactionAt,
                    text = edited.text, editedAt = edited.editedAt,
                    replyTo = same.firstNotNullOfOrNull { it.replyTo },
                )
            }
            .sortedWith(compareBy<VShared> { it.at }.thenBy { it.key }),
        genres = listOfNotNull(local.genres, remote.genres).maxByOrNull { it.at },
    )

    /**
     * Keeps what both sides have ([pick] chooses between two versions), what one side added since
     * [base], and drops what one side removed since [base].
     */
    internal fun <T> threeWay(base: List<T>?, local: List<T>, remote: List<T>, key: (T) -> String, pick: (T, T) -> T): List<T> {
        val b = base?.mapTo(HashSet(), key)
        val l = local.associateBy(key)
        val r = remote.associateBy(key)
        val out = LinkedHashMap<String, T>()
        for ((k, lv) in l) {
            val rv = r[k]
            when {
                rv != null -> out[k] = pick(lv, rv)
                b == null || k !in b -> out[k] = lv // added here
                else -> Unit // removed on another phone
            }
        }
        for ((k, rv) in r) if (k !in l && (b == null || k !in b)) out[k] = rv // added on another phone
        return out.values.toList()
    }
}
