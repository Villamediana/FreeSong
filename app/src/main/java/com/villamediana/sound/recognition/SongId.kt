package com.villamediana.sound.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What a few seconds of a song turned out to be. */
@kotlinx.serialization.Serializable
data class SongMatch(
    val title: String,
    val artist: String,
    val album: String = "",
    val year: String = "",
    val genre: String = "",
    val cover: String = "",
    val url: String = "",
    /** Where the match was in the song, in seconds, when Shazam said. */
    val offset: Double? = null,
    val lyrics: List<String> = emptyList(),
    val youtube: String = "",
)

/**
 * Names a song from audio: the fingerprint is made on the phone
 * ([ShazamSignature]) and only that is sent to Shazam's own matcher.
 */
object SongId {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [pcm] at 16 kHz mono. A long recording is fingerprinted from its middle,
     * where the song is surely playing; a short one from the start.
     */
    suspend fun identify(pcm: ShortArray, language: String = "pt-BR", country: String = "BR"): Result<SongMatch?> = withContext(Dispatchers.Default) {
        runCatching {
            val seconds = pcm.size / ShazamSignature.RATE
            val from = if (seconds > 36) ShazamSignature.RATE * (seconds / 2 - 6) else 0
            val sig = ShazamSignature.generate(pcm, from, maxSeconds = 12.0)
            require(sig.peakCount > 0) { "nothing in that audio to recognise" }
            withContext(Dispatchers.IO) { ask(sig, language, country) }
        }
    }

    private fun ask(sig: ShazamSignature.Signature, language: String, country: String): SongMatch? {
        val body = buildJsonObject {
            put("timezone", java.util.TimeZone.getDefault().id)
            putJsonObject("signature") {
                put("uri", ShazamSignature.uri(sig))
                put("samplems", sig.sampleMs)
            }
            put("timestamp", System.currentTimeMillis())
            putJsonObject("context") {}
            putJsonObject("geolocation") {}
        }.toString()
        val url = "https://amp.shazam.com/discovery/v5/$language/$country/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val request = Request.Builder().url(url)
            .header("X-Shazam-Platform", "IPHONE")
            .header("X-Shazam-AppVersion", "14.1.0")
            .header("Accept", "*/*")
            .header("Accept-Language", language)
            .header("User-Agent", "Shazam/3685 CFNetwork/1197 Darwin/20.0.0")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("Shazam answered ${r.code}")
            return parse(r.body?.string().orEmpty())
        }
    }

    fun parse(text: String): SongMatch? {
        val root = json.parseToJsonElement(text).jsonObject
        val track = root["track"] as? JsonObject ?: return null
        fun JsonObject.s(k: String) = (this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }).orEmpty()
        val images = track["images"] as? JsonObject
        val sections = (track["sections"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val meta = sections.flatMap { (it["metadata"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .associate { it.s("title") to it.s("text") }
        val lyrics = sections.firstOrNull { it.s("type") == "LYRICS" }
            ?.get("text")?.let { runCatching { it.jsonArray.mapNotNull { l -> l.jsonPrimitive.contentOrNull } }.getOrNull() }
            .orEmpty()
        val youtube = sections.firstOrNull { it.s("type") == "VIDEO" }?.let { v ->
            (v["youtubeurl"] as? JsonObject)?.let { y ->
                ((y["actions"] as? JsonArray)?.firstOrNull() as? JsonObject)?.s("uri")
            }
        }.orEmpty()
        val offset = (root["matches"] as? JsonArray)?.firstOrNull()?.let { m ->
            (m as? JsonObject)?.get("offset")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        }
        return SongMatch(
            title = track.s("title"),
            artist = track.s("subtitle"),
            album = meta["Album"].orEmpty(),
            year = meta["Released"].orEmpty(),
            genre = (track["genres"] as? JsonObject)?.s("primary").orEmpty(),
            cover = images?.s("coverarthq")?.ifEmpty { images.s("coverart") }.orEmpty(),
            url = track.s("url"),
            offset = offset,
            lyrics = lyrics,
            youtube = youtube,
        )
    }
}
