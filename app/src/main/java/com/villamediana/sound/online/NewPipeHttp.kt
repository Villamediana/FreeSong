package com.villamediana.sound.online

import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/** NewPipeExtractor's HTTP bridge over OkHttp. */
class NewPipeHttp(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val responseBody = response.body.string()
            return Response(response.code, response.message, response.headers.toMultimap(), responseBody, response.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        @Volatile private var initialized = false

        /**
         * The language YouTube answers in (titles, dates, suggestions): the one the app shows, set by
         * [relocalize]. Null = the phone's.
         */
        @Volatile private var preferred: Locale? = null

        /** Idempotent. Uses the app's language (else the phone's) and the phone's country. */
        fun init(client: OkHttpClient, locale: Locale = preferred ?: Locale.getDefault()) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                NewPipe.init(NewPipeHttp(client), localizationOf(locale), contentCountryOf(locale))
                initialized = true
            }
        }

        /**
         * YouTube in [locale] from now on. A phone in English with the app in Portuguese got the titles
         * translated to English: the request now follows what the person reads on screen.
         */
        fun relocalize(locale: Locale) {
            synchronized(this) {
                if (preferred == locale) return
                preferred = locale
                if (initialized) NewPipe.setupLocalization(localizationOf(locale), contentCountryOf(locale))
            }
        }

        private fun countryOf(locale: Locale) = locale.country.takeIf { it.length == 2 } ?: "BR"
        private fun localizationOf(locale: Locale) = Localization(locale.language.ifEmpty { "pt" }, countryOf(locale))
        private fun contentCountryOf(locale: Locale) = ContentCountry(countryOf(locale))
    }
}
