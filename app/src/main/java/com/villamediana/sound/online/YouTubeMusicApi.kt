package com.villamediana.sound.online

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException

/**
 * The YouTube Music (InnerTube "WEB_REMIX") calls NewPipeExtractor doesn't cover: artist pages,
 * album pages (MPREb_ → OLAK5uy_ playlist, year), playlist headers, suggestions, and the song/album
 * search shelves read in full (album of each song, year of each album).
 * Blocking — call from Dispatchers.IO.
 */
internal class YouTubeMusicApi(private val http: OkHttpClient) {
    companion object {
        /** Shelf selectors of music.youtube.com/search (same values the web app sends). */
        const val PARAMS_SONGS = "Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D"
        const val PARAMS_ALBUMS = "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"
    }

    private val jsonType = "application/json".toMediaType()

    fun browse(browseId: String, params: String? = null): JsonObject =
        post("browse", buildJsonObject {
            put("context", context())
            put("browseId", browseId)
            if (params != null) put("params", params)
        })

    /** First page of a YouTube Music search; [params] selects the shelf (songs, albums…). */
    fun search(query: String, params: String): JsonObject =
        post("search", buildJsonObject {
            put("context", context())
            put("query", query)
            put("params", params)
        })

    fun searchContinuation(token: String): JsonObject {
        val t = java.net.URLEncoder.encode(token, "UTF-8")
        return post("search", buildJsonObject { put("context", context()) }, "ctoken=$t&continuation=$t&type=next")
    }

    /**
     * The "up next" panel of a song: YouTube Music's radio that starts from it (playlist RDAMVM…),
     * the same list the web player queues after the song.
     */
    fun next(videoId: String): JsonObject =
        post("next", buildJsonObject {
            put("context", context())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            putJsonObject("watchEndpointMusicSupportedConfigs") {
                putJsonObject("watchEndpointMusicConfig") {
                    put("hasPersistentPlaylistPanel", true)
                    put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
                }
            }
        })

    fun suggestions(query: String): List<String> =
        YouTubeMusicParser.suggestions(post("music/get_search_suggestions", buildJsonObject {
            put("context", context())
            put("input", query)
        }))

    private fun context(): JsonObject {
        val loc = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        return buildJsonObject {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", YoutubeParsingHelper.getYoutubeMusicClientVersion())
                put("hl", loc.languageCode.ifEmpty { "en" })
                put("gl", country.countryCode.ifEmpty { "US" })
                put("platform", "DESKTOP")
                put("utcOffsetMinutes", 0)
            }
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
    }

    private fun post(endpoint: String, body: JsonObject, query: String? = null): JsonObject {
        val builder = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?" + (query?.let { "$it&" } ?: "") + "prettyPrint=false")
            .post(body.toString().toRequestBody(jsonType))
            .header("User-Agent", NewPipeHttp.USER_AGENT)
            .header("Accept-Language", acceptLanguage())
        val headers = YoutubeParsingHelper.getYoutubeMusicHeaders() + YoutubeParsingHelper.getCookieHeader()
        headers.forEach { (name, values) -> values.firstOrNull()?.let { builder.header(name, it) } }
        http.newCall(builder.build()).execute().use { response ->
            when (response.code) {
                in 200..299 -> Unit
                404, 400 -> throw OnlineException("YouTube Music: ${response.code} for $endpoint", kind = OnlineException.Kind.NOT_FOUND)
                429 -> throw OnlineException("YouTube Music rate limit", kind = OnlineException.Kind.RATE_LIMITED)
                else -> throw IOException("YouTube Music: HTTP ${response.code}")
            }
            return OnlineJson.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    private fun acceptLanguage(): String {
        val loc = NewPipe.getPreferredLocalization()
        return loc.localizationCode + "," + loc.languageCode + ";q=0.9,en;q=0.5"
    }
}
