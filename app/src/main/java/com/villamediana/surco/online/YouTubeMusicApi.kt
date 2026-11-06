package com.villamediana.surco.online

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
 * The few YouTube Music (InnerTube "WEB_REMIX") calls NewPipeExtractor doesn't cover: artist pages,
 * album pages (MPREb_ → OLAK5uy_ playlist, year), playlist headers and music search suggestions.
 * Blocking — call from Dispatchers.IO.
 */
internal class YouTubeMusicApi(private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    fun browse(browseId: String, params: String? = null): JsonObject =
        post("browse", buildJsonObject {
            put("context", context())
            put("browseId", browseId)
            if (params != null) put("params", params)
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

    private fun post(endpoint: String, body: JsonObject): JsonObject {
        val builder = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
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
